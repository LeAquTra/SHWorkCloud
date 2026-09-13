package com.leaqutra.shworkcloud.service;

import com.aliyun.oss.OSS;
import com.leaqutra.shworkcloud.config.OssClientConfig;
import com.leaqutra.shworkcloud.config.OssProperties;
import com.leaqutra.shworkcloud.config.StsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名 URL 的形状守卫。
 *
 * <p><b>背景</b>：文件预览在界面上表现为「一张裂图 + 文件名（alt 文本）」。
 * 排查中写了个探针打印 SDK 实际生成的 URL，确认了两件事，这里固化成断言：
 *
 * <ol>
 *   <li><b>预览/缩略图用的签名不能带 {@code response-*} 覆盖</b> ——
 *       全站能正常显示图片的路径（头像、验证码、相册、列表缩略图）用的都是
 *       不带覆盖的普通签名；带覆盖的那条是唯一的差异。</li>
 *   <li><b>下载文件名不能双重编码</b> —— 调用方先 {@code URLEncoder.encode} 一次、
 *       SDK 再编码一次，OSS 返回的 filename 就成了 {@code %25E7%2585%25A7...}
 *       这种不可读的百分号串（而且空格会变成 {@code +}）。
 *       正确做法是把原始值（RFC 5987 形式）交给 SDK 编一次。</li>
 * </ol>
 *
 * <p>签名是<b>纯本地计算</b>，所以用假 AK/SK 就能生成真实格式的 URL。
 */
class OssSignServiceTest {

    private static final String OBJECT_KEY = "homework/2/202609/abc.jpg";
    private static final String DISPLAY_NAME = "照片 1.jpg";

    private static OssProperties props() {
        OssProperties properties = new OssProperties();
        properties.setEndpoint("oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucketName("probe-bucket");
        return properties;
    }

    private static OSS client() {
        StsProperties sts = new StsProperties();
        sts.setAccessKeyId("fake-access-key-id");
        sts.setAccessKeySecret("fake-access-key-secret");
        return new OssClientConfig().ossClient(props(), sts);
    }

    /** 取出 query 里某个参数的值（已 URL 解码） */
    private static String queryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    @DisplayName("预览 / 缩略图签名：必须是不带 response-* 覆盖的普通签名")
    void previewUrlHasNoResponseOverrides() {
        OSS oss = client();
        try {
            String url = new OssSignService(oss, props()).presignedObjectUrl(OBJECT_KEY, 3600);

            assertTrue(url.startsWith("https://"), url);
            assertTrue(url.contains("OSSAccessKeyId="), url);
            assertTrue(url.contains("Signature="), url);
            // 关键：预览走的签名里不应出现任何覆盖参数
            assertFalse(url.contains("response-content-type"), url);
            assertFalse(url.contains("response-content-disposition"), url);
        } finally {
            oss.shutdown();
        }
    }

    @Test
    @DisplayName("下载签名：filename 只编码一次，中文名可读（不是 %25E7%2585%25A7 这种）")
    void downloadUrlEncodesFileNameExactlyOnce() {
        OSS oss = client();
        try {
            String url = new OssSignService(oss, props())
                    .presignedDownloadUrl(OBJECT_KEY, DISPLAY_NAME, 600);

            String disposition = queryParam(url, "response-content-disposition");
            assertTrue(disposition != null && disposition.startsWith("attachment"), disposition);
            // 必须是 RFC 5987：filename*=UTF-8''<pct>
            assertTrue(disposition.contains("filename*=UTF-8''"), disposition);
            // 中文按 UTF-8 百分号编码出现一次；双重编码会变成 %25E7...
            assertTrue(disposition.contains("%E7%85%A7%E7%89%87"), disposition);
            assertFalse(disposition.contains("%25E7"), "filename 被双重编码了：" + disposition);
            // 空格必须是 %20，不能是表单编码的 +
            assertTrue(disposition.contains("%201.jpg"), disposition);
        } finally {
            oss.shutdown();
        }
    }

    @Test
    @DisplayName("下载签名：ASCII 回退名仍然存在且保留扩展名（老客户端不至于拿到乱码文件名）")
    void downloadUrlKeepsAsciiFallback() {
        OSS oss = client();
        try {
            String url = new OssSignService(oss, props())
                    .presignedDownloadUrl(OBJECT_KEY, DISPLAY_NAME, 600);
            String disposition = queryParam(url, "response-content-disposition");
            // 两段并存：ASCII 的 filename= 给老客户端，filename*= 给现代浏览器
            assertTrue(disposition.contains("filename=\""), disposition);
            assertTrue(disposition.contains(".jpg\""), "ASCII 回退名必须保留扩展名：" + disposition);
            assertTrue(disposition.indexOf("filename=") < disposition.indexOf("filename*="),
                    "ASCII 回退名应排在 filename* 之前：" + disposition);
        } finally {
            oss.shutdown();
        }
    }

    @Test
    @DisplayName("签名 URL 一定是 https（http 会被 https 页面按混合内容拦掉）")
    void everySignedUrlIsHttps() {
        OSS oss = client();
        try {
            OssSignService service = new OssSignService(oss, props());
            assertEquals(true, service.presignedObjectUrl(OBJECT_KEY, 600).startsWith("https://"));
            assertEquals(true, service.presignedDownloadUrl(OBJECT_KEY, "a.txt", 600)
                    .startsWith("https://"));
            assertEquals(true, service.presignedPutUrl(OBJECT_KEY, "image/jpeg", 600)
                    .startsWith("https://"));
        } finally {
            oss.shutdown();
        }
    }
}
