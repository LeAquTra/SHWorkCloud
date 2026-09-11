package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志。
 * <p>
 * 注意：{@code detail} 中<b>禁止</b>记录密码、Token、AK/SK 等敏感信息。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 ID；匿名注册等场景为 null */
    private Long userId;

    private String username;

    /** 动作码，如 FILE_UPLOAD / USER_DISABLE / IMPORT_STUDENTS */
    private String action;

    /** FILE / USER / CAPTCHA / SESSION */
    private String targetType;

    private String targetId;

    private String detail;

    private String ip;

    private String userAgent;

    /** 1 成功 0 失败 */
    private Byte success;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;
}
