package com.leaqutra.shworkcloud.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.dto.AdminUserQuery;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.PasswordGenerator;
import com.leaqutra.shworkcloud.security.PasswordHasher;
import com.leaqutra.shworkcloud.security.UserCache;
import com.leaqutra.shworkcloud.vo.AdminVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台用户管理。
 * <p>
 * 三条必须守住的红线：
 * <ol>
 *   <li><b>超管受保护</b>：不可禁用、不可删除、不可降级；</li>
 *   <li><b>禁用/重置/删除都要立即生效</b>：kickout 踢下线 + disable 禁止再次登录 +
 *       清 UserCache（否则最长 60 秒的缓存窗口内仍可操作）；</li>
 *   <li><b>角色校验不在这里做</b>：由 Controller 上的 {@code @SaCheckRole} 与
 *       路由拦截器负责。v1.1 把角色注解标在 Service 上，拦截器读不到，等于后台裸奔。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserMapper userMapper;
    private final FileEntryMapper fileEntryMapper;
    private final UserCache userCache;
    private final QuotaService quotaService;
    private final OssSignService ossSignService;
    private final AuditService auditService;
    private final LoginUser loginUser;

    /** 后台代改资料时允许修改的字段（顺序即文档顺序） */
    private static final Set<String> EDITABLE_FIELDS =
            Set.of("realName", "studentNo", "className", "email", "nickname");

    /**
     * 不允许走「代改资料」的字段，以及它们各自的专用接口。
     * <p>这类字段要么能锁死账号（status / password），要么能提权（role），
     * 要么有独立的对象存储副作用（avatar），必须走各自的接口而不是被顺手改掉。
     */
    private static final Map<String, String> REDIRECTED_FIELDS = Map.of(
            "username", "登录名是登录凭据，不支持修改；如需更换请重建账号",
            "role", "请用 PUT /api/admin/users/{id}/role",
            "status", "请用 PUT /api/admin/users/{id}/status",
            "storageQuota", "请用 PUT /api/admin/users/{id}/quota",
            "quotaBytes", "请用 PUT /api/admin/users/{id}/quota",
            "password", "请用 PUT /api/admin/users/{id}/reset-password",
            "usedStorage", "请用 PUT /api/admin/users/{id}/recalc-storage",
            "avatar", "头像由用户自己在个人资料页上传：POST /api/user/avatar",
            "avatarKey", "头像由用户自己在个人资料页上传：POST /api/user/avatar",
            "avatarUrl", "头像由用户自己在个人资料页上传：POST /api/user/avatar");

    // ---------------------------------------------------------------- 查询

    public PageVO<AdminVo.AdminUserVo> page(AdminUserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getStudentNo, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getEmail, keyword));
        }
        if (StringUtils.hasText(query.getClassName())) {
            wrapper.eq(SysUser::getClassName, query.getClassName().trim());
        }
        if (query.getRole() != null) {
            wrapper.eq(SysUser::getRole, query.getRole());
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysUser::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(SysUser::getCreateTime);

        Page<SysUser> page = new Page<>(query.normalizedPage(), query.normalizedSize());
        return PageVO.of(userMapper.selectPage(page, wrapper), this::toVo);
    }

    /** 班级列表（供筛选下拉） */
    public List<String> classes() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .select(SysUser::getClassName)
                        .isNotNull(SysUser::getClassName)
                        .ne(SysUser::getClassName, "")
                        .groupBy(SysUser::getClassName))
                .stream().map(SysUser::getClassName).filter(StringUtils::hasText).sorted().toList();
    }

    private AdminVo.AdminUserVo toVo(SysUser user) {
        return new AdminVo.AdminUserVo(
                user.getId(), user.getUsername(), user.getStudentNo(), user.getRealName(),
                user.getClassName(), user.getEmail(),
                user.getRole() == null ? 0 : user.getRole().intValue(),
                user.getStatus() == null ? 1 : user.getStatus().intValue(),
                user.getStorageQuota() == null ? 0L : user.getStorageQuota(),
                user.getUsedStorage() == null ? 0L : user.getUsedStorage(),
                user.getLastLoginTime(), user.getLastLoginIp(), user.getCreateTime());
    }

    // ---------------------------------------------------------------- 写操作

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, byte status, String clientIp) {
        SysUser user = mustExist(id);
        guardSuperAdmin(user, "禁用/启用");
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.BAD_PARAM, "状态值只能是 0 或 1");
        }
        userMapper.updateStatus(id, status);
        userCache.evict(id);
        if (status == 0) {
            StpUtil.kickout(id);
            // -1 表示永久封禁，防止被踢出后立刻重新登录
            StpUtil.disable(id, -1);
        } else {
            StpUtil.untieDisable(id);
        }
        auditService.log(loginUser.id(), "USER_STATUS_CHANGE", "USER", String.valueOf(id),
                clientIp, true, "status=" + status);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateQuota(Long id, long quotaBytes, String clientIp) {
        SysUser user = mustExist(id);
        if (quotaBytes < 0 || quotaBytes > 10L * 1024 * 1024 * 1024 * 1024) {
            throw new BizException(ErrorCode.BAD_PARAM, "容量应在 0 ~ 10TB 之间");
        }
        long used = user.getUsedStorage() == null ? 0L : user.getUsedStorage();
        if (quotaBytes < used) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "新配额小于已用容量（" + used + " 字节），请先让该用户清理文件");
        }
        userMapper.updateQuota(id, quotaBytes);
        auditService.log(loginUser.id(), "USER_QUOTA_CHANGE", "USER", String.valueOf(id),
                clientIp, true, "quota=" + quotaBytes);
    }

    /**
     * 重置密码。
     * <p>返回的初始密码<b>只在此处返回一次</b>，不写日志、不写审计 detail。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminVo.ResetPasswordVo resetPassword(Long id, String clientIp) {
        SysUser user = mustExist(id);
        String raw = PasswordGenerator.random();
        userMapper.updatePassword(id, PasswordHasher.encode(raw), (byte) 0);
        userCache.evict(id);
        StpUtil.kickout(id);
        auditService.log(loginUser.id(), "RESET_PASSWORD", "USER", String.valueOf(id),
                clientIp, true, null);
        return new AdminVo.ResetPasswordVo(id, user.getUsername(), raw);
    }

    /** 批量重置：只返回统一的初始密码（若有），逐个用户单独生成会难以在课上分发 */
    @Transactional(rollbackFor = Exception.class)
    public int resetPasswordBatch(List<Long> ids, String clientIp) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long id : ids) {
            SysUser user = userMapper.selectById(id);
            if (user == null) {
                continue;
            }
            String raw = PasswordGenerator.random();
            userMapper.updatePassword(id, PasswordHasher.encode(raw), (byte) 0);
            userCache.evict(id);
            StpUtil.kickout(id);
            count++;
        }
        auditService.log(loginUser.id(), "RESET_PASSWORD_BATCH", "USER", null,
                clientIp, true, "count=" + count);
        return count;
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeRole(Long id, byte role, String clientIp) {
        SysUser user = mustExist(id);
        guardSuperAdmin(user, "修改角色");
        // 管理员只能被超管任命/撤销；不允许直接设为超管
        if (role != SysUser.ROLE_STUDENT && role != SysUser.ROLE_TEACHER && role != SysUser.ROLE_ADMIN) {
            throw new BizException(ErrorCode.BAD_PARAM, "只能设置为学生(0)、教师(2)或管理员(1)");
        }
        userMapper.updateRole(id, role);
        userCache.evict(id);
        StpUtil.kickout(id);
        auditService.log(loginUser.id(), "USER_ROLE_CHANGE", "USER", String.valueOf(id),
                clientIp, true, "role=" + role);
    }

    /**
     * 删除账号：逻辑删除 + 踢下线 + 封禁。
     * <p>不做物理删除，保留审计链；文件保留，由管理员另行走清理流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id, boolean purgeFiles, String clientIp) {
        SysUser user = mustExist(id);
        guardSuperAdmin(user, "删除");

        long fileCount = fileEntryMapper.countByUser(id);
        if (purgeFiles) {
            List<FileEntry> entries = fileEntryMapper.selectList(new LambdaQueryWrapper<FileEntry>()
                    .eq(FileEntry::getUserId, id));
            List<String> objectKeys = entries.stream()
                    .filter(entry -> !entry.isFolderEntry())
                    .map(FileEntry::getObjectKey)
                    .filter(StringUtils::hasText)
                    .toList();
            if (!entries.isEmpty()) {
                fileEntryMapper.deleteByIds(entries.stream().map(FileEntry::getId).toList());
            }
            userMapper.resetUsedStorage(id, 0L);
            // 索引删掉之后 OSS 对象必须一起删，否则 Bucket 会越积越脏。
            // 放在事务提交后执行：万一回滚，也不会出现"对象删了、索引还在"的坏数据。
            AfterCommit.run(() -> ossSignService.deleteBatch(objectKeys));
        }

        // 被删账号的头像已经没有消费方，一并清理并置空，避免变成无引用的垃圾对象
        String avatarKey = user.getAvatarKey();
        if (StringUtils.hasText(avatarKey)) {
            userMapper.updateAvatar(id, null);
            AfterCommit.run(() -> ossSignService.delete(avatarKey));
        }

        userMapper.deleteById(id);      // @TableLogic -> UPDATE deleted = 1
        userCache.evict(id);
        StpUtil.kickout(id);
        StpUtil.disable(id, -1);
        auditService.log(loginUser.id(), "USER_DELETE", "USER", String.valueOf(id),
                clientIp, true, "purgeFiles=%s,fileCount=%d".formatted(purgeFiles, fileCount));
    }

    public long recalcStorage(Long id, String clientIp) {
        SysUser user = mustExist(id);
        long diff = quotaService.recalculate(user.getId());
        auditService.log(loginUser.id(), "STORAGE_RECALC", "USER", String.valueOf(id),
                clientIp, true, "diff=" + diff);
        return diff;
    }

    /**
     * 后台代改用户资料（<b>部分更新</b>）。
     * <p>
     * 允许的键：{@code realName} / {@code studentNo} / {@code className} / {@code email} / {@code nickname}。
     * <ul>
     *   <li><b>不传的键不改动</b>（不是全量覆盖），避免"只想改班级结果把邮箱清空"；</li>
     *   <li>传空串表示<b>清空</b>该字段；</li>
     *   <li>登录名 / 角色 / 状态 / 配额 / 密码 / 头像各有专用接口，这里会明确拒绝并告知该用哪个。</li>
     * </ul>
     * 之所以收 {@code Map} 而不是 record：只有 {@code Map} 才能区分
     * "键不存在"（不改）与"键存在但值为 null"（清空）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long id, Map<String, Object> body, String clientIp) {
        SysUser user = mustExist(id);
        // 注意：这里刻意**不**调用 guardSuperAdmin ——
        // 改姓名/班级/邮箱/昵称既不会锁死账号也不会提权，
        // 而超管本人没法通过 /user/profile 改这些字段（那个接口只管个性属性），
        // 拦住它反而会让超管连自己的真实姓名都改不了。

        if (body == null || body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "请求体为空。允许的字段：" + EDITABLE_FIELDS);
        }
        for (String key : body.keySet()) {
            String hint = REDIRECTED_FIELDS.get(key);
            if (hint != null) {
                throw new BizException(ErrorCode.BAD_PARAM,
                        "字段 " + key + " 不能通过本接口修改：" + hint);
            }
            if (!EDITABLE_FIELDS.contains(key)) {
                throw new BizException(ErrorCode.BAD_PARAM,
                        "不支持的字段：" + key + "。允许的字段：" + EDITABLE_FIELDS);
            }
        }

        // 唯一性校验：只在"值真的变了"时查库，避免把自己的旧值当成冲突
        if (body.containsKey("studentNo")) {
            String studentNo = AccountRules.normalizeStudentNo(stringValue(body.get("studentNo")));
            if (studentNo != null && !studentNo.equals(user.getStudentNo())) {
                SysUser other = userMapper.selectByStudentNo(studentNo);
                if (other != null && !other.getId().equals(id)) {
                    throw new BizException(ErrorCode.STUDENT_NO_EXISTS,
                            "学号 " + studentNo + " 已被账号 " + other.getUsername() + " 使用");
                }
            }
            user.setStudentNo(studentNo);
        }
        if (body.containsKey("email")) {
            String email = AccountRules.normalizeEmail(stringValue(body.get("email")));
            if (email != null && !email.equals(user.getEmail())) {
                SysUser other = userMapper.selectByEmail(email);
                if (other != null && !other.getId().equals(id)) {
                    throw new BizException(ErrorCode.EMAIL_REGISTERED,
                            "邮箱已被账号 " + other.getUsername() + " 使用");
                }
            }
            user.setEmail(email);
        }
        if (body.containsKey("realName")) {
            user.setRealName(AccountRules.normalizeRealName(stringValue(body.get("realName"))));
        }
        if (body.containsKey("className")) {
            user.setClassName(AccountRules.normalizeClassName(stringValue(body.get("className"))));
        }
        if (body.containsKey("nickname")) {
            // 复用个性属性的昵称规则：为空报错，与 /user/profile 的语义保持一致
            user.setNickname(ProfileRules.normalizeNickname(stringValue(body.get("nickname"))));
        }

        try {
            userMapper.updateAdminProfile(id, user.getRealName(), user.getStudentNo(),
                    user.getClassName(), user.getEmail(), user.getNickname());
        } catch (DuplicateKeyException e) {
            // 上面的预检查能拦住 99% 的情况，但 uk_username / uk_student_no / uk_email
            // 这三个唯一索引**不排除逻辑删除的行**：一个已注销账号仍占着它的邮箱/学号，
            // 而 selectByEmail/selectByStudentNo 都带 deleted = 0，查不到它。
            // 并发下也可能两边同时通过预检查。这里兜底把数据库异常翻译成人话。
            throw new BizException(ErrorCode.BAD_PARAM,
                    "邮箱或学号已被占用（可能属于一个已注销的账号）");
        }

        // CachedUser 目前只含 status/role/pwdChanged，并不包含这几个资料字段；
        // 仍然 evict 是廉价保险：以后给 CachedUser 加字段时不会漏掉失效。
        userCache.evict(id);
        auditService.log(loginUser.id(), "USER_PROFILE_UPDATE", "USER", String.valueOf(id),
                clientIp, true, "fields=" + String.join(",", body.keySet()));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------- 内部

    private SysUser mustExist(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private void guardSuperAdmin(SysUser user, String action) {
        if (user.isSuperAdmin()) {
            throw new BizException(ErrorCode.ADMIN_PROTECTED, "超级管理员账号受保护，禁止" + action);
        }
    }
}
