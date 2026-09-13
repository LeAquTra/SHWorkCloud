package com.leaqutra.shworkcloud.config;

import com.aliyun.oss.OSS;
import com.leaqutra.shworkcloud.service.OssSignService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端签发的预签名 URL **必须是 https**。
 * <p>
 * <b>背景（真实故障）</b>：部署到 https 域名后，文件上传前端只报
 * "网络错误，上传中断"，而头像上传（走服务端）完全正常。浏览器控制台给出真凶：
 * <pre>
 *   Mixed Content: The page at 'https://www.solohelper.xyz/' was loaded over HTTPS,
 *   but requested an insecure XMLHttpRequest endpoint
 *   'http://sakura-4826.oss-cn-beijing.aliyuncs.com/homework/...'
 * </pre>
 * 原因：aliyun-sdk-oss 的 {@code ClientConfiguration} 构造函数里写死了
 * {@code protocol = Protocol.HTTP}，而 {@code generatePresignedUrl} 按该协议拼 URL。
 * endpoint 写成裸域名时，签发给浏览器的直传地址就是 {@code http://} 开头的，
 * 浏览器以「混合内容」为由直接拦掉 XHR —— 请求根本没发出去，所以
 * OSS 侧看不到任何 PUT，前端也只能拿到 XHR 的 {@code onerror}。
 * <p>
 * 修法见 {@link OssClientConfig#ossClient}：显式 {@code setProtocol(Protocol.HTTPS)}。
 * <p>
 * 说明：{@code generatePresignedUrl} 是**纯本地签名计算，不发起网络请求**，
 * 因此这里用假 AK/SK 即可，测试可完全离线运行。
 */
class OssClientHttpsTest {

    private static OssProperties props(String endpoint) {
        OssProperties properties = new OssProperties();
        properties.setEndpoint(endpoint);
        properties.setRegion("cn-beijing");
        properties.setBucketName("unit-test-bucket");
        return properties;
    }

    private static StsProperties fakeSts() {
        StsProperties sts = new StsProperties();
        sts.setAccessKeyId("fake-access-key-id");
        sts.setAccessKeySecret("fake-access-key-secret");
        return sts;
    }

    private static String presignedPutUrl(OssProperties properties) {
        OSS client = new OssClientConfig().ossClient(properties, fakeSts());
        try {
            return new OssSignService(client, properties)
                    .presignedPutUrl("homework/1001/202609/abcdef", "text/plain", 600);
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("裸域名 endpoint 下，预签名直传地址必须是 https（混合内容的根因）")
    void presignedPutUrlIsHttpsWithBareEndpoint() {
        String url = presignedPutUrl(props("oss-cn-beijing.aliyuncs.com"));
        assertTrue(url.startsWith("https://"),
                "预签名 PUT 地址必须是 https，否则 https 页面会被浏览器按混合内容拦掉。实际：" + url);
    }

    @Test
    @DisplayName("endpoint 写成 https:// 前缀也照样是 https（容错，且不出现双协议）")
    void presignedPutUrlIsHttpsWithSchemeEndpoint() {
        String url = presignedPutUrl(props("https://oss-cn-beijing.aliyuncs.com"));
        assertTrue(url.startsWith("https://"), "实际：" + url);
        // 规范化必须把前缀去掉，否则会拼出 https://https://... 这种畸形地址
        assertEquals(-1, url.indexOf("https://https://"), "endpoint 前缀没被规范化：" + url);
    }

    @Test
    @DisplayName("endpoint 带结尾斜杠也能正常签名")
    void presignedPutUrlToleratesTrailingSlash() {
        String url = presignedPutUrl(props("oss-cn-beijing.aliyuncs.com/"));
        assertTrue(url.startsWith("https://"), "实际：" + url);
        assertTrue(url.contains("unit-test-bucket.oss-cn-beijing.aliyuncs.com/"),
                "桶域名拼装异常：" + url);
    }

    @Test
    @DisplayName("下载与预览用的签名 URL 同样必须是 https（否则 <img>/下载也会被拦）")
    void presignedGetUrlsAreHttps() {
        OssProperties properties = props("oss-cn-beijing.aliyuncs.com");
        OSS client = new OssClientConfig().ossClient(properties, fakeSts());
        try {
            OssSignService service = new OssSignService(client, properties);

            String preview = service.presignedObjectUrl("homework/1001/a.png", 3600);
            assertTrue(preview.startsWith("https://"), "预览地址必须是 https，实际：" + preview);

            String download = service.presignedDownloadUrl("homework/1001/a.png", "作业.png", 600);
            assertTrue(download.startsWith("https://"), "下载地址必须是 https，实际：" + download);
        } finally {
            client.shutdown();
        }
    }
}
