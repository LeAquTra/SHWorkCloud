package com.leaqutra.shworkcloud.dto;

/**
 * 好友与私聊的请求体。
 * <p>
 * 用 record 而不是可变的 {@code @Data}：这些都是"进来就不该再改"的输入，
 * 不可变能让编译器帮忙挡住"顺手改一下入参"这类问题。
 * <p>
 * <b>通知</b>：这里没有 {@code fromUser} 字段，而且不能加 ——
 * 发送者一律取自 Sa-Token 会话（{@code LoginUser.id()}）。
 * 一旦让前端传发送者，就等于把"冒充别人发消息"做成了接口。
 */
public final class FriendDto {

    private FriendDto() {
    }

    /** 发起好友申请 */
    public record FriendRequestReq(Long userId) {
    }

    /**
     * 处理收到的申请。
     * <p>{@code accept} 为 false 表示拒绝 —— 实现是删掉那条待处理行，
     * 而不是打一个"已拒绝"标记：这样对方之后仍可再次申请，
     * 不会因为一次点错就形成永久黑名单。
     */
    public record HandleReq(Long userId, boolean accept) {
    }

    /** 发送消息 */
    public record MessageReq(Long toUserId, String content) {
    }

    /**
     * 会话消息查询。
     * <p>两种取法共用一个请求体，{@code beforeId} 优先：
     * <ul>
     *   <li><b>增量</b>：只传 {@code afterId}，取比它新的（轮询用，带 {@code id ASC}）；</li>
     *   <li><b>翻历史</b>：传 {@code beforeId}，取比它旧的（向上滚动加载，带 {@code id DESC}）。</li>
     * </ul>
     * 都不传 = 取最新一页。
     * <p>之所以用 id 游标而不是分页页码：聊天时新消息会不断插入，
     * 用 {@code page=2} 翻页会因为"前面又多了几条"而重复或漏掉内容。
     */
    public record MessageQuery(Long peerId, Long afterId, Long beforeId, Integer size) {
    }
}
