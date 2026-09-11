package com.leaqutra.shworkcloud.vo;

import java.time.LocalDate;

/**
 * 认证与用户信息响应体。
 */
public final class AuthVo {

    private AuthVo() {
    }

    public record LoginVo(String token, String tokenName, Long userId, String username,
                          String realName, Integer role, boolean mustChangePassword) {
    }

    /**
     * 当前用户资料 + 容量 + 机房提醒配置。
     * <p>头像相关三个字段：
     * <ul>
     *   <li>{@code avatarKey}：OSS ObjectKey，客户端一般不用，排查问题用；</li>
     *   <li>{@code avatarUrl}：1 小时有效的 OSS 签名地址，可直接放进 {@code <img src>}
     *       （私有 Bucket 必须签名，且 {@code <img>} 无法携带 Authorization 头）；</li>
     *   <li>{@code avatarVersion}：头像版本串，用于客户端缓存失效（换头像后 URL 里的
     *       {@code ?v=} 会变）。</li>
     * </ul>
     * 若客户端能自行设置请求头，也可以直接用稳定地址 {@code GET /api/user/avatar/{userId}}。
     */
    public record UserProfileVo(Long userId, String username, String studentNo, String realName,
                                String className, String nickname, String email,
                                String avatarKey, String avatarUrl, String avatarVersion,
                                String signature, Integer gender, LocalDate birthday,
                                Integer role, long quota, long used, long free, long recycleUsed,
                                int idleLogoutMinutes, int checkoutWarnMinutes) {
    }

    public record QuotaVo(long quota, long used, long free, long recycleUsed) {
    }

    /** 注册流程说明：让 API 调用方知道当前部署到底要求哪几步校验 */
    public record RegisterConfigVo(boolean registerEnabled, boolean requireImageCaptcha,
                                   boolean mailEnabled, String emailPattern,
                                   String mailHint) {
    }
}
