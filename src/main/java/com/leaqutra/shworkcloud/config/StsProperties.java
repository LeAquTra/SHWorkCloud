package com.leaqutra.shworkcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAM STS 配置（对应 application.yaml 的 aliyun.sts.*）。
 * <p>
 * 服务端用这对 AK/SK 调用 STS 的 AssumeRole 换取临时凭证下发给浏览器。
 * 这两个值**绝不能**返回给前端，也不能入库、不能入前端包。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.sts")
public class StsProperties {

    private String accessKeyId;
    private String accessKeySecret;

    /** 如 acs:ram::1234567890:role/oss-homework-role */
    private String roleArn;

    /** 临时凭证有效期（秒），建议 900~1800 */
    private long durationSeconds = 1800L;
}
