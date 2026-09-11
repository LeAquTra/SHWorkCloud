package com.leaqutra.shworkcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 基础配置（对应 application.yaml 的 aliyun.oss.*）。
 * <p>
 * 注意：{@code region} 必须与 {@code endpoint} 成对一致（如 cn-beijing /
 * oss-cn-beijing.aliyuncs.com），否则前端直传会因为签名区域不匹配而失败。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    /** 如 oss-cn-beijing.aliyuncs.com */
    private String endpoint;

    /** 如 cn-beijing */
    private String region;

    private String bucketName;

    /** 用户文件前缀，默认 homework */
    private String keyPrefix = "homework";

    /** 规范 URL 前缀；留空则按 https://{bucket}.{endpoint} 自动拼装 */
    private String baseUrl;

    /** 用户文件前缀（含结尾斜杠），由 keyPrefix 派生 */
    public String userPrefix(long userId) {
        return keyPrefix + "/" + userId + "/";
    }

    public String baseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        return "https://" + bucketName + "." + endpoint;
    }
}
