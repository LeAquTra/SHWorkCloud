package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.vo.FileVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 签名地址接口的响应契约守卫。
 * <p>
 * <b>背景（真实故障）</b>：用户点"下载"，新窗口显示 <b>404 页面不存在</b>。
 * <p>
 * 原因是这两个接口当时返回 {@code R<String>}（data 是<b>裸字符串</b>），
 * 而文档与前端都按对象处理：
 * <pre>
 *   后端接口手册 §6：  # -&gt; { url }  用这个 URL 直接下载
 *   前端对接指南 §4：  const { url } = await get&lt;{ url: string }&gt;(...)
 *   前端代码：         const { url } = await fileApi.downloadUrl(row.id)
 * </pre>
 * 于是解构出来的 {@code url} 是 {@code undefined}，赋给 {@code <a href>} 后被转成
 * 字面量字符串 {@code "undefined"}，再按相对路径解析成 {@code https://站点/undefined} ——
 * 新窗口打开当然 404。最坑的是<b>前端不会抛任何异常</b>，控制台干干净净，
 * 所以只能靠"契约测试"把两边钉死。
 * <p>
 * 这里用反射断言<b>声明的泛型返回类型</b>，而不是去 mock 整个 Service：
 * 项目现有单测刻意不依赖 Spring/数据库，这样做零依赖，而且如果哪天
 * 有人把返回类型改回 {@code R<String>}，这些用例会立刻失败。
 */
class FileUrlContractTest {

    /** 取出控制器方法的 R&lt;T&gt; 里的 T */
    private static Object responseDataTypeOf(String methodName) throws Exception {
        Method method = FileController.class.getMethod(methodName, Long.class);
        ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
        return returnType.getActualTypeArguments()[0];
    }

    @Test
    @DisplayName("download-url 的 data 必须是 { url } 对象，不能是裸字符串（否则新窗口 404）")
    void downloadUrlReturnsWrappedUrl() throws Exception {
        assertEquals(FileVo.UrlVo.class, responseDataTypeOf("downloadUrl"),
                "get /files/{id}/download-url 必须返回 R<FileVo.UrlVo>；"
                        + "返回裸字符串会让前端解构出 undefined，进而打开 /undefined 报 404");
    }

    @Test
    @DisplayName("preview-url 的 data 同样必须是 { url } 对象")
    void previewUrlReturnsWrappedUrl() throws Exception {
        assertEquals(FileVo.UrlVo.class, responseDataTypeOf("previewUrl"),
                "get /files/{id}/preview-url 必须返回 R<FileVo.UrlVo>");
    }

    @Test
    @DisplayName("UrlVo 序列化后的键名就是 url（前端按 .url 取值）")
    void urlVoSerializesToUrlKey() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        assertEquals("{\"url\":\"https://sakura-4826.oss-cn-beijing.aliyuncs.com/a?b=c\"}",
                mapper.writeValueAsString(
                        new FileVo.UrlVo("https://sakura-4826.oss-cn-beijing.aliyuncs.com/a?b=c")));
    }
}
