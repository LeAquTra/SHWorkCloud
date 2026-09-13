package com.leaqutra.shworkcloud.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.comm.Protocol;
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

        String endpoint = normalizeEndpoint(oss.getEndpoint());

        // ⚠️⚠️ 必须显式指定 HTTPS —— 这是踩过的真实故障，别删。
        //
        // aliyun-sdk-oss 的 ClientConfiguration 构造函数里写死了 `protocol = Protocol.HTTP`
        // （本仓库用 javap 反编译确认过），而 generatePresignedUrl 会按这个协议拼 URL。
        // 也就是说：endpoint 写成裸域名（oss-cn-beijing.aliyuncs.com）时，
        // 服务端签发给浏览器的直传地址是 **http://** 开头的。
        //
        // 后果：前端一旦部署在 https 页面（生产环境必然如此），浏览器会以
        // 「混合内容（Mixed Content）」为由**直接拦掉这个 XHR**，请求根本发不出去。
        // 而前端只能看到 XHR 的 onerror（"网络错误，上传中断"），
        // OSS 侧只留下一堆失败请求 —— 从两头看都像是"网络问题"，极难定位。
        //
        // 另外这也会让**服务端自己**调 OSS 的流量走明文 HTTP（下载中转、删除、对账），
        // 所以设成 HTTPS 是安全要求，不只是为了前端。
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setProtocol(Protocol.HTTPS);

        log.info("初始化 OSS 客户端 endpoint={} protocol=HTTPS region={} bucket={}",
                endpoint, oss.getRegion(), oss.getBucketName());
        return new OSSClientBuilder()
                .build(endpoint, sts.getAccessKeyId(), sts.getAccessKeySecret(), config);
    }

    /**
     * 把 endpoint 规范化成 SDK 期望的裸域名。
     * <p>
     * 协议由上面的 {@link ClientBuilderConfiguration} 决定，所以这里要把
     * 用户可能写上的 {@code https://} / {@code http://} 前缀和结尾斜杠去掉，
     * 避免同时出现「带协议的域名 + 协议配置」导致 SDK 拼出畸形地址。
     * 这样 {@code OSS_ENDPOINT} 写成 {@code oss-cn-beijing.aliyuncs.com}
     * 或 {@code https://oss-cn-beijing.aliyuncs.com} 都能正常工作。
     */
    private static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
