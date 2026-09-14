package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.entity.EmailSendLog;
import com.leaqutra.shworkcloud.mapper.EmailSendLogMapper;
import com.leaqutra.shworkcloud.security.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 邮箱验证码（自助注册）。
 * <p>
 * 流程：{@code 发送验证码 -> 校验并消费 -> 注册}。发送之前是否要求<b>人机验证</b>由
 * {@code app.captcha.require-on-register} 决定（总开关 {@code app.captcha.enabled}）：
 * <ul>
 *   <li><b>要求时</b>：必须先过图片验证码，并在发送邮件码时携带一次性
 *       {@code captchaPassToken}，适合公网开放注册、需要更强防机刷的场景；</li>
 *   <li><b>题库为空时自动不要求</b>：图片验证码依赖后台题库，题库空时若强制要求
 *       会把所有注册挡死（见 {@link CaptchaRules}）。</li>
 * </ul>
 * 限流按「IP + 账号」组合维度，避免机房全班共用一个出口 IP 时被整体限流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private static final String CODE_KEY = "reg:code:";
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 通过校验后允许提交注册的时效（与 Redis 中的 cap:pass:used 一致） */
    private static final long VERIFIED_TTL_SECONDS = 300;

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redis;
    private final AppProperties appProperties;
    private final EmailSendLogMapper emailSendLogMapper;
    private final CaptchaService captchaService;
    private final RateLimiter rateLimiter;

    /** 发件邮箱（QQ 邮箱地址） */
    @Value("${spring.mail.username:}")
    private String mailUsername;

    /** QQ 邮箱 SMTP 授权码（不是登录密码） */
    @Value("${spring.mail.password:}")
    private String mailAuthCode;

    /** 发送注册验证码 */
    public void sendRegisterCode(String email, String captchaPassToken, String clientIp) {
        AppProperties.Register register = appProperties.getRegister();
        if (!register.isEnabled()) {
            throw new BizException(ErrorCode.REGISTER_DISABLED);
        }
        requireQqEmail(email);

        // 1) 人机验证（题库为空时会自动不要求，见 CaptchaRules）：
        //    通过则一次性消费 passToken，防止绕过验证直接刷邮件
        captchaService.checkRegisterPass(captchaPassToken);

        // 2) 限流（内网来源不做 IP 维度计数）
        rateLimiter.checkEmailSend(email, clientIp);

        // 3) 生成并缓存验证码
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        redis.opsForValue().set(CODE_KEY + email, code, CODE_TTL);
        // 标记"该邮箱已获取验证码"，注册提交时校验这个标记
        captchaService.markEmailVerified(email, VERIFIED_TTL_SECONDS);

        // 4a) 开发模式：只写日志
        if (!register.isMailEnabled()) {
            // 启动期 StartupSafetyValidator 已保证 prod 下不可能走到这里
            log.warn("【开发模式】验证码未真实发送 email={} code={}（app.register.mail-enabled=false）",
                    email, code);
            writeLog(email, clientIp, true, "mail-disabled(dev)");
            return;
        }

        // 4b) 未配置发件账号时给出可操作的错误，而不是笼统的"发送失败"
        if (!StringUtils.hasText(mailUsername) || !StringUtils.hasText(mailAuthCode)) {
            captchaService.clearEmailVerified(email);
            redis.delete(CODE_KEY + email);
            throw new BizException(ErrorCode.MAIL_SEND_FAIL,
                    "服务端未配置发件邮箱（MAIL_USERNAME / MAIL_AUTH_CODE），请联系管理员");
        }

        // 4c) 真正发送
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailUsername);
            message.setTo(email);
            message.setSubject("【作业云盘】注册验证码");
            message.setText("您的注册验证码为：" + code + "，10 分钟内有效，请勿泄露给他人。");
            mailSender.send(message);
            writeLog(email, clientIp, true, null);
        } catch (Exception e) {
            log.error("发送验证码邮件失败 email={}", email, e);
            writeLog(email, clientIp, false, e.getMessage());
            // 发送失败要回滚"已验证"标记，否则用户会卡在一个无法重试的中间态
            captchaService.clearEmailVerified(email);
            redis.delete(CODE_KEY + email);
            throw new BizException(ErrorCode.MAIL_SEND_FAIL, "验证码邮件发送失败，请稍后重试或联系老师");
        }
    }

    /** 注册提交时校验，成功即删除（一次性） */
    public void verifyAndConsume(String email, String inputCode) {
        String key = CODE_KEY + email;
        String saved = redis.opsForValue().get(key);
        if (saved == null) {
            throw new BizException(ErrorCode.EMAIL_CODE_EXPIRED);
        }
        if (!saved.equals(inputCode == null ? null : inputCode.trim())) {
            throw new BizException(ErrorCode.EMAIL_CODE_WRONG);
        }
        redis.delete(key);
    }

    private void requireQqEmail(String email) {
        String pattern = appProperties.getRegister().getEmailPattern();
        if (!StringUtils.hasText(email) || !Pattern.matches(pattern, email.trim())) {
            throw new BizException(ErrorCode.EMAIL_NOT_QQ);
        }
    }

    private void writeLog(String email, String ip, boolean success, String error) {
        try {
            EmailSendLog entity = new EmailSendLog();
            entity.setEmail(email);
            entity.setScene("REGISTER");
            entity.setIp(ip);
            entity.setSuccess((byte) (success ? 1 : 0));
            entity.setErrorMsg(error == null ? null
                    : (error.length() > 500 ? error.substring(0, 500) : error));
            emailSendLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写邮件审计失败 email={}", email);
        }
    }
}
