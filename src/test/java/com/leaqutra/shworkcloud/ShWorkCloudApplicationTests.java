package com.leaqutra.shworkcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试。
 * <p>
 * 默认<b>不执行</b>：启动完整上下文需要真实的 MySQL、Redis 与 OSS 凭据。
 * 在有环境的机器上这样跑：
 * <pre>
 *   RUN_INTEGRATION_TESTS=true mvn test -Dtest=ShWorkCloudApplicationTests
 * </pre>
 * 它主要用来验证：依赖坐标是否正确（尤其 MyBatis-Plus 的 boot4 starter）、
 * Sa-Token 与 Spring 上下文能否装配、配置项是否齐全。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true",
        disabledReason = "需要真实 MySQL/Redis/OSS，默认跳过")
class ShWorkCloudApplicationTests {

    @Test
    void contextLoads() {
        // 能启动即通过
    }
}
