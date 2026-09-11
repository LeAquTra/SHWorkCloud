package com.leaqutra.shworkcloud.security;

import lombok.Data;

/**
 * 用户关键状态的缓存副本（用于每请求的账号状态校验）。
 * <p>
 * 刻意使用可变的 JavaBean 而不是 record：Hutool 的 JSON 反序列化对 record 支持不可靠，
 * 而这个对象需要存进 Redis 再读回来。
 */
@Data
public class CachedUser {

    private Long id;
    private Integer role;
    private Integer status;
    private Integer deleted;
    private Integer pwdChanged;
}
