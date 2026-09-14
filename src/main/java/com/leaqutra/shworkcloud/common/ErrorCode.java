package com.leaqutra.shworkcloud.common;

import lombok.Getter;

/**
 * 业务错误码。
 * <p>
 * 对应文档：docs/SHWordCloud_Standard_v2.0.md §14.1（错误码表）。
 * 约定：HTTP 状态码策略为「业务码一律 200，仅 UNAUTHORIZED -> 401、FORBIDDEN -> 403」，
 * 由 {@link GlobalExceptionHandler} 落实。
 */
@Getter
public enum ErrorCode {

    // ---------- 通用 ----------
    SUCCESS(0, "ok"),
    BAD_PARAM(40000, "参数错误"),

    // ---------- 容量与目录 ----------
    QUOTA_EXCEEDED(40010, "存储空间不足"),
    NAME_CONFLICT(40020, "同级目录下存在同名文件"),
    PARENT_NOT_FOUND(40030, "父目录不存在或无权访问"),
    CANNOT_MOVE_INTO_SELF(40040, "不能移动到自身或子目录内"),

    // ---------- 上传 ----------
    OSS_OBJECT_NOT_FOUND(40050, "OSS 对象不存在（上传未完成或已被清理）"),
    ILLEGAL_OBJECT_KEY(40060, "ObjectKey 非法（不属于当前用户）"),
    UPLOAD_TOKEN_INVALID(40061, "上传凭证无效或已过期"),
    UPLOAD_TOKEN_USED(40062, "上传凭证已被使用"),

    // ---------- 文件 ----------
    FILE_NOT_FOUND(40070, "文件不存在或已被删除"),
    NOT_A_FILE(40071, "目标不是文件"),
    NOT_A_FOLDER(40072, "目标不是文件夹"),
    PREVIEW_NOT_SUPPORTED(40073, "该类型不支持在线预览"),
    DEPTH_EXCEEDED(40080, "目录层级超过上限（20 层）"),
    ILLEGAL_FILE_NAME(40081, "文件名为空或包含非法字符"),
    FILE_TOO_LARGE(40082, "文件超出单文件大小上限"),

    // ---------- 导入 ----------
    IMPORT_FORMAT_ERROR(40090, "导入文件格式错误（非 CSV 或编码无法识别）"),
    IMPORT_VALIDATE_FAILED(40091, "导入数据校验失败"),
    IMPORT_LIMIT_EXCEEDED(40092, "导入行数或文件大小超限"),

    // ---------- 公告 ----------
    ANNOUNCEMENT_NOT_FOUND(40094, "公告不存在"),
    ANNOUNCEMENT_STATE_INVALID(40095, "公告当前状态不允许该操作"),

    // ---------- 认证与会话 ----------
    UNAUTHORIZED(40100, "未登录或会话已失效"),
    CAPTCHA_EXPIRED(40101, "验证码已过期或不存在，请重新获取"),
    CAPTCHA_WRONG(40102, "图片验证码答案错误"),
    CAPTCHA_PASS_INVALID(40103, "图片验证凭证无效或已被使用"),
    CAPTCHA_EMPTY(40104, "验证码题库为空或未启用"),
    EMAIL_NOT_QQ(40110, "仅支持 QQ 邮箱注册"),
    EMAIL_CODE_WRONG(40111, "邮箱验证码错误"),
    EMAIL_CODE_EXPIRED(40112, "邮箱验证码已过期，请重新获取"),
    SEND_TOO_FREQUENT(40113, "发送过于频繁，请稍后再试"),
    SEND_LIMIT_EXCEEDED(40114, "发送次数超限"),
    EMAIL_REGISTERED(40115, "邮箱已被注册"),
    BAD_CREDENTIALS(40116, "账号或密码错误"),
    ACCOUNT_DISABLED(40117, "账号已被禁用"),
    ACCOUNT_LOCKED(40118, "账号已锁定，请稍后重试"),
    MUST_CHANGE_PASSWORD(40119, "需先修改初始密码"),
    ADMIN_PROTECTED(40120, "超级管理员账号受保护，禁止该操作"),
    STUDENT_NO_EXISTS(40121, "学号已存在"),
    REGISTER_DISABLED(40122, "自助注册未开放"),
    AVATAR_CHANGE_TOO_FREQUENT(40123, "头像修改过于频繁"),

    // ---------- 权限 ----------
    FORBIDDEN(40300, "无权限访问该资源"),

    // ---------- 服务端 ----------
    SERVER_ERROR(50000, "服务器内部错误"),
    OSS_ERROR(50010, "OSS 服务异常"),
    MAIL_SEND_FAIL(50020, "邮件发送失败"),
    STS_ERROR(50030, "STS 凭证签发失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
