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
    /**
     * 路径不存在。
     * <p>必须与"服务器内部错误"区分开：接口写错、版本没对齐时，
     * 返回 500 会让人去翻服务端日志找根因，而真相是"这个地址没有接口"。
     */
    NOT_FOUND(40004, "接口不存在"),
    /** 方法与路由不匹配（例如把 GET 的接口当 POST 调）。同样不能被当成服务端故障 */
    METHOD_NOT_ALLOWED(40005, "请求方法不支持"),
    /** Content-Type 不被支持（例如该传 JSON 却传了表单） */
    UNSUPPORTED_MEDIA_TYPE(40006, "请求内容类型不支持"),

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
    /**
     * 需要人机验证但没带（或带了无效的）验证凭证。
     * <p>与 {@link #CAPTCHA_PASS_INVALID} 的区别：这个是"压根没过验证"，前端应<b>弹出验证码窗口</b>；
     * 那个是"凭证无效 / 已被用过"，前端应重新过一遍验证。两者前端处理其实一样，
     * 分开是为了日志与排查时能一眼看出是"没做"还是"做错了"。
     */
    CAPTCHA_REQUIRED(40105, "请先完成人机验证"),
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

    // ---------- 好友与私聊 ----------
    /**
     * 好友已达上限（{@code FriendRules.MAX_FRIENDS} = 50）。
     * <p>发申请与接受申请两处都会校验：只在前者拦，别人同时接受多个申请时仍会超额。
     */
    FRIEND_LIMIT_REACHED(40124, "好友数量已达上限"),
    /** 不能加自己为好友 */
    FRIEND_SELF(40125, "不能添加自己为好友"),
    /** 已经是好友了 */
    FRIEND_ALREADY(40126, "你们已经是好友"),
    /** 已经发过申请、对方还没处理 */
    FRIEND_REQUEST_EXISTED(40127, "已发送过好友申请，请等待对方处理"),
    /** 要处理的那条申请不存在（可能已被对方撤回，或已经被处理过） */
    FRIEND_REQUEST_NOT_FOUND(40128, "好友申请不存在或已被处理"),
    /** 目标用户不存在 / 已被禁用 / 已注销 */
    FRIEND_TARGET_NOT_FOUND(40129, "该用户不存在或已不可用"),
    /**
     * 不是好友就不能聊天。
     * <p>这条是<b>隐私边界</b>而非功能限制：没有它，知道 userId 就能给任意人发消息。
     */
    NOT_FRIEND(40130, "你们还不是好友，无法发送消息"),

    // ---------- 社区 ----------
    /** 帖子不存在，或当前用户看不到它（未通过审核的帖子只有作者与审核者可见） */
    POST_NOT_FOUND(40096, "帖子不存在或已被删除"),
    /**
     * 当前状态不允许该操作。
     * <p>典型场景：待审核的帖子不能再次编辑或删除（它已经在队列里了），
     * 或审核一个已经被别人处理过的帖子。
     */
    POST_STATE_INVALID(40097, "帖子当前状态不允许该操作"),
    /** 没有审核权限（需要管理员及以上：role 1 / 2 / 9） */
    POST_REVIEW_FORBIDDEN(40301, "需要管理员及以上权限才能审核"),

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
