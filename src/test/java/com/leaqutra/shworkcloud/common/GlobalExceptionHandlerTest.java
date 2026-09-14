package com.leaqutra.shworkcloud.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由/协议类异常的返回契约。
 *
 * <p>守的是一个**真实踩过的坑**：这些异常如果掉进 {@code Exception} 兜底，
 * 就会被包装成 HTTP 500 + code 50000「服务器内部错误」。后果有两层：
 * <ol>
 *   <li>前端只对 404/405 做降级或提示，拿到 500 就只能显示一句"加载失败" ——
 *       用户看到的就是"验证码加载失败"，而真相只是"这个接口该用 GET"；</li>
 *   <li>服务端日志被无意义的堆栈刷屏，排查方向被带偏成"服务是不是挂了"。</li>
 * </ol>
 * 所以"客户端把请求写错了"必须明确回答 4xx，而不是 5xx。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("方法不匹配 → HTTP 405 + 40005，并告知该用哪个方法")
    void methodNotSupportedIsClientError() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET"));

        ResponseEntity<R<Void>> response = handler.handleMethodNotSupported(e);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(ErrorCode.METHOD_NOT_ALLOWED.getCode(), response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("GET"), response.getBody().getMessage());
        assertNotEquals(ErrorCode.SERVER_ERROR.getCode(), response.getBody().getCode(),
                "405 被当成服务端错误，前端会显示'服务器内部错误'，排查方向也会被带偏");
    }

    @Test
    @DisplayName("路径不存在 → HTTP 404 + 40004（不是 500）")
    void unknownPathIsNotFound() {
        NoResourceFoundException e = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/not-exists", "接口不存在");

        ResponseEntity<R<Void>> response = handler.handleNoResource(e);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("Content-Type 不支持 → HTTP 415 + 40006")
    void unsupportedMediaTypeIsClientError() {
        HttpMediaTypeNotSupportedException e =
                new HttpMediaTypeNotSupportedException("text/plain");

        ResponseEntity<R<Void>> response = handler.handleMediaType(e);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("真正的未知异常仍然 500 —— 别把兜底改没了")
    void realErrorsStillFailLoudly() {
        ResponseEntity<R<Void>> response = handler.handleOther(
                new IllegalStateException("boom"), new org.springframework.mock.web.MockHttpServletRequest());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.SERVER_ERROR.getCode(), response.getBody().getCode());
    }
}
