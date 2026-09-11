package com.leaqutra.shworkcloud.common;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    // ---------------------------------------------------------------- 兜底

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e, HttpServletRequest request) {
        // 记录完整堆栈供排查，但不把内部细节返回给前端
        log.error("未处理异常 uri={} method={}", request.getRequestURI(), request.getMethod(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.SERVER_ERROR));
    }
}
