package com.leaqutra.shworkcloud.security;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 限流。
 * <p>
 * <b>机房场景的关键设计（文档 §3.5）：</b>整个机房/学校共用一个公网出口 IP，
 * 若按纯 IP 维度限流，第 N 个学生之后全班都会被拒绝。
 * 因此对命中内网网段的来源只保留「同一账号」维度的最小频控，不做 IP 计数。
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    /** 同一邮箱/账号的发送间隔（秒） */
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    /** 同一邮箱/账号每小时上限 */
    private static final long EMAIL_HOUR_LIMIT = 5;
    /** 同一 (IP + 账号) 每小时上限 */
    private static final long PAIR_HOUR_LIMIT = 3;
    /** 内网来源的图片验证码拉取上限（/小时） */
    private static final long INTERNAL_CAPTCHA_HOUR_LIMIT = 300;
    /** 内网来源的登录上限（/小时） */
    private static final long INTERNAL_LOGIN_HOUR_LIMIT = 100;
    /** 单用户签发上传凭证上限（/小时） */
    private static final long STS_HOUR_LIMIT = 120;

    private final StringRedisTemplate redis;
    private final AppProperties appProperties;

    public boolean isInternal(String ip) {
        return CidrMatcher.matchesAny(ip, appProperties.getSecurity().getInternalNetworks());
    }

    /**
     * 邮箱验证码发送限流（注册辅通道）。
     * 内网：仅 60 秒间隔，不做 IP 与小时维度计数 → 全班共用出口 IP 也不会被误伤。
     * 公网：邮箱维度 + (IP,邮箱) 组合维度双重计数。
     */
    public void checkEmailSend(String email, String ip) {
        String scene = "mail";
        if (Boolean.TRUE.equals(redis.hasKey(key(scene, "60s", email)))) {
            throw new BizException(ErrorCode.SEND_TOO_FREQUENT);
        }
        long byEmail = incr(key(scene, "1h", email), Duration.ofHours(1));

        if (isInternal(ip)) {
            // 内网不统计 IP 维度，但仍限制单邮箱总量，防止个别账号被反复轰炸
            if (byEmail > EMAIL_HOUR_LIMIT * 4L) {
                throw new BizException(ErrorCode.SEND_LIMIT_EXCEEDED);
            }
        } else {
            long byPair = incr(key(scene, "1h", ip + "_" + email), Duration.ofHours(1));
            if (byEmail > EMAIL_HOUR_LIMIT || byPair > PAIR_HOUR_LIMIT) {
                throw new BizException(ErrorCode.SEND_LIMIT_EXCEEDED);
            }
        }
        redis.opsForValue().set(key(scene, "60s", email), "1", SEND_INTERVAL);
    }

    /** 拉取图片验证码限流（匿名接口，防刷签名 URL） */
    public void checkCaptchaFetch(String ip) {
        if (isInternal(ip)) {
            if (incr(key("cap", "1h", ip), Duration.ofHours(1)) > INTERNAL_CAPTCHA_HOUR_LIMIT) {
                throw new BizException(ErrorCode.SEND_LIMIT_EXCEEDED);
            }
            return;
        }
        long limit = appProperties.getSecurity().getPublicIpHourLimit();
        if (incr(key("cap", "1h", ip), Duration.ofHours(1)) > limit) {
            throw new BizException(ErrorCode.SEND_LIMIT_EXCEEDED);
        }
    }

    /**
     * 登录限流：以账号维度为主（防定向爆破），IP 维度仅作粗粒度兜底。
     * 内网来源大幅放宽 IP 维度，避免机房误伤。
     */
    public void checkLogin(String login, String ip) {
        long ipLimit = isInternal(ip)
                ? INTERNAL_LOGIN_HOUR_LIMIT
                : appProperties.getSecurity().getPublicIpHourLimit();
        if (incr(key("login", "1h", ip), Duration.ofHours(1)) > ipLimit) {
            throw new BizException(ErrorCode.SEND_LIMIT_EXCEEDED);
        }
    }

    /** 签发上传凭证限流（按用户维度） */
    public void checkStsIssue(long userId) {
        if (incr(key("sts", "1h", String.valueOf(userId)), Duration.ofHours(1)) > STS_HOUR_LIMIT) {
            throw new BizException(ErrorCode.SEND_LIMIT_EXCEEDED);
        }
    }

    private String key(String scene, String window, String subject) {
        return "rl:" + scene + ":" + window + ":" + (StringUtils.hasText(subject) ? subject : "-");
    }

    private long incr(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        long count = value == null ? 1L : value;
        if (count == 1L) {
            redis.expire(key, ttl);
        }
        return count;
    }
}
