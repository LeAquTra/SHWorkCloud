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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

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
