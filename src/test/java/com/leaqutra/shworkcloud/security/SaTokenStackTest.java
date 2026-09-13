package com.leaqutra.shworkcloud.security;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.plugin.SaTokenPluginHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.leaqutra.shworkcloud.config.SaTokenJsonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sa-Token 与 JSON 栈的回归守卫。
 * <p>
 * <b>背景（真实踩过的坑）</b>：应用部署到服务器后启动即崩，日志是
 * <pre>
 *   NoClassDefFoundError: com/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator
 *     at cn.dev33.satoken.plugin.SaTokenPluginForJackson.install(SaTokenPluginForJackson.java:34)
 *     at cn.dev33.satoken.plugin.SaTokenPluginHolder.loaderPlugins(SaTokenPluginHolder.java:78)
 *     at cn.dev33.satoken.spring.SaBeanInject.&lt;init&gt;(SaBeanInject.java:83)
 * </pre>
 * 原因：Sa-Token 1.45 会扫描类路径上所有 jar 的
 * {@code META-INF/satoken/cn.dev33.satoken.plugin.SaTokenPlugin} 并<b>立即 install</b>，
 * 而 {@code sa-token-jackson} 注册的插件引用的是 <b>Jackson 2</b> 的类；
 * Spring Boot 4 用 <b>Jackson 3</b>（{@code tools.jackson}），类路径上没有 Jackson 2。
 * 且 Sa-Token 对插件加载异常是 fail-fast（直接抛，不跳过坏插件），
 * 于是整个应用起不来，systemd 无限重启。
 * <p>
 * 修法是排除 {@code sa-token-jackson}（见 pom.xml），并用
 * {@link SaTokenJsonConfig} 注入基于 Jackson 3 的实现。
 * <p>
 * <b>这组用例的价值</b>：一旦有人（或某次依赖升级）把 Jackson 2 或
 * {@code sa-token-jackson} 带回类路径，这里会立刻炸 —— 而不是等部署到服务器才发现。
 */
class SaTokenStackTest {

    @Test
    @DisplayName("Sa-Token SPI 插件加载不抛异常（正是生产上启动崩溃的那条调用链）")
    void pluginLoadingDoesNotThrow() {
        // 这一行等价于生产里的 SaBeanInject.<init> → SaTokenPluginHolder.loaderPlugins()：
        // 只要类路径上存在一个 install() 会失败的插件，这里就会抛 SaTokenPluginException
        assertDoesNotThrow(() -> new SaTokenPluginHolder().loaderPlugins());
    }

    @Test
    @DisplayName("类路径上没有 Jackson 2（Boot 4 用的是 Jackson 3）")
    void noJackson2OnClasspath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"),
                "类路径上出现了 Jackson 2。Boot 4 用 Jackson 3，两套并存会"
                        + "导致 Sa-Token 插件崩溃 + LocalDateTime 序列化格式不一致，请把引入方排除掉");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator"),
                "PolymorphicTypeValidator 就是当初让应用起不来的那个类");
    }

    @Test
    @DisplayName("sa-token-jackson 已被排除（它只认 Jackson 2，且 Sa-Token 不会跳过坏插件）")
    void saTokenJacksonPluginExcluded() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("cn.dev33.satoken.plugin.SaTokenPluginForJackson"),
                "sa-token-jackson 又回到类路径上了：应用启动会抛 NoClassDefFoundError，"
                        + "请在 pom.xml 的 sa-token-spring-boot3-starter 里重新加回 exclusion");
    }

    @Test
    @DisplayName("Sa-Token 核心与 Jackson 3 仍在（确认排除没有误伤）")
    void requiredClassesStillPresent() {
        assertDoesNotThrow(() -> Class.forName("cn.dev33.satoken.stp.StpUtil"));
        assertDoesNotThrow(() -> Class.forName("cn.dev33.satoken.json.SaJsonTemplate"));
        assertDoesNotThrow(() -> Class.forName("tools.jackson.databind.ObjectMapper"));
    }

    @Test
    @DisplayName("登录 → 会话读写 → 登出 全流程可用，且不依赖 Jackson 插件")
    void loginFlowWorksWithoutJacksonPlugin() {
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(1001L);
            assertTrue(StpUtil.isLogin(), "登录后应处于已登录态");
            assertNotNull(StpUtil.getTokenValue(), "应签发 token");

            // 会话里存对象是 Sa-Token 的常规用法（内存 DAO 直接存对象，不做 JSON 序列化）
            StpUtil.getSession().set("nickname", "张三");
            assertEquals("张三", StpUtil.getSession().get("nickname"));

            Long loginId = StpUtil.getLoginIdAsLong();
            assertEquals(1001L, loginId);

            StpUtil.logout();
            assertFalse(StpUtil.isLogin(), "登出后不应再是已登录态");
        });
    }

    @Test
    @DisplayName("注入的 JSON 转换器真的能用（而不是 Sa-Token 那个「未实现」的占位实现）")
    void jackson3JsonTemplateActuallyWorks() {
        SaTokenJsonConfig.Jackson3JsonTemplate template = new SaTokenJsonConfig.Jackson3JsonTemplate();

        String json = template.objectToJson(Map.of("nickname", "张三", "role", 0));
        assertEquals("张三", template.jsonToMap(json).get("nickname"));

        // default 方法 jsonToObject(String) 会委托到 jsonToObject(String, Class)
        assertNotNull(template.jsonToObject("{\"a\":1}"));
    }
}
