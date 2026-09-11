package com.leaqutra.shworkcloud.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 启动期安全校验。
 * <p>
 * 把「不能带进生产环境的配置」在启动时就拦下来，避免出现
 * "某次调试把开关打开、上线时忘了改回来" 这类事故。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSafetyValidator implements InitializingBean {

    private final AppProperties appProperties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));

        if (!appProperties.getRegister().isMailEnabled()) {
            if (prod) {
                throw new IllegalStateException("""
                        生产环境（profile 含 prod）禁止关闭 app.register.mail-enabled：
                        关闭后邮箱验证码只写日志，任何人都能用别人的邮箱完成注册。""");
            }
            log.warn("""
                    ============================================================
                    app.register.mail-enabled = false
                    邮箱验证码不会真正发送，只写在本日志里（WARN 级别）。
                    仅限本地联调使用 —— 生产环境启动会被拒绝。
                    ============================================================""");
        }

        if (prod && !appProperties.getRegister().isRequireImageCaptcha()) {
            log.warn("""
                    自助注册已开启且未要求图片验证码（app.register.require-image-captcha=false）。
                    公网环境建议开启图片验证码，否则容易被脚本批量注册。""");
        }

        if (prod && appProperties.getSecurity().isTrustProxy()) {
            log.info("已启用 X-Forwarded-For 解析（app.security.trust-proxy=true），"
                    + "请确认部署在可信反向代理之后且代理覆盖而非追加该头");
        }
    }
}
