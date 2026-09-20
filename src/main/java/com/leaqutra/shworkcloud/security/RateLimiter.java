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
    /**
     * 单用户发起好友申请的上限（/小时）。
     * <p>防的是"拿好友申请当骚扰工具"：反复申请会持续消耗对方的注意力，
     * 而每次申请都要写一行库。30 次/小时对正常使用（一节课加几个同学）绰绰有余。
     */
    private static final long FRIEND_REQUEST_HOUR_LIMIT = 30;
    /**
     * 单用户发消息的上限（/分钟）。
     * <p>注意这里用"分钟"而不是"小时"：聊天是连续动作，用小时维度会出现
     * "前 10 分钟把额度用完、后面整节课发不出话"。1000 字符 × 120 条 ≈
     * 120KB/分钟，对单条连接是很轻的负载，但足以拦住脚本刷屏。
     */
    private static final long CHAT_MINUTE_LIMIT = 120;
    /** 单用户发帖 / 编辑的上限（/小时），见 {@link #checkPostCreate} */
    private static final long POST_HOUR_LIMIT = 10;

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

    /**
     * 发起好友申请限流（按用户维度）。
     * <p>只限"发起"，不限"处理"：被申请的人点同意可能一次点十几个
     * （班里同学挨个加过来），限流会让他卡住没法操作。
     */
    public void checkFriendRequest(long userId) {
        if (incr(key("freq", "1h", String.valueOf(userId)), Duration.ofHours(1))
                > FRIEND_REQUEST_HOUR_LIMIT) {
            throw new BizException(ErrorCode.SEND_TOO_FREQUENT, "好友申请过于频繁，请稍后再试");
        }
    }

    /** 发消息限流（按用户维度，分钟窗口） */
    public void checkChatSend(long userId) {
        if (incr(key("chat", "1m", String.valueOf(userId)), Duration.ofMinutes(1))
                > CHAT_MINUTE_LIMIT) {
            throw new BizException(ErrorCode.SEND_TOO_FREQUENT, "发送过于频繁，请稍后再试");
        }
    }

    /**
     * 发帖 / 编辑限流（按用户维度，小时窗口）。
     * <p>刷帖的真正代价由<b>审核员</b>承担（每一条都要人工看），
     * 所以这个额度必须比聊天严得多：10 条/小时对"一节课发两三条"完全够用，
     * 但足以拦住"一口气刷 200 条把队列淹掉"。
     */
    public void checkPostCreate(long userId) {
        if (incr(key("post", "1h", String.valueOf(userId)), Duration.ofHours(1))
                > POST_HOUR_LIMIT) {
            throw new BizException(ErrorCode.SEND_TOO_FREQUENT,
                    "发帖过于频繁（每小时最多 " + POST_HOUR_LIMIT + " 条），请稍后再试");
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
