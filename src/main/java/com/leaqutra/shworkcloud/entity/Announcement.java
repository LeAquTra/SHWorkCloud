package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统公告。
 * <p>
 * 两个维度决定"用户怎么被打扰"：
 * <ol>
 *   <li>{@code level} —— <b>注意力分级</b>：
 *       1 普通（横幅，可关）/ 2 重要（横幅+警示色，可关）/
 *       3 紧急（<b>弹窗强制确认</b>，不点掉不能继续）；</li>
 *   <li>{@code status} —— 草稿 / 已发布 / 已撤回。
 *       "撤回"只改状态、不删数据，便于事后追溯谁在什么时候发过什么。</li>
 * </ol>
 * 另有 {@code expireTime}：到点自动不再展示（不用人工去撤）。
 */
@Data
@TableName("announcement")
public class Announcement {

    public static final int LEVEL_NORMAL = 1;
    public static final int LEVEL_IMPORTANT = 2;
    public static final int LEVEL_URGENT = 3;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_RECALLED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** 纯文本正文，前端按换行渲染（不接受 HTML，避免 XSS） */
    private String content;

    /** 注意力分级：1 普通 2 重要 3 紧急 */
    private Integer level;

    /** 0 草稿 1 已发布 2 已撤回 */
    private Integer status;

    private LocalDateTime publishTime;

    /** 为空表示不过期 */
    private LocalDateTime expireTime;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
