package com.leaqutra.shworkcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文冒烟测试。
 * <p>
 * 默认<b>不执行</b>：启动完整上下文需要真实的 MySQL、Redis 与 OSS 凭据。
 * 在有环境的机器上这样跑：
 * <pre>
 *   RUN_INTEGRATION_TESTS=true mvn test
 * </pre>
 * 它主要用来验证：依赖坐标是否正确（尤其 MyBatis-Plus 的 boot4 starter）、
 * Sa-Token 与 Spring 上下文能否装配、配置项是否齐全。
 * <p>
 * <b>为什么激活 {@code test} profile</b>：不激活时这个测试要求运维把
 * {@code MAIL_USERNAME} / OSS AK-SK 等一系列环境变量都注入到测试进程里，
 * 否则会以 {@code Could not resolve placeholder 'MAIL_USERNAME'} 失败 ——
 * 于是"能跑集成测试"变成一件要手工准备环境的事，最后就没人跑。
 * 用 {@code application-test.yaml} 提供一套指向本机测试库的替身配置后，
 * {@code RUN_INTEGRATION_TESTS=true mvn test} 开箱即用，
 * 这是"集成测试真的会被执行"的前提（见 {@code FriendApiIntegrationTest} 的注释：
 * 这个项目曾因为长期没有真跑数据库的测试，把一个结果集列数错误直接发到了线上）。
 * <p>需要指向别的库时用 {@code --spring.datasource.url=...} 覆盖即可。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true",
        disabledReason = "需要真实 MySQL/Redis，默认跳过（见 application-test.yaml）")
class ShWorkCloudApplicationTests {

    @Test
    void contextLoads() {
        // 能启动即通过
    }
}
