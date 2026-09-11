package com.leaqutra.shworkcloud.security;

import cn.hutool.crypto.digest.BCrypt;

/**
 * 密码哈希（BCrypt，强度 10）。
 * <p>
 * 本环境本地仓库没有 spring-security-crypto，因此使用 Hutool 自带的 BCrypt 实现。
 * Hutool 生成的是标准 {@code $2a$} 格式哈希，与 Spring Security 的
 * {@code BCryptPasswordEncoder} 互相兼容 —— 将来若引入 spring-security-crypto，
 * 已有哈希无需迁移。
 */
public final class PasswordHasher {

    /** BCrypt cost factor */
    private static final int STRENGTH = 10;

    private PasswordHasher() {
    }

    public static String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(STRENGTH));
    }

    /**
     * 校验密码。
     * <p>
     * 注意：哈希串为空或格式非法时 BCrypt.checkpw 会抛异常，这里统一吞掉返回 false，
     * 避免历史脏数据把登录接口打成 500。
     */
    public static boolean matches(String rawPassword, String hashed) {
        if (rawPassword == null || hashed == null || hashed.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hashed);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
