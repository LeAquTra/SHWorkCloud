package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 个性属性校验。
 * <p>重点是"不传即不改、传空即清空"的语义边界 —— 写错会导致用户资料被静默清掉。
 */
class ProfileRulesTest {

    // ---------------------------------------------------------------- 昵称

    @Test
    @DisplayName("昵称：null 表示不修改")
    void nicknameNullMeansUnchanged() {
        assertNull(ProfileRules.normalizeNickname(null));
    }

    @Test
    @DisplayName("昵称：去掉首尾空格")
    void nicknameTrims() {
        assertEquals("小明", ProfileRules.normalizeNickname("  小明  "));
    }

    @Test
    @DisplayName("昵称：不能为空串，也不能只有空格")
    void nicknameRejectsBlank() {
        assertThrows(BizException.class, () -> ProfileRules.normalizeNickname(""));
        assertThrows(BizException.class, () -> ProfileRules.normalizeNickname("   "));
    }

    @Test
    @DisplayName("昵称：长度按字符数算（50 通过 / 51 拒绝），中文不被当成多字节")
    void nicknameLengthByCodePoint() {
        assertDoesNotThrow(() -> ProfileRules.normalizeNickname("张".repeat(50)));
        assertThrows(BizException.class, () -> ProfileRules.normalizeNickname("张".repeat(51)));
    }

    // ---------------------------------------------------------------- 签名

    @Test
    @DisplayName("签名：null 与空白都表示清空")
    void signatureBlankMeansClear() {
        assertNull(ProfileRules.normalizeSignature(null));
        assertNull(ProfileRules.normalizeSignature(""));
        assertNull(ProfileRules.normalizeSignature("   "));
    }

    @Test
    @DisplayName("签名：正常内容保留，超长拒绝")
    void signatureValidation() {
        assertEquals("今天也要加油", ProfileRules.normalizeSignature("  今天也要加油 "));
        assertDoesNotThrow(() -> ProfileRules.normalizeSignature("字".repeat(255)));
        assertThrows(BizException.class, () -> ProfileRules.normalizeSignature("字".repeat(256)));
    }

    // ---------------------------------------------------------------- 性别

    @Test
    @DisplayName("性别：null 视为未知(0)，即清空")
    void genderNullBecomesUnknown() {
        assertEquals(ProfileRules.GENDER_UNKNOWN, ProfileRules.normalizeGender(null));
    }

    @Test
    @DisplayName("性别：只接受 0/1/2")
    void genderRange() {
        assertEquals(ProfileRules.GENDER_UNKNOWN, ProfileRules.normalizeGender((byte) 0));
        assertEquals(ProfileRules.GENDER_MALE, ProfileRules.normalizeGender((byte) 1));
        assertEquals(ProfileRules.GENDER_FEMALE, ProfileRules.normalizeGender((byte) 2));
        assertThrows(BizException.class, () -> ProfileRules.normalizeGender((byte) 3));
        assertThrows(BizException.class, () -> ProfileRules.normalizeGender((byte) -1));
    }

    // ---------------------------------------------------------------- 生日

    @Test
    @DisplayName("生日：null 表示清空")
    void birthdayNullMeansClear() {
        assertNull(ProfileRules.normalizeBirthday(null));
    }

    @Test
    @DisplayName("生日：不能是未来")
    void birthdayRejectsFuture() {
        assertThrows(BizException.class,
                () -> ProfileRules.normalizeBirthday(LocalDate.now().plusDays(1)));
    }

    @Test
    @DisplayName("生日：今天合法，过早的年份拒绝")
    void birthdayBounds() {
        assertDoesNotThrow(() -> ProfileRules.normalizeBirthday(LocalDate.now()));
        assertDoesNotThrow(() -> ProfileRules.normalizeBirthday(LocalDate.of(2008, 9, 1)));
        assertThrows(BizException.class,
                () -> ProfileRules.normalizeBirthday(LocalDate.of(1899, 12, 31)));
    }
}
