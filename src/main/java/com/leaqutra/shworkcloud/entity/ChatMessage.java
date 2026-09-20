package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友私聊消息（<b>仅文字</b>）。
 * <p>
 * {@code content} 是纯文本，前端按换行渲染，<b>不存也不渲染 HTML</b> ——
 * 聊天是最容易被注入的位置，一旦允许 HTML 就等于把 XSS 直接送到对方的会话里。
 * <p>
 * {@code id} 除了做主键，还兼作<b>增量拉取游标</b>：前端记住当前最大的 id，
 * 之后只请求 {@code id > lastId} 的消息。这比按时间戳可靠（同一秒内可能有多条，
 * 时间戳会漏消息），也是把"轮询"换成 WebSocket/SSE 时唯一不需要改的接口契约。
 * <p>
 * 本表<b>没有逻辑删除</b>：消息不做单条删除（撤回/删除消息不在本期范围），
 * 账号被删除时其消息按 {@code to_user} 外键级联清掉（见 migration_v2.3.sql 的说明）。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /** 未读 */
    public static final int READ_NO = 0;

    /** 已读 */
    public static final int READ_YES = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发送者用户 ID。**必须取自 Sa-Token 会话，绝不接受前端传入** */
    private Long fromUser;

    /** 接收者用户 ID */
    private Long toUser;

    /** 纯文本正文 */
    private String content;

    /** 0 未读 / 1 已读 */
    private Integer readFlag;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
