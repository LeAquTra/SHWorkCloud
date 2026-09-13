package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 账号字段校验规则。
 * <p>
 * 重点守住两件事：
 * <ol>
 *   <li><b>登录名字符集</b>：只允许数字与大小写字母（需求明确要求），
 *       且非法输入必须<b>报错</b>而不是被静默抹掉 —— 静默改写会产出
 *       "用户以为注册成功、实际登录名已变"的幽灵账号；</li>
 *   <li><b>学号格式与名单导入一致</b>：否则会出现"CSV 能导进来、后台改不动"。</li>
 * </ol>
 */
class AccountRulesTest {

    // ------------------------------------------------------------ 登录名

    @Test
    @DisplayName("登录名：纯数字 / 纯字母 / 字母数字混合都合法")
    void acceptsAlphanumeric() {
        assertEquals("20260001", AccountRules.requireValidUsername("20260001"));
        assertEquals("zhangSan", AccountRules.requireValidUsername("zhangSan"));
        assertEquals("AbC123", AccountRules.requireValidUsername("  AbC123  "));
        assertEquals("A", AccountRules.requireValidUsername("A"));
    }

    @Test
    @DisplayName("登录名：下划线/连字符/空格/汉字/全角/符号一律拒绝（期望 40000）")
    void rejectsNonAlphanumeric() {
        for (String bad : new String[]{"zhang_san", "zhang-san", "zhang san", "张三",
                "ｚｈａｎｇ", "a@b", "a.b", "a+b", "a/b", "a#b", "ａｂｃ"}) {
            BizException e = assertThrows(BizException.class,
                    () -> AccountRules.requireValidUsername(bad), "应拒绝：" + bad);
            assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode(), "应拒绝：" + bad);
        }
    }

    @Test
    @DisplayName("登录名：空值拒绝，超过 20 个字符拒绝")
    void rejectsEmptyAndTooLong() {
        assertThrows(BizException.class, () -> AccountRules.requireValidUsername(null));
        assertThrows(BizException.class, () -> AccountRules.requireValidUsername(""));
        assertThrows(BizException.class, () -> AccountRules.requireValidUsername("   "));

        String twenty = "a".repeat(AccountRules.USERNAME_MAX);
        assertEquals(twenty, AccountRules.requireValidUsername(twenty));

        BizException e = assertThrows(BizException.class,
                () -> AccountRules.requireValidUsername("a".repeat(AccountRules.USERNAME_MAX + 1)));
        assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
    }

    @Test
    @DisplayName("可选登录名：留空返回 null（交给服务端按邮箱生成），非法则报错")
    void optionalUsername() {
        assertNull(AccountRules.optionalUsername(null));
        assertNull(AccountRules.optionalUsername(""));
        assertNull(AccountRules.optionalUsername("   "));
        assertEquals("abc123", AccountRules.optionalUsername(" abc123 "));
        assertThrows(BizException.class, () -> AccountRules.optionalUsername("张 三"));
    }

    @Test
    @DisplayName("登录名不变量：校验通过的返回值一定匹配 ^[0-9A-Za-z]+$")
    void returnedValueAlwaysMatchesPattern() {
        for (String raw : new String[]{" 20260001 ", "zhangSan", "A1"}) {
            String value = AccountRules.requireValidUsername(raw);
            assertEquals(true, value.matches(AccountRules.USERNAME_PATTERN), value);
        }
    }

    // ------------------------------------------------------------ 身份字段

    @Test
    @DisplayName("真实姓名/班级：去空格；空串表示清空（返回 null）")
    void nameAndClass() {
        assertEquals("张三", AccountRules.normalizeRealName("  张三 "));
        assertNull(AccountRules.normalizeRealName(""));
        assertNull(AccountRules.normalizeRealName("   "));
        assertNull(AccountRules.normalizeRealName(null));

        assertEquals("高一(3)班", AccountRules.normalizeClassName(" 高一(3)班 "));
        assertNull(AccountRules.normalizeClassName(""));

        assertThrows(BizException.class,
                () -> AccountRules.normalizeRealName("名".repeat(AccountRules.REAL_NAME_MAX + 1)));
        assertThrows(BizException.class,
                () -> AccountRules.normalizeClassName("班".repeat(AccountRules.CLASS_NAME_MAX + 1)));
    }

    @Test
    @DisplayName("学号：与名单导入同一套格式（数字/字母/下划线/连字符，3~32 位）")
    void studentNo() {
        assertEquals("20260001", AccountRules.normalizeStudentNo(" 20260001 "));
        assertEquals("2026_001", AccountRules.normalizeStudentNo("2026_001"));
        assertEquals("A-01", AccountRules.normalizeStudentNo("A-01"));
        assertNull(AccountRules.normalizeStudentNo(""));
        assertNull(AccountRules.normalizeStudentNo(null));

        // 太短 / 太长 / 含非法字符
        assertThrows(BizException.class, () -> AccountRules.normalizeStudentNo("ab"));
        assertThrows(BizException.class, () -> AccountRules.normalizeStudentNo("a".repeat(33)));
        assertThrows(BizException.class, () -> AccountRules.normalizeStudentNo("2026.001"));
        assertThrows(BizException.class, () -> AccountRules.normalizeStudentNo("2026 001"));
    }

    @Test
    @DisplayName("学号格式常量与名单导入共用同一个（防止两处漂移）")
    void studentNoPatternIsShared() {
        // 这条断言的意义：如果哪天有人把 AccountRules.STUDENT_NO 改宽/改窄，
        // 这里会提醒他名单导入用的是同一份规则（StudentImportService 直接引用该常量）
        assertEquals(true, AccountRules.STUDENT_NO.matcher("2026_001").matches());
        assertEquals(false, AccountRules.STUDENT_NO.matcher("ab").matches());
    }

    @Test
    @DisplayName("邮箱：格式合法即通过；空串表示清空；不限制必须 QQ（那是注册通道的规则）")
    void email() {
        assertEquals("a@b.com", AccountRules.normalizeEmail(" a@b.com "));
        assertEquals("student@school.edu.cn", AccountRules.normalizeEmail("student@school.edu.cn"));
        assertNull(AccountRules.normalizeEmail(""));
        assertNull(AccountRules.normalizeEmail(null));

        for (String bad : new String[]{"abc", "a@b", "@b.com", "a b@c.com", "a@.com"}) {
            assertThrows(BizException.class, () -> AccountRules.normalizeEmail(bad), "应拒绝：" + bad);
        }
        assertThrows(BizException.class,
                () -> AccountRules.normalizeEmail("a".repeat(AccountRules.EMAIL_MAX) + "@qq.com"));
    }
}
