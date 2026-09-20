package com.leaqutra.shworkcloud.dto;

/**
 * 社区（发帖 / 审核）的请求体。
 * <p>
 * <b>没有 authorId 字段，而且不能加</b>：作者一律取自 Sa-Token 会话。
 * 一旦让前端传作者，就等于把"冒名发帖"做成了接口 ——
 * 与私聊的 {@code MessageReq} 是同一条纪律。
 */
public final class CommunityDto {

    private CommunityDto() {
    }

    /** 发帖 / 编辑。内容为纯文本；链接由服务端解析，前端不要传任何标记 */
    public record PostReq(String content) {
    }

    /** 审核：通过 / 拒绝。拒绝时建议带理由（作者能看到） */
    public record ReviewReq(Long postId, boolean approve, String rejectReason) {
    }

    /**
     * 时间线 / 我的帖子 的分页查询（游标式）。
     *
     * @param beforeId  取比它更旧的帖子（首屏不传，服务端当作"最新"）
     * @param authorId  只看某个作者的帖子；不传表示全部
     * @param mine      true 表示"我的帖子"（含待审与被拒），此时 authorId 被忽略
     * @param size      一页条数，上界见 {@code PostRules.FEED_MAX_PAGE}
     */
    public record FeedQuery(Long beforeId, Long authorId, Boolean mine, Integer size) {
    }

    /**
     * 后台审核队列查询（页码式）。
     * <p>这里用页码而不是游标：审核是"把待处理的清空"这种工作，
     * 队列相对稳定且需要"共多少条待审"的总数，页码更合适。
     *
     * @param status 0 待审核 / 1 已通过 / 2 已拒绝；不传表示全部
     */
    public record ReviewQuery(Integer status, Long page, Long size) {
    }
}
