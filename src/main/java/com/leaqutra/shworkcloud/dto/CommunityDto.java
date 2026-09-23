package com.leaqutra.shworkcloud.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 社区（发帖 / 审核 / 后台管理）的请求体。
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
     * 后台帖子列表查询（页码式）。
     * <p>这里用页码而不是游标：后台是"把待处理的清空"这种工作，
     * 队列相对稳定且需要"共多少条"的总数，页码更合适。
     *
     * <p>本 record 同时服务两个页面：
     * <ul>
     *   <li><b>社区审核</b>（教师及以上）只用 {@code status}；</li>
     *   <li><b>社区管理</b>（管理员及以上）额外用 {@code postId} / {@code ids} /
     *       {@code authorId} / {@code keyword} 定位内容 —— 收到举报时管理员手上
     *       往往只有一个帖子 ID 或一句原文，没有这些条件就只能一页页翻。</li>
     * </ul>
     *
     * @param status   0 待审核 / 1 已通过 / 2 已拒绝；不传表示全部
     * @param postId   精确查某一条帖子（举报/工单里拿到的 ID）
     * @param ids      <b>按 ID 批量取</b>：粘贴一串 ID 后只把这些帖子捞出来，
     *                 用来核对或批量处理一份举报清单；与其它条件同时传时取交集
     * @param authorId 只看某个作者的帖子（"这个学生都发了什么"）
     * @param keyword  正文关键字（模糊匹配，LIKE 通配符在 Mapper 侧转义）
     * @param page     页码，从 1 开始
     * @param size     一页条数，上界见 {@code PostRules.REVIEW_MAX_PAGE}
     */
    public record ReviewQuery(Integer status, Long postId, List<Long> ids, Long authorId,
                              String keyword, Long page, Long size) {
    }

    /** 单条 ID 参数体（单条操作把 ID 放在 body 里，前端可以统一封装） */
    public record PostIdReq(Long postId) {
    }

    /**
     * 批量操作请求（社区管理台）。
     *
     * @param ids    要处理的帖子 ID。服务端会去重、丢弃非法值，并限制条数
     * @param action {@code unpublish}（下架，回到待审核）/ {@code reject}（批量拒绝）/
     *               {@code delete}（彻底删除）
     * @param reason 批量拒绝时的理由（作者可见，可空）
     */
    public record BatchReq(List<Long> ids, String action, String reason) {

        /**
         * 规范化 ID 列表：去重、保序、丢弃 null 与非正数。
         * <p>去重是必要的：表格勾选与手输 ID 很容易把同一条重复带上来，
         * 而"同一条被处理两次"在批量拒绝场景下会写出两条审计记录，
         * 也会让返回的 {@code affected} 数字对不上。
         * <p><b>这里不截断条数</b>：上限由 Service 按
         * {@code PostRules.MAX_BATCH_IDS} 判定并报错 —— 静默截断会让管理员
         * 以为 200 条都处理了，而实际只处理了前 100 条。
         */
        public List<Long> normalizedIds() {
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<Long> unique = new LinkedHashSet<>();
            for (Long id : ids) {
                if (id != null && id > 0) {
                    unique.add(id);
                }
            }
            return new ArrayList<>(unique);
        }
    }
}
