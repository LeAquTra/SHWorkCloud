package com.leaqutra.shworkcloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内网网段匹配测试。
 * <p>这是机房场景最关键的一条：整个机房共用一个公网出口 IP，
 * 只有正确识别内网来源并豁免 IP 维度限流，才不会被"全班一起限流"。
 */
class CidrMatcherTest {

    @Test
    @DisplayName("标准内网网段命中")
    void matchesPrivateRanges() {
        assertTrue(CidrMatcher.matches("192.168.1.100", "192.168.0.0/16"));
        assertTrue(CidrMatcher.matches("10.5.3.7", "10.0.0.0/8"));
        assertTrue(CidrMatcher.matches("172.16.30.1", "172.16.0.0/12"));
        assertTrue(CidrMatcher.matches("127.0.0.1", "127.0.0.1/32"));
        assertTrue(CidrMatcher.matches("::1", "127.0.0.1/32"));
    }

    @Test
    @DisplayName("公网地址不命中内网网段")
    void rejectsPublicAddresses() {
        assertFalse(CidrMatcher.matches("8.8.8.8", "192.168.0.0/16"));
        assertFalse(CidrMatcher.matches("192.169.1.1", "192.168.0.0/16"));
        assertFalse(CidrMatcher.matches("11.0.0.1", "10.0.0.0/8"));
        // 172.32 不在 172.16/12 范围内
        assertFalse(CidrMatcher.matches("172.32.0.1", "172.16.0.0/12"));
    }

    @Test
    @DisplayName("边界：/32 与 /0")
    void boundaries() {
        assertTrue(CidrMatcher.matches("192.168.1.1", "192.168.1.1/32"));
        assertFalse(CidrMatcher.matches("192.168.1.2", "192.168.1.1/32"));
        assertTrue(CidrMatcher.matches("1.2.3.4", "0.0.0.0/0"));
    }

    @Test
    @DisplayName("非法输入不抛异常，一律返回 false")
    void malformedInput() {
        assertFalse(CidrMatcher.matches(null, "10.0.0.0/8"));
        assertFalse(CidrMatcher.matches("10.0.0.1", null));
        assertFalse(CidrMatcher.matches("not-an-ip", "10.0.0.0/8"));
        assertFalse(CidrMatcher.matches("10.0.0.256", "10.0.0.0/8"));
        assertFalse(CidrMatcher.matches("10.0.0", "10.0.0.0/8"));
        assertFalse(CidrMatcher.matches("10.0.0.1", "10.0.0.0/33"));
        assertFalse(CidrMatcher.matches("10.0.0.1", "10.0.0.0/abc"));
    }

    @Test
    @DisplayName("matchesAny 命中任一网段即通过")
    void matchesAnyList() {
        List<String> networks = List.of("10.0.0.0/8", "192.168.0.0/16");
        assertTrue(CidrMatcher.matchesAny("192.168.5.5", networks));
        assertFalse(CidrMatcher.matchesAny("8.8.8.8", networks));
        assertFalse(CidrMatcher.matchesAny("8.8.8.8", List.of()));
        assertFalse(CidrMatcher.matchesAny("8.8.8.8", null));
    }
}
