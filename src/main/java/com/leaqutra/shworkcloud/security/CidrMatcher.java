package com.leaqutra.shworkcloud.security;

import org.springframework.util.StringUtils;

/**
 * IPv4 CIDR 匹配。
 * <p>
 * 用于判断请求是否来自内网网段（机房同出口 IP 场景需要对内网豁免 IP 维度限流）。
 * 自己实现而不是依赖第三方，便于单元测试覆盖。
 */
public final class CidrMatcher {

    private CidrMatcher() {
    }

    /**
     * 判断 IP 是否落在给定 CIDR 内。
     *
     * @param ip   点分十进制 IPv4，或 IPv6 环回 {@code ::1}
     * @param cidr 形如 {@code 192.168.0.0/16}；分隔符缺失时按 /32 处理
     */
    public static boolean matches(String ip, String cidr) {
        if (!StringUtils.hasText(ip) || !StringUtils.hasText(cidr)) {
            return false;
        }
        String value = cidr.trim();

        // IPv6 环回：单独处理，避免被当成非法 IPv4 而漏判
        if ("::1".equals(ip.trim())) {
            return value.startsWith("127.") || value.equals("::1/128");
        }

        long target = toLong(ip.trim());
        if (target < 0) {
            return false;
        }

        int slash = value.indexOf('/');
        String base = slash >= 0 ? value.substring(0, slash) : value;
        int prefix;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            prefix = 32;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }

        long baseLong = toLong(base);
        if (baseLong < 0) {
            return false;
        }

        if (prefix == 0) {
            return true;
        }
        long mask = (-1L << (32 - prefix)) & 0xFFFFFFFFL;
        return (target & mask) == (baseLong & mask);
    }

    /** 是否命中任意一个 CIDR */
    public static boolean matchesAny(String ip, Iterable<String> cidrs) {
        if (cidrs == null) {
            return false;
        }
        for (String cidr : cidrs) {
            if (matches(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    /** 点分十进制 -> 无符号 32 位；非法返回 -1 */
    private static long toLong(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return -1;
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (octet < 0 || octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }
}
