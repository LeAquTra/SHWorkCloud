package com.leaqutra.shworkcloud.common;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 * <p>
 * HTTP 状态码策略（文档 §6.7 / §14.1）：
 * 业务错误一律 HTTP 200 + 业务 code；仅未登录 -> 401、无权限 -> 403，
 * 便于前端拦截器区分「需要重新登录」与「业务失败」。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---------------------------------------------------------------- 鉴权

    /** Sa-Token：未登录 / 会话已失效 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
    }

    /** Sa-Token：无角色 / 无权限 */
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<R<Void>> handleNoRole(Exception e) {
        log.warn("权限拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ErrorCode.FORBIDDEN));
    }

    /** Sa-Token：账号被禁用（StpUtil.disable） */
    @ExceptionHandler(DisableServiceException.class)
    public ResponseEntity<R<Void>> handleDisabled(DisableServiceException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.ACCOUNT_DISABLED));
    }

    // ---------------------------------------------------------------- 业务

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.ok(R.fail(e.getErrorCode().getCode(), e.getMessage()));
    }

    // ---------------------------------------------------------------- 数据库

    /**
     * 数据库访问失败。
     * <p>
     * <b>为什么单独处理（真实教训）</b>：这类错误以前会掉进下面的 {@code Exception} 兜底，
     * 前端只拿到一个笼统的 {@code 50000 服务器内部错误}。这样一来，
     * "部署时忘了跑增量 SQL、表里缺一列"这种一眼能看出的问题，
     * 表现却是"所有相关接口都 500 且毫无线索"，只能登服务器翻日志 —— 排查成本极高。
     * <p>
     * 这里把<b>根因那一句</b>放进 {@code message} 返回给调用方。泄漏面是可控的：
     * 只带异常类名与最内层 message（如 {@code Unknown column 'deleted' in 'field list'}），
     * <b>不带 SQL 全文、不带参数、不带堆栈</b>。
     * 数据库结构不是攻击者拿不到的秘密（能触发这条的人本来就有接口访问权），
     * 而"看不出发生了什么"的代价远大于这点信息。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<R<Void>> handleDataAccess(DataAccessException e) {
        String root = rootCauseMessage(e);
        log.error("数据库访问失败: {}", root, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.SERVER_ERROR, "数据库访问失败：" + root));
    }

    /** 取最内层异常的消息，尽量给出一句能直接定位问题的话 */
    private static String rootCauseMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String type = current.getClass().getSimpleName();
        String message = current.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    // ---------------------------------------------------------------- 参数

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.ok(R.fail(ErrorCode.BAD_PARAM, "缺少参数: " + e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.ok(R.fail(ErrorCode.BAD_PARAM, "参数类型错误: " + e.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.ok(R.fail(ErrorCode.BAD_PARAM, "请求体格式错误"));
    }

    /** 本环境未引入 spring-boot-starter-validation，这里是手写校验抛出的统一出口 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.ok(R.fail(ErrorCode.BAD_PARAM, e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.ok(R.fail(ErrorCode.FILE_TOO_LARGE, "上传文件超出服务端限制"));
    }

    // ------------------------------------------------- 路由与协议（不是服务端故障！）

    /*
     * 这一组必须单独处理，否则会掉进下面的 Exception 兜底 → HTTP 500 + code 50000，
     * 让"接口写错了"看起来像"服务器崩了"。
     *
     * 真实踩过的坑：前端早期把 GET /auth/captcha 误按 POST 调，
     * 本该回 405，结果回了 500「服务器内部错误」：
     *   ① 前端只对 404/405 做降级重试，于是直接失败，用户看到"验证码加载失败"；
     *   ② 服务端日志被一条无意义的堆栈刷屏，排查方向被带偏。
     */

    /** 路径没有匹配的接口（多为前端拼错地址、或后端与前端版本不一致） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("接口不存在 uri={} method={}", e.getResourcePath(), e.getHttpMethod());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(ErrorCode.NOT_FOUND, "接口不存在：" + e.getResourcePath()));
    }

    /** 方法不匹配（GET 的接口被 POST 调等）。message 里带上正确方法，省一次来回 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null ? ""
                : e.getSupportedHttpMethods().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining("/"));
        log.warn("请求方法不被支持 method={} supported={}", e.getMethod(), supported);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(ErrorCode.METHOD_NOT_ALLOWED,
                        supported.isEmpty()
                                ? "请求方法 " + e.getMethod() + " 不被支持"
                                : "该接口只支持 " + supported + "，请勿用 " + e.getMethod() + " 调用"));
    }

    /** 请求体类型不对（该传 JSON 却传了表单等） */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("请求内容类型不被支持 contentType={}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(R.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "请求 Content-Type 不被支持：" + e.getContentType()));
    }

    // ---------------------------------------------------------------- 兜底

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e, HttpServletRequest request) {
        // 记录完整堆栈供排查，但不把内部细节返回给前端
        log.error("未处理异常 uri={} method={}", request.getRequestURI(), request.getMethod(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.SERVER_ERROR));
    }
}
