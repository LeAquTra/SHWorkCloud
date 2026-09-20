package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社区帖子（文字动态，**需要审核**）。
 * <p>
 * 状态机刻意与 {@link Announcement} 保持同构（草稿/已发布/已撤回 →
 * 待审/已通过/已拒绝），维护者只需要理解一套规则：
 * <pre>
 *   发帖 ──▶ 待审核 ──approve──▶ 已通过 ──作者编辑──▶ 待审核（重新排队）
 *              │                    │
 *              │                    └──reject──▶ 已拒绝
 *              └──reject──▶ 已拒绝 ──作者编辑──▶ 待审核
 * </pre>
 * 两条不能松的约束：
 * <ol>
 *   <li><b>用户发帖一律落为 {@link #STATUS_PENDING}</b>，绝不允许直接可见 ——
 *       这是需求里"需要管理员以上审核通过"的落地点；</li>
 *   <li><b>已通过的帖子被作者编辑后必须回到待审核</b>。否则作者可以先用正常内容
 *       过审、再把正文换成任何东西，审核形同虚设。</li>
 * </ol>
 * {@code content} 是<b>纯文本</b>：不存 HTML、不存链接标记。链接在读取时由
 * {@code LinkSegmenter} 解析成分段下发，所以"显示成什么"始终由服务端决定。
 */
@Data
@TableName("post")
public class Post {

    /** 待审核（新帖的初始状态） */
    public static final int STATUS_PENDING = 0;

    /** 已通过（唯一对所有人可见的状态） */
    public static final int STATUS_APPROVED = 1;

    /** 已拒绝（仅作者与审核者可见，作者能看到拒绝理由） */
    public static final int STATUS_REJECTED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long authorId;

    /** 纯文本正文，不存 HTML */
    private String content;

    /**
     * 正文里的链接数，发帖时由 {@code LinkSegmenter} 算好。
     * <p>用途只有一个：给审核队列一个可筛选的信号 ——
     * "带 3 个外链的帖子"和"纯文字日常"在审核时不是一回事。
     */
    private Integer linkCount;

    /** 0 待审核 / 1 已通过 / 2 已拒绝 */
    private Integer status;

    private Long reviewedBy;

    private LocalDateTime reviewTime;

    /** 拒绝理由（仅 {@link #STATUS_REJECTED} 时有值，作者可见） */
    private String rejectReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 是否处于待审核（审核队列与"能否编辑"的判断都走它，避免各处比较魔法数字） */
    public boolean isPending() {
        return status != null && status == STATUS_PENDING;
    }

    /** 是否已通过 */
    public boolean isApproved() {
        return status != null && status == STATUS_APPROVED;
    }
}
