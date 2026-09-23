package com.leaqutra.shworkcloud.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 社区（帖子）的响应体。
 */
public final class CommunityVo {

    private CommunityVo() {
    }

    /**
     * 正文的一个分段。
     * <p>
     * <b>这是"链接特殊显示"的实现方式：服务端不返回 HTML，而是把正文切成
     * 文字段与链接段。</b> 前端只做一件事 —— 把 {@code type=link} 的段渲染成
     * {@code <a>}，其余按纯文本插值。这样渲染层没有任何解析逻辑，
     * 正文里手写 {@code <a href=...>} 也永远不会被当成标记执行。
     *
     * @param type {@code text} 或 {@code link}
     * @param text 要显示的文本（链接段显示的是<b>用户写的原文</b>，不是改写后的 URL）
     * @param href 仅链接段有值，且一定是 {@code http(s)://} 开头；
     *             前端直接放进 {@code <a href>}，不要再做任何拼接
     */
    public record Segment(String type, String text, String href) {
    }

    /**
     * 一条帖子。
     *
     * @param author    作者名片。<b>与好友/聊天共用同一个收窄过的视图</b>
     *                  （{@link FriendVo.UserCard}），因此不会带出邮箱、生日、性别、容量
     * @param segments  正文分段（见 {@link Segment}）；前端应优先用它渲染
     * @param linkCount 链接数，前端可用来提示"含 N 个外部链接"
     * @param status    0 待审核 / 1 已通过 / 2 已拒绝。
     *                  时间线里恒为 1；"我的帖子"与审核队列里才有其它值
     * @param rejectReason 拒绝理由，仅 {@code status=2} 时有值（作者可见）
     * @param canEdit   当前请求者能否编辑这条。由服务端算好，前端不必自己推状态机
     * @param canDelete 当前请求者能否删除这条
     */
    public record Post(Long id, FriendVo.UserCard author, String content,
                       List<Segment> segments, Integer linkCount, Integer status,
                       Long reviewedBy, LocalDateTime reviewTime, String rejectReason,
                       LocalDateTime createTime, LocalDateTime updateTime,
                       boolean canEdit, boolean canDelete) {
    }

    /**
     * 时间线一页。
     *
     * @param posts    按时间倒序
     * @param nextBeforeId 下一页的游标（把最后一条的 id 传回来即可）；没有更多时为 null
     * @param hasMore  是否还有更早的
     */
    public record Feed(List<Post> posts, Long nextBeforeId, boolean hasMore) {
    }

    /** 我的帖子概览：三个状态各有多少（给"我的"页签显示角标） */
    public record MySummary(long pending, long approved, long rejected) {
    }

    /**
     * 审核队列一页（后台「社区审核」用）。
     * <p>
     * ⚠️ <b>已经在用的响应契约，字段不要动</b>：前端 `PostReviewPageVO`
     * 按 `records / total / page / size / pendingTotal` 解构。
     * 管理台要的统计另开了 {@link AdminPostPage}（多一个 statusCounts），
     * 而不是往这里加字段 —— 加字段虽然向后兼容，但会让两个页面的契约
     * 混成一个，读代码的人分不清哪个字段是给谁用的。
     *
     * @param pendingTotal 全站待审核总数（不受筛选条件影响，导航红点用）
     */
    public record ReviewPage(List<Post> records, long total, long page, long size,
                             long pendingTotal) {
    }

    /**
     * 社区管理台的帖子列表一页：在 {@link ReviewPage} 的基础上多带全局状态分布。
     *
     * @param statusCounts 三个状态各多少条。<b>是不受当前筛选影响的全局值</b> ——
     *                     管理员需要知道"现在一共有多少条已通过的内容"，
     *                     而不是"我筛出来的这些"（后者看 {@code total}）
     */
    public record AdminPostPage(List<Post> records, long total, long page, long size,
                                long pendingTotal, StatusCounts statusCounts) {
    }

    /**
     * 社区内容的全局状态分布。
     *
     * @param pending  待审核
     * @param approved 已通过（广场上正在展示的）
     * @param rejected 已拒绝
     */
    public record StatusCounts(long pending, long approved, long rejected) {

        public long total() {
            return pending + approved + rejected;
        }
    }

    /**
     * 批量操作结果。
     * <p>
     * <b>刻意返回 {@code affected} 而不是只回一个 200</b>：批量操作最容易出的事
     * 是"看起来成功了，其实一条都没匹配上"（ID 粘错、状态已经变了）。
     * 把数字带回去，前端才能明确说"已处理 3 条，2 条状态不允许"。
     *
     * @param affected 真正被改动的条数
     * @param skipped  因为状态/存在性不满足而跳过的条数
     * @param message  一句可直接展示给管理员的结论
     */
    public record BatchResult(int affected, int skipped, String message) {
    }

    /**
     * 发帖 / 编辑 / 审核之后的回执。
     * <p>返回整条帖子（而不是只回 id）：前端可以直接把卡片换成新状态，
     * 不必再查一次；发帖后 `status` 是 0，前端据此提示"已提交，等待审核"。
     */
    public record ActionResult(Post post, String message) {
    }
}
