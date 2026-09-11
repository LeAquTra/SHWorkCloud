package com.leaqutra.shworkcloud.security;

import com.leaqutra.shworkcloud.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordValidatorTest {

    @Test
    @DisplayName("合规密码通过")
    void acceptsValid() {
        assertDoesNotThrow(() -> PasswordValidator.validate("abc12345", "20260001"));
        assertDoesNotThrow(() -> PasswordValidator.validate("Sh@2026abc", null));
    }

    @Test
    @DisplayName("长度不足或过长被拒")
    void rejectsLength() {
        assertThrows(BizException.class, () -> PasswordValidator.validate("a1b2c3", null));
        assertThrows(BizException.class, () -> PasswordValidator.validate("a".repeat(40), null));
        assertThrows(BizException.class, () -> PasswordValidator.validate(null, null));
        assertThrows(BizException.class, () -> PasswordValidator.validate("   ", null));
    }

    @Test
    @DisplayName("必须同时含字母与数字")
    void rejectsWeakComposition() {
        assertThrows(BizException.class, () -> PasswordValidator.validate("abcdefgh", null));
        assertThrows(BizException.class, () -> PasswordValidator.validate("12345678", null));
    }

    @Test
    @DisplayName("不允许包含空格")
    void rejectsWhitespace() {
        assertThrows(BizException.class, () -> PasswordValidator.validate("abc 1234", null));
    }

    @Test
    @DisplayName("不允许与学号相同")
    void rejectsSameAsUsername() {
        assertThrows(BizException.class, () -> PasswordValidator.validate("20260001", "20260001"));
        assertThrows(BizException.class, () -> PasswordValidator.validate("20260001", "20260001"));
    }

    @Test
    @DisplayName("生成的随机密码必须能通过强度校验")
    void generatedPasswordPassesValidation() {
        for (int i = 0; i < 200; i++) {
            String generated = PasswordGenerator.random();
            assertDoesNotThrow(() -> PasswordValidator.validate(generated, null),
                    "生成的密码未通过校验: " + generated);
        }
    }

    @Test
    @DisplayName("生成的密码长度符合预期且含字母与数字")
    void generatedPasswordShape() {
        String pwd = PasswordGenerator.random();
        assertTrue(pwd.length() >= PasswordValidator.MIN_LENGTH);
        assertTrue(pwd.chars().anyMatch(Character::isLetter));
        assertTrue(pwd.chars().anyMatch(Character::isDigit));
        // 排除易混淆字符，便于老师在课上口述
        assertFalse(pwd.contains("0"));
        assertFalse(pwd.contains("O"));
        assertFalse(pwd.contains("1"));
        assertFalse(pwd.contains("l"));
        assertFalse(pwd.contains("I"));
    }

    @Test
    @DisplayName("BCrypt 哈希可校验，且同一明文两次哈希不同（加盐）")
    void bcryptRoundTrip() {
        String raw = "Sh@2026abc";
        String hash1 = PasswordHasher.encode(raw);
        String hash2 = PasswordHasher.encode(raw);
        assertFalse(hash1.equals(hash2), "BCrypt 每次应生成不同盐值");
        assertTrue(PasswordHasher.matches(raw, hash1));
        assertTrue(PasswordHasher.matches(raw, hash2));
        assertFalse(PasswordHasher.matches("wrong", hash1));
    }

    @Test
    @DisplayName("脏哈希数据不抛异常，只返回 false")
    void bcryptHandlesBadHash() {
        assertFalse(PasswordHasher.matches("x", null));
        assertFalse(PasswordHasher.matches("x", ""));
        assertFalse(PasswordHasher.matches("x", "not-a-bcrypt-hash"));
        assertFalse(PasswordHasher.matches(null, "$2a$10$abcdefghijklmnopqrstuv"));
    }

    @Test
    @DisplayName("哈希格式为 $2a$ 前缀（与 Spring Security BCryptPasswordEncoder 兼容）")
    void bcryptPrefixCompatible() {
        assertTrue(PasswordHasher.encode("abc12345").startsWith("$2a$10$"));
    }
}
