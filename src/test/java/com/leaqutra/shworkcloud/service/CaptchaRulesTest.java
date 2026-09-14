package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.dto.AuthDto;
import com.leaqutra.shworkcloud.dto.OssDto;
import com.leaqutra.shworkcloud.vo.AuthVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人机验证的开关规则与接口契约。
 * <p>
 * 这组用例守两件事：
 * <ol>
 *   <li><b>题库为空时一律不要求</b> —— 这是最重要的护栏。登录是机房的主路径，
 *       如果"配置要求"就等于"真的要求"，那么题库被删光（或全新部署还没上传题目）时，
 *       所有人都会被「请先完成人机验证」挡在门外，且没人能完成验证；</li>
 *   <li>前端拿得到、也传得回验证凭证 —— 字段名一旦改了，前端会静默地不再发送凭证，
 *       表现为"升级后所有人都登录不了"，这种缺陷在编译期是看不出来的。</li>
 * </ol>
 */
class CaptchaRulesTest {

    // ------------------------------------------------------------ 开关真值表

    @Test
    @DisplayName("总开关 + 场景开关 + 题库可用 三者都成立才要求验证")
    void requiresOnlyWhenAllThreeHold() {
        assertTrue(CaptchaRules.required(true, true, true));
    }

    @Test
    @DisplayName("题库为空 → 不要求（即使配置全开）—— 这是不能破的护栏")
    void neverRequiresWhenBankEmpty() {
        assertFalse(CaptchaRules.required(true, true, false),
                "题库为空还要求验证码，等于把所有人挡在门外且无法完成验证");
        // 三个场景共用同一个判定，所以这里覆盖一次就够，不必分开断言
    }

    @Test
    @DisplayName("总开关关闭 → 三个场景都不要求（临时排障用）")
    void masterSwitchOffDisablesAll() {
        assertFalse(CaptchaRules.required(false, true, true));
    }

    @Test
    @DisplayName("场景开关关闭 → 只有该场景不要求")
    void scopeSwitchOffDisablesThatScope() {
        assertFalse(CaptchaRules.required(true, false, true));
        // 关掉登录场景不影响另外两个场景的判定
        assertTrue(CaptchaRules.required(true, true, true));
    }

    @Test
    @DisplayName("场景枚举齐全：登录 / 注册 / 上传，且都带可读标签")
    void scopesAreComplete() {
        CaptchaRules.Scope[] scopes = CaptchaRules.Scope.values();
        assertEquals(3, scopes.length);
        assertEquals(List.of("登录", "注册", "上传"),
                Arrays.stream(scopes).map(CaptchaRules.Scope::label).toList());
    }

    // ------------------------------------------------------------ 接口契约

    @Test
    @DisplayName("登录请求体必须能携带 captchaPassToken（否则前端无从提交验证结果）")
    void loginRequestCarriesPassToken() {
        assertTrue(componentNames(AuthDto.LoginReq.class).contains("captchaPassToken"),
                "LoginReq 缺 captchaPassToken：需要人机验证时前端就没法把凭证传回来");
    }

    @Test
    @DisplayName("申请上传凭证的请求体必须能携带 captchaPassToken")
    void ticketRequestCarriesPassToken() {
        assertTrue(componentNames(OssDto.TicketReq.class).contains("captchaPassToken"),
                "TicketReq 缺 captchaPassToken：上传场景的验证码将无法通过");
    }

    @Test
    @DisplayName("发邮件码的请求体保留 captchaPassToken（注册场景）")
    void emailCodeRequestCarriesPassToken() {
        assertTrue(componentNames(AuthDto.EmailCodeReq.class).contains("captchaPassToken"));
    }

    @Test
    @DisplayName("human-check 响应体下发三个场景的生效开关 + 凭证有效期")
    void humanCheckVoShape() {
        assertEquals(List.of("login", "register", "upload", "passTtlSeconds"),
                componentNames(AuthVo.HumanCheckVo.class),
                "字段名是前端读的契约，改动要同步改前端并更新本断言");
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
