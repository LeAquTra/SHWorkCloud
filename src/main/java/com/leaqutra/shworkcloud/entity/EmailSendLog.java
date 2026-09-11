package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件发送审计：用于排查「学生说没收到验证码」这类问题。
 */
@Data
@TableName("email_send_log")
public class EmailSendLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    /** REGISTER / RESET_PWD */
    private String scene;

    private String ip;

    private Byte success;

    private String errorMsg;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;
}
