package com.leaqutra.shworkcloud.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 好友与私聊的响应体。
 */
public final class FriendVo {

    private FriendVo() {
    }

    /**
     * 用户名片（好友列表 / 搜索结果 / 他人主页都用它）。
     * <p>
     * <b>字段是刻意只有这些的。</b> {@code sys_user} 里还有邮箱、生日、性别、
     * 容量、used_storage、最后登录 IP 等字段，一个都不在这里 ——
     * 好友关系不是"把用户表整行给对方看"的授权。
     * <p>
     * 允许暴露的只有"同班同学互相看得见"的那部分：昵称/姓名、班级、头像、签名、角色。
     * 学号虽然是登录名，但教室里本来就互相知道，且搜索要靠它精确找人；
     * 如果后续认为敏感，从这里删掉 {@code username} 即可（搜索接口会一并失效）。
     *
     * @param userId      用户 ID
     * @param username    登录名（学生即学号）
     * @param displayName 展示名：昵称优先，没有昵称回落到姓名，再回落到登录名。
     *                    <b>由服务端算好</b>，避免前端三处各写一遍优先级
     * @param nickname    昵称原值（前端想自己拼也可以）
     * @param realName    真实姓名（可能为 null）
     * @param className   班级（可能为 null）
     * @param avatarUrl   1 小时有效的 OSS 签名地址，可直接给 {@code <img src>}
     * @param avatarVersion 头像版本串，用于换头像后绕过浏览器缓存
     * @param signature   个性签名
     * @param role        0 学生 / 1 管理员 / 2 教师 / 9 超管
     * @param relation    我与 TA 的关系，见 {@link #relationOf}
     * @param requestedAt 申请时间；只在"待处理"状态下有值
     */
    public record UserCard(Long userId, String username, String displayName, String nickname,
                           String realName, String className, String avatarUrl,
                           String avatarVersion, String signature, Integer role,
                           String relation, LocalDateTime requestedAt) {

        /** 我自己 */
        public static final String RELATION_SELF = "SELF";
        /** 已是好友 */
        public static final String RELATION_FRIEND = "FRIEND";
        /** 我已发出申请，等对方接受 */
        public static final String RELATION_OUTGOING = "OUTGOING";
        /** 对方申请加我，等我处理 */
        public static final String RELATION_INCOMING = "INCOMING";
        /** 没有任何关系（搜索结果里的陌生人） */
        public static final String RELATION_NONE = "NONE";
    }

    /**
     * 好友页的汇总数据。
     * <p>一次请求把三样东西一起给出去（好友 / 待处理申请 / 名额），
     * 而不是拆成三个接口：进好友页必然三样都要，拆开只会多两个来回 ——
     * 与 {@code FileService.breadcrumb} 把面包屑单独给出去的理由相反，
     * 那边的面包屑是"切目录时才需要"，这里是"进页面就一定需要"。
     *
     * @param maxFriends 上限（当前是 50），前端不要写死这个数字
     * @param usedSlots  已占用名额 = 已确认好友 + 待处理申请（收发的都算）
     * @param remaining  还可添加的人数
     */
    public record Overview(List<UserCard> friends, List<UserCard> incoming,
                           List<UserCard> outgoing, int maxFriends, int usedSlots,
                           int remaining) {
    }

    /**
     * 单条消息。
     *
     * @param id        消息 ID（前端拿它当增量拉取的游标）
     * @param fromUserId 发送者
     * @param mine      true 表示这条是我发的（前端据此决定气泡在左还是右）。
     *                  由服务端算，省得前端到处写 {@code msg.fromUserId === me.userId}
     * @param content   纯文本正文。<b>前端必须用文本插值渲染，不得 v-html</b>
     * @param createTime 发送时间
     */
    public record Message(Long id, Long fromUserId, boolean mine, String content,
                          LocalDateTime createTime) {
    }

    /**
     * 一次会话拉取的结果。
     *
     * @param peer     对方名片（含 relation，便于前端在对方删好友后立刻改文案）
     * @param messages 按时间升序
     * @param maxId    本次返回的最大消息 id；前端下次就用它当 {@code afterId}。
     *                 没有消息时原样回传入参，前端不会因此把游标清零
     * @param lastReadIdByPeer 对方已读到的最大消息 id（已读回执）。
     *                 前端把 {@code id <= 该值} 的己方消息标为"已读"
     * @param hasMore  仅在翻历史（传了 beforeId）时有意义：更早的消息还没取完
     */
    public record Thread(UserCard peer, List<Message> messages, long maxId,
                         long lastReadIdByPeer, boolean hasMore) {
    }

    /**
     * 会话列表项（聊天列表 / 左侧会话栏）。
     *
     * @param lastMessage      最后一条消息的正文摘要；没聊过时为 null
     * @param lastMessageTime  最后一条消息的时间；没聊过时为 null
     * @param lastFromMe       最后一条是否我发的（列表里显示"我：xxx"）
     * @param unread           我未读的条数
     */
    public record Conversation(UserCard peer, String lastMessage, LocalDateTime lastMessageTime,
                               boolean lastFromMe, long unread) {
    }

    /** 未读汇总：顶栏红点只认这个接口，避免为了一个数字把整个会话列表拉下来 */
    public record Unread(Integer total, Integer friends, Integer requests) {
    }

    /** 处理申请 / 发送申请之后的简单回执 */
    public record ActionResult(String relation, String message) {
    }
}
