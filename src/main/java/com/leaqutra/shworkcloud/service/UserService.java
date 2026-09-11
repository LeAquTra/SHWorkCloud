package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.AuthVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 当前用户自助接口：资料、个性属性与容量。
 * <p>
 * 可自助修改的只有<b>个性属性</b>（昵称/头像/签名/性别/生日）。
 * 学号、姓名、班级属于学籍数据，只能由教师或管理员通过名单导入维护 ——
 * 允许学生自行改学号会让账号体系失去意义。
 * <p>
 * 头像比较特殊：它需要真正上传文件到 OSS，因此走独立的
 * {@code POST /user/avatar}（见 {@link AvatarService}），
 * 不通过本类的字段更新，否则会出现"只改了个 URL、OSS 里什么都没有"的脏数据。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final QuotaService quotaService;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final AvatarService avatarService;
    private final LoginUser loginUser;

    public AuthVo.UserProfileVo profile() {
        long userId = loginUser.id();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return toVo(user);
    }

    /**
     * 修改个性属性（<b>真正的部分更新</b>）。
     * <p>
     * 语义按「请求体里有没有这个 key」区分，而不是按值是否为 null：
     * <ul>
     *   <li>key <b>不存在</b> → 不修改该属性；</li>
     *   <li>key <b>存在</b>且值为 null / 空串 → 清空该属性（昵称除外，昵称不能为空）；</li>
     *   <li>key 存在且有值 → 更新。</li>
     * </ul>
     * 之所以不用「全量替换」，是因为那样客户端只想改昵称却漏传其它字段时，
     * 会把用户资料静默清空 —— 这类数据丢失很难被发现。
     * <p>
     * 头像<b>不在这里改</b>：请用 {@code POST /user/avatar}（multipart 上传）或
     * {@code DELETE /user/avatar}（清除），服务端会把旧头像一起从 OSS 删掉。
     *
     * @param body 允许的键：{@code nickname} / {@code signature} / {@code gender} / {@code birthday}
     * @return 修改后的完整资料（调用方无需再查一次）
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthVo.UserProfileVo updateProfile(Map<String, Object> body) {
        long userId = loginUser.id();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (body == null || body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "请求体为空。允许的字段：nickname / signature / gender / birthday");
        }
        if (body.containsKey("avatar") || body.containsKey("avatarUrl")
                || body.containsKey("avatarKey")) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "头像不能通过本接口修改，请用 POST /api/user/avatar 上传（JPG/PNG，≤5MB）"
                            + "，或 DELETE /api/user/avatar 清除");
        }

        if (body.containsKey("nickname")) {
            user.setNickname(ProfileRules.normalizeNickname(stringValue(body.get("nickname"))));
        }
        if (body.containsKey("signature")) {
            user.setSignature(ProfileRules.normalizeSignature(stringValue(body.get("signature"))));
        }
        if (body.containsKey("gender")) {
            user.setGender(ProfileRules.normalizeGender(byteValue(body.get("gender"))));
        }
        if (body.containsKey("birthday")) {
            user.setBirthday(parseBirthday(stringValue(body.get("birthday"))));
        }

        // 只更新个性属性列，避免用 updateById 把整行（含密码）写回
        userMapper.updateProfile(userId, user.getNickname(),
                user.getSignature(), user.getGender(), user.getBirthday());

        auditService.log(userId, "PROFILE_UPDATE", "USER", String.valueOf(userId), null, true,
                "fields=" + String.join(",", body.keySet()));
        return toVo(userMapper.selectById(userId));
    }

    public AuthVo.QuotaVo quota() {
        return quotaService.query(loginUser.id());
    }

    // ------------------------------------------------------------ 内部

    private AuthVo.UserProfileVo toVo(SysUser user) {
        AuthVo.QuotaVo quota = quotaService.query(user.getId());
        String avatarKey = user.getAvatarKey();
        return new AuthVo.UserProfileVo(
                user.getId(), user.getUsername(), user.getStudentNo(),
                user.getRealName(), user.getClassName(), user.getNickname(), user.getEmail(),
                avatarKey,
                avatarService.signedUrl(avatarKey),
                avatarService.version(avatarKey),
                user.getSignature(),
                user.getGender() == null ? 0 : user.getGender().intValue(),
                user.getBirthday(),
                user.getRole() == null ? 0 : user.getRole().intValue(),
                quota.quota(), quota.used(), quota.free(), quota.recycleUsed(),
                appProperties.getClassroom().getIdleLogoutMinutes(),
                appProperties.getClassroom().getCheckoutWarnMinutes());
    }

    /** null 与空串统一成 null（表示清空） */
    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** JSON 数字进来是 Integer，这里容忍 Integer / Number / 数字字符串 */
    private static Byte byteValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.byteValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Byte.valueOf(text);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_PARAM, "gender 必须是 0 / 1 / 2");
        }
    }

    private LocalDate parseBirthday(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ProfileRules.normalizeBirthday(LocalDate.parse(raw));
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.BAD_PARAM, "生日格式应为 yyyy-MM-dd，例如 2008-09-01");
        }
    }
}
