package com.leaqutra.shworkcloud.service;

import cn.dev33.satoken.stp.StpUtil;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.dto.AuthDto;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.PasswordHasher;
import com.leaqutra.shworkcloud.security.PasswordValidator;
import com.leaqutra.shworkcloud.security.RateLimiter;
import com.leaqutra.shworkcloud.security.UserCache;
import com.leaqutra.shworkcloud.vo.AuthVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 认证：学号登录、退出、改密、自助注册（可选）。
 * <p>
 * 登录安全要点：
 * <ul>
 *   <li>不区分「账号不存在」与「密码错误」，避免账号枚举；</li>
 *   <li>连续失败达到阈值锁定一段时间（防定向爆破）；</li>
 *   <li>限制以账号维度为主，IP 维度对内网网段放宽（机房同出口 IP 场景）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final AppProperties appProperties;
    private final RateLimiter rateLimiter;
    private final UserCache userCache;
    private final LoginUser loginUser;
    private final CaptchaService captchaService;
    private final EmailCodeService emailCodeService;
    private final AuditService auditService;

    // ---------------------------------------------------------------- 登录

    public AuthVo.LoginVo login(AuthDto.LoginReq req, String clientIp) {
        String loginName = req.login() == null ? "" : req.login().trim();
        if (!StringUtils.hasText(loginName) || !StringUtils.hasText(req.password())) {
            throw new BizException(ErrorCode.BAD_PARAM, "请填写账号与密码");
        }
        rateLimiter.checkLogin(loginName, clientIp);

        SysUser user = userMapper.selectByLogin(loginName);
        if (user == null) {
            // 与「密码错误」返回同一个错误码，避免暴露账号是否存在
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED,
                    "账号已锁定，请于 " + user.getLockedUntil().toLocalTime().withNano(0) + " 后重试");
        }
        if (!PasswordHasher.matches(req.password(), user.getPassword())) {
            int maxFail = appProperties.getSecurity().getLoginMaxFail();
            userMapper.incrLoginFail(user.getId(), maxFail,
                    LocalDateTime.now().plusMinutes(appProperties.getSecurity().getLoginLockMinutes()));
            auditService.log(user.getId(), user.getUsername(), "LOGIN", "USER",
                    String.valueOf(user.getId()), clientIp, false, "密码错误");
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        userMapper.onLoginSuccess(user.getId(), LocalDateTime.now(), clientIp);
        StpUtil.login(user.getId());
        // 让角色与状态缓存立即反映最新值
        userCache.evict(user.getId());
        auditService.log(user.getId(), user.getUsername(), "LOGIN", "USER",
                String.valueOf(user.getId()), clientIp, true, null);

        return new AuthVo.LoginVo(
                StpUtil.getTokenValue(),
                StpUtil.getTokenName(),
                user.getId(),
                user.getUsername(),
                user.getRealName() != null ? user.getRealName() : user.getNickname(),
                user.getRole() == null ? 0 : user.getRole().intValue(),
                user.getPwdChanged() != null && user.getPwdChanged() == 0);
    }

    public void logout() {
        long userId = loginUser.id();
        StpUtil.logout();
        auditService.log(userId, "LOGOUT", "USER", String.valueOf(userId), null, true);
    }

    // ---------------------------------------------------------------- 改密

    /**
     * 修改密码（首登强制改密也走这里）。
     * <p>改密后会踢掉<b>其它</b>终端的会话，但保留当前会话：
     * 当前操作者刚刚用旧密码证明了身份；若把当前会话也踢掉，
     * 首次登录改密的学生会被立刻弹回登录页，在 45 分钟的课上体验很差。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(AuthDto.ChangePwdReq req) {
        long userId = loginUser.id();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (!PasswordHasher.matches(req.oldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "原密码不正确");
        }
        if (!StringUtils.hasText(req.newPassword())
                || !req.newPassword().equals(req.confirmPassword())) {
            throw new BizException(ErrorCode.BAD_PARAM, "两次输入的新密码不一致");
        }
        if (req.newPassword().equals(req.oldPassword())) {
            throw new BizException(ErrorCode.BAD_PARAM, "新密码不能与原密码相同");
        }
        PasswordValidator.validate(req.newPassword(), user.getUsername());

        userMapper.updatePassword(userId, PasswordHasher.encode(req.newPassword()), (byte) 1);
        userCache.evict(userId);

        // 踢掉其它终端，保留当前会话
        String current = StpUtil.getTokenValue();
        for (String token : StpUtil.getTokenValueListByLoginId(userId)) {
            if (!token.equals(current)) {
                StpUtil.kickoutByTokenValue(token);
            }
        }
        auditService.log(userId, user.getUsername(), "CHANGE_PASSWORD", "USER",
                String.valueOf(userId), null, true, null);
    }

    // ---------------------------------------------------------------- 注册

    /** 自助注册（可选通道）。默认关闭；关闭时返回 REGISTER_DISABLED。 */
    @Transactional(rollbackFor = Exception.class)
    public void register(AuthDto.RegisterReq req, String clientIp) {
        if (!appProperties.getRegister().isEnabled()) {
            throw new BizException(ErrorCode.REGISTER_DISABLED);
        }
        String email = req.email() == null ? "" : req.email().trim().toLowerCase();
        String pattern = appProperties.getRegister().getEmailPattern();
        if (!StringUtils.hasText(email) || !Pattern.matches(pattern, email)) {
            throw new BizException(ErrorCode.EMAIL_NOT_QQ);
        }
        if (userMapper.selectByEmail(email) != null) {
            throw new BizException(ErrorCode.EMAIL_REGISTERED);
        }
        // 必须先走完图片验证（发送邮箱验证码时会写入该标记）
        if (!captchaService.isEmailVerified(email)) {
            throw new BizException(ErrorCode.CAPTCHA_PASS_INVALID, "请先完成图片验证并获取邮箱验证码");
        }
        emailCodeService.verifyAndConsume(email, req.emailCode());

        // 用户自填的登录名必须严格合法：只允许数字与大小写字母。
        // 不合法直接报错，绝不静默改写 —— 否则用户以为注册成功、实际登录名已被换掉。
        String username = resolveUsername(email, AccountRules.optionalUsername(req.username()));
        PasswordValidator.validate(req.password(), username);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(PasswordHasher.encode(req.password()));
        user.setNickname(username);
        user.setRole((byte) SysUser.ROLE_STUDENT);
        user.setStatus((byte) 1);
        user.setDeleted((byte) 0);
        user.setPwdChanged((byte) 1);
        user.setStorageQuota(appProperties.getQuota().getDefaultBytes());
        user.setUsedStorage(0L);
        user.setLoginFailCount(0);
        userMapper.insert(user);

        captchaService.clearEmailVerified(email);
        auditService.log(user.getId(), username, "REGISTER", "USER",
                String.valueOf(user.getId()), clientIp, true, "self-register");
    }

    /**
     * 取登录名：优先用用户填的，留空则用邮箱 @ 前面的部分；冲突时追加序号。
     * <p>
     * {@code preferred} 已经过 {@link AccountRules#optionalUsername} 严格校验
     * （只允许数字与大小写字母，非法直接抛错）。下面的 {@code replaceAll} 是给
     * <b>邮箱派生</b>那条路径兜底的清洗，不是主要校验手段。
     */
    private String resolveUsername(String email, String preferred) {
        String base = StringUtils.hasText(preferred) ? preferred.trim() : email.substring(0, email.indexOf('@'));
        // 与 AccountRules.USERNAME_PATTERN 保持同一个字符集：
        // 这样「无论走哪条路径，落库的登录名都满足 ^[0-9A-Za-z]+$」这个不变量
        base = base.replaceAll("[^A-Za-z0-9]", "");
        if (base.isEmpty()) {
            base = "u" + System.currentTimeMillis() % 100000;
        }
        if (base.length() > AccountRules.USERNAME_MAX) {
            base = base.substring(0, AccountRules.USERNAME_MAX);
        }
        if (userMapper.selectByLogin(base) == null) {
            return base;
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + i;
            if (userMapper.selectByLogin(candidate) == null) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.NAME_CONFLICT);
    }
}
