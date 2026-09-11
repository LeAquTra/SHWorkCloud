package com.leaqutra.shworkcloud.security;

import com.leaqutra.shworkcloud.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 取真实客户端 IP。
 * <p>
 * <b>安全要点：</b>只有确认部署在可信反向代理之后（{@code app.security.trust-proxy=true}）
 * 才读取 {@code X-Forwarded-For}，并且只取第一跳。
 * 若不加限制地信任该请求头，攻击者可以伪造它以绕过全部限流；
 * 同时 Nginx 侧必须用 {@code proxy_set_header X-Forwarded-For $remote_addr;}
 * 覆盖而不是追加客户端传值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpUtil {

    private static final String XFF = "X-Forwarded-For";
    private static final String XRI = "X-Real-IP";

    private final AppProperties appProperties;

    public String get(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        if (appProperties.getSecurity().isTrustProxy()) {
            String xff = request.getHeader(XFF);
            if (StringUtils.hasText(xff)) {
                // 取第一跳（最靠近客户端的那个地址）
                int comma = xff.indexOf(',');
                String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
                if (isValid(first)) {
                    return first;
                }
            }
            String xri = request.getHeader(XRI);
            if (StringUtils.hasText(xri) && isValid(xri.trim())) {
                return xri.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return isValid(remote) ? remote : "unknown";
    }

    private boolean isValid(String ip) {
        return StringUtils.hasText(ip) && ip.length() <= 64;
    }
}
