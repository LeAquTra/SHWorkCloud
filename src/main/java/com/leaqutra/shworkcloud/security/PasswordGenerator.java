package com.leaqutra.shworkcloud.security;

import java.security.SecureRandom;

/**
 * 密码生成：用于名单导入与管理员重置密码时生成初始密码。
 * <p>
 * 刻意排除易混淆字符（0/O、1/l/I），因为初始密码常常需要老师在课堂上口述。
 */
public final class PasswordGenerator {

    private static final String LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String ALL = LETTERS + DIGITS;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int DEFAULT_LENGTH = 10;

    private PasswordGenerator() {
    }

    public static String random() {
        return random(DEFAULT_LENGTH);
    }

    /** 生成同时包含字母与数字的随机密码，保证能通过 PasswordValidator 的强度校验 */
    public static String random(int length) {
        int len = Math.max(length, 8);
        StringBuilder sb = new StringBuilder(len);
        sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        for (int i = 2; i < len; i++) {
            sb.append(ALL.charAt(RANDOM.nextInt(ALL.length())));
        }
        // 打乱，避免字母/数字总在前面
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
