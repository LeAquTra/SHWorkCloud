package com.leaqutra.shworkcloud.config;

import cn.dev33.satoken.json.SaJsonTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 给 Sa-Token 注入一个基于 <b>Jackson 3</b> 的 JSON 转换器。
 * <p>
 * 为什么需要这个类：
 * <ol>
 *   <li>Sa-Token 的 <code>sa-token-jackson</code> 插件只认 <b>Jackson 2</b>
 *       （{@code com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator}），
 *       而 Spring Boot 4 用的是 <b>Jackson 3</b>（{@code tools.jackson}）。
 *       两套并存时 Sa-Token 的 SPI 插件会在启动期抛
 *       {@code NoClassDefFoundError}，导致<b>应用完全起不来</b>，
 *       所以该插件已在 {@code pom.xml} 里被排除。</li>
 *   <li>排除之后，Sa-Token 会回落到核心包里的 {@code SaJsonTemplateDefaultImpl}，
 *       而它每个方法都抛 {@code NotImplException("未实现具体的 json 转换器")} ——
 *       这是个"请自行注入"的占位实现，谁碰谁炸。</li>
 *   <li>于是这里补上一个真正可用的实现，并且<b>用 Jackson 3</b>，
 *       保证全项目只有一套 JSON 栈（{@code LocalDateTime} 的序列化格式才不会被两套库搞乱）。</li>
 * </ol>
 * <p>
 * 它是怎么被 Sa-Token 拿到的：{@code SaBeanInject#setSaJsonTemplate} 上标了
 * {@code @Autowired(required = false)}，所以只要容器里存在 {@link SaJsonTemplate} 类型的 Bean，
 * Sa-Token 就会自动注入 —— 不需要任何额外配置。
 * <p>
 * 说明：当前登录态走 Sa-Token 默认的<b>内存 DAO</b>（直接存对象，不做 JSON 序列化），
 * 所以这个转换器实际很少被触发；它的价值是"需要时一定可用"，而不是"缺了就崩"。
 */
@Slf4j
@Configuration
public class SaTokenJsonConfig {

    @Bean
    public SaJsonTemplate saJsonTemplate() {
        log.info("已注入 Sa-Token 的 JSON 转换器：Jackson 3（tools.jackson）");
        return new Jackson3JsonTemplate();
    }

    /**
     * 用 Jackson 3 实现 Sa-Token 的 {@link SaJsonTemplate}。
     * <p>
     * 只实现两个抽象方法即可：接口里的 {@code jsonToObject(String)} 与
     * {@code jsonToMap(String)} 都是 default 方法，最终会委托到
     * {@code jsonToObject(String, Class)}，无需重复实现。
     * <p>
     * Jackson 3 抛的是<b>非受检</b>异常 {@code tools.jackson.core.JacksonException}，
     * 因此这里不需要 try/catch 包装；真出现坏 JSON 会直接冒泡成运行时异常。
     */
    public static class Jackson3JsonTemplate implements SaJsonTemplate {

        private final ObjectMapper mapper = JsonMapper.builder().build();

        @Override
        public String objectToJson(Object obj) {
            return mapper.writeValueAsString(obj);
        }

        @Override
        public <T> T jsonToObject(String jsonStr, Class<T> type) {
            return mapper.readValue(jsonStr, type);
        }
    }
}
