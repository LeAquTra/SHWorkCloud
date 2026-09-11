package com.leaqutra.shworkcloud.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * OSS 客户端。服务端用它在 commit 时校验对象、生成签名 URL、执行 CopyObject 与批量删除。
 * <p>
 * 该客户端使用 RAM 子用户 AK/SK（{@link StsProperties}），**只在服务端使用**，
 * 任何情况下都不得把这对密钥返回给前端。
 */
@Slf4j
@Configuration
public class OssClientConfig {

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(OssProperties oss, StsProperties sts) {
        if (!StringUtils.hasText(sts.getAccessKeyId()) || !StringUtils.hasText(sts.getAccessKeySecret())) {
            throw new IllegalStateException("""
                    未配置 aliyun.sts.access-key-id / access-key-secret，无法初始化 OSS 客户端。
                    请在 src/main/resources/application-local.yaml 中填入 RAM 子用户的 AK/SK
                    （可从 application-local.yaml.example 复制模板）。
                    注意：真实密钥禁止提交到版本库。""");
        }
        if (!StringUtils.hasText(oss.getBucketName())) {
            throw new IllegalStateException("未配置 aliyun.oss.bucket-name，无法初始化 OSS 客户端。");
        }
        log.info("初始化 OSS 客户端 endpoint={} region={} bucket={}",
                oss.getEndpoint(), oss.getRegion(), oss.getBucketName());
        return new OSSClientBuilder().build(oss.getEndpoint(), sts.getAccessKeyId(), sts.getAccessKeySecret());
    }
}
