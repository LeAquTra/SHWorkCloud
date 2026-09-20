package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.dto.FriendDto;
import com.leaqutra.shworkcloud.entity.ChatMessage;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.ChatMessageMapper;
import com.leaqutra.shworkcloud.mapper.FriendRelationMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.RateLimiter;
import com.leaqutra.shworkcloud.vo.FriendVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 好友私聊（<b>仅文字</b>）。
 * <p>
 * <b>三条设计决定：</b>
 * <ol>
 *   <li><b>不是好友不能聊</b>。每次拉会话、发消息都校验好友关系 ——
 *       这是隐私边界：没有它，知道 userId 就能给任意人发消息。删除好友后
 *       连历史也不再展示（{@link #thread} 同样校验），
 *       因为"删了还能翻聊天记录"不符合用户对删除的预期。</li>
 *   <li><b>游标式增量拉取</b>，不用页码也不用时间戳。id 单调递增，
 *       {@code id > lastId} 既不会因为"同一秒有多条"而漏，也不会因为
 *       "前面又插了新行"而重复。换传输层（WebSocket/SSE）时这个契约不用改。</li>
 *   <li><b>正文原样存取，渲染交给前端</b>。入库不转义（否则用户看到自己的原话变形），
 *       前端必须用文本插值而不是 {@code v-html}。长度上限与
 *       {@code chat_message.content} 的 VARCHAR(1000) 对齐。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageMapper chatMessageMapper;
    private final FriendRelationMapper friendRelationMapper;
    private final UserMapper userMapper;
    private final UserCardAssembler cardAssembler;
    private final LoginUser loginUser;
    private final AuditService auditService;
    /** 限流放在 Service 层：任何写消息的入口都必须受限，不依赖 Controller 记得加 */
    private final RateLimiter rateLimiter;

    // ---------------------------------------------------------------- 会话列表

    /**
     * 我的会话列表（含未读数）。
     * <p>
     * 数据来源是<b>好友列表</b>而不是"聊过天的人"：这样刚加上的好友即使一条消息
     * 都没发，也会出现在列表里（否则用户会以为加好友没成功）。没聊过的会话
     * {@code lastMessage} 为 null，排在有过消息的后面。
     * <p>排序：有消息的按最后消息时间倒序，没消息的按加好友时间倒序垫底。
     */
    public List<FriendVo.Conversation> conversations() {
        long me = loginUser.id();
        List<FriendVo.UserCard> friends =
                cardAssembler.toCards(friendRelationMapper.selectFriends(me));

        Map<Long, ChatMessageMapper.LastMessageRow> sent = orEmpty(
                chatMessageMapper.selectLastSentPerPeer(me));
        Map<Long, ChatMessageMapper.LastMessageRow> received = orEmpty(
                chatMessageMapper.selectLastReceivedPerPeer(me));
        Map<Long, ChatMessageMapper.UnreadRow> unread = orEmpty(
                chatMessageMapper.selectUnreadPerPeer(me));

        List<FriendVo.Conversation> list = new ArrayList<>(friends.size());
        for (FriendVo.UserCard friend : friends) {
            Long peerId = friend.userId();
            ChatMessageMapper.LastMessageRow outgoing = sent.get(peerId);
            ChatMessageMapper.LastMessageRow incoming = received.get(peerId);

            // 取两个方向里更新的那一条作为"最后一条"
            ChatMessageMapper.LastMessageRow last = latest(outgoing, incoming);
            long unreadCount = unread.containsKey(peerId) ? unread.get(peerId).unread() : 0L;

            list.add(new FriendVo.Conversation(friend,
                    last == null ? null : last.content(),
                    last == null ? null : last.createTime(),
                    last != null && last.fromUser() != null && last.fromUser() == me,
                    unreadCount));
        }

        // 有消息的排前面（按时间倒序）；没消息的保持"最近加的好友在前"
        list.sort((a, b) -> {
            LocalDateTime ta = a.lastMessageTime();
            LocalDateTime tb = b.lastMessageTime();
            if (ta == null && tb == null) {
                return 0;   // 保持 selectFriends 的既有顺序（成为好友时间倒序）
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.compareTo(ta);
        });
        return list;
    }

    /** 未读汇总：顶栏红点用一个轻量接口，不必把整个会话列表拉下来 */
    public FriendVo.Unread unread() {
        long me = loginUser.id();
        int friends = (int) chatMessageMapper.countUnread(me);
        // 待处理申请也计入红点：好友申请和聊天消息都是"有人在等你"，
        // 分散成两个红点反而会被忽略
        int requests = friendRelationMapper.selectIncoming(me).size();
        return new FriendVo.Unread(friends + requests, friends, requests);
    }

    // ---------------------------------------------------------------- 单会话

    /**
     * 拉取与某人的消息。
     * <p>三种取法（见 {@link FriendDto.MessageQuery}）：
     * <ul>
     *   <li>{@code afterId} → 增量（轮询）；</li>
     *   <li>{@code beforeId} → 翻历史（向上滚动）；</li>
     *   <li>都不传 → 最新一页。</li>
     * </ul>
     * 返回的 {@code maxId} 是前端的下一个游标；没有新消息时会把入参原样带回，
     * 前端不会因为一次空响应把游标清零（清零会导致下次把整个会话重拉一遍）。
     */
    public FriendVo.Thread thread(FriendDto.MessageQuery query) {
        long me = loginUser.id();
        Long peerId = query == null ? null : query.peerId();
        if (peerId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定聊天对象");
        }
        SysUser peer = requireActiveUser(peerId);
        requireFriend(me, peerId);

        int size = FriendRules.normalizePageSize(query.size());
        Long afterId = query.afterId();
        Long beforeId = query.beforeId();

        List<ChatMessage> rows;
        boolean hasMore = false;
        if (afterId != null && afterId > 0) {
            rows = chatMessageMapper.selectSince(me, peerId, afterId, size);
        } else if (beforeId != null && beforeId > 0) {
            // 降序取一页再反转成时间顺序；多取一条用来判断"还有没有更早的"
            rows = new ArrayList<>(chatMessageMapper.selectBefore(me, peerId, beforeId, size + 1));
            hasMore = rows.size() > size;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, size));
            }
            Collections.reverse(rows);
        } else {
            rows = new ArrayList<>(chatMessageMapper.selectBefore(me, peerId, Long.MAX_VALUE, size));
            Collections.reverse(rows);
        }

        long maxId = afterId == null ? 0L : afterId;
        List<FriendVo.Message> messages = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            messages.add(toVo(row, me));
            if (row.getId() != null && row.getId() > maxId) {
                maxId = row.getId();
            }
        }

        return new FriendVo.Thread(
                cardAssembler.toCard(peer, cardAssembler.relationOf(me, peerId), null),
                messages,
                maxId,
                chatMessageMapper.selectPeerLastReadId(me, peerId),
                hasMore);
    }

    /**
     * 发送消息。
     * <p>校验顺序刻意是"能不能发"优先于"内容对不对"：对非好友先报"你们还不是好友"，
     * 而不是先报"内容不能为空" —— 后者会让陌生人通过试探不同内容来判断
     * 对方账号是否存在。
     */
    @Transactional(rollbackFor = Exception.class)
    public FriendVo.Message send(FriendDto.MessageReq req, String clientIp) {
        long me = loginUser.id();
        Long toId = req == null ? null : req.toUserId();
        if (toId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定消息接收者");
        }
        if (toId == me) {
            throw new BizException(ErrorCode.BAD_PARAM, "不能给自己发消息");
        }

        requireActiveUser(toId);
        requireFriend(me, toId);

        // 关系校验之后再限流：非好友本来就发不出去，不该消耗他的发送额度
        rateLimiter.checkChatSend(me);

        String content = FriendRules.normalizeMessage(req.content());

        ChatMessage message = new ChatMessage();
        // 发送者只可能来自会话，不接受前端传入 —— 否则就成了"冒充他人发消息"的接口
        message.setFromUser(me);
        message.setToUser(toId);
        message.setContent(content);
        message.setReadFlag(ChatMessage.READ_NO);
        chatMessageMapper.insert(message);

        // 审计里只记长度，不记正文：聊天内容属隐私，日志不该留副本
        auditService.log(me, "CHAT_SEND", "USER", String.valueOf(toId), clientIp, true,
                "chars=" + content.length());
        return toVo(message, me);
    }

    /**
     * 把与某人的会话标记为已读。
     *
     * @return 本次实际置为已读的条数（前端可据此决定要不要刷新未读红点）
     */
    @Transactional(rollbackFor = Exception.class)
    public int markRead(Long peerId) {
        long me = loginUser.id();
        if (peerId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定会话");
        }
        // 不校验好友关系：删除好友后可能会残留未读，那些也该能被清掉，
        // 否则红点会永久挂在那里点不掉
        return chatMessageMapper.markReadFrom(me, peerId);
    }

    // ---------------------------------------------------------------- 内部工具

    private FriendVo.Message toVo(ChatMessage row, long me) {
        return new FriendVo.Message(row.getId(), row.getFromUser(),
                row.getFromUser() != null && row.getFromUser() == me,
                row.getContent(), row.getCreateTime());
    }

    /** 比对两个方向的最后一条，返回更新的那条（都为 null 时返回 null） */
    private ChatMessageMapper.LastMessageRow latest(ChatMessageMapper.LastMessageRow a,
                                                    ChatMessageMapper.LastMessageRow b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.createTime() == null) {
            return b;
        }
        if (b.createTime() == null) {
            return a;
        }
        return a.createTime().isAfter(b.createTime()) ? a : b;
    }

    private void requireFriend(long me, long otherId) {
        if (!cardAssembler.isFriend(me, otherId)) {
            throw new BizException(ErrorCode.NOT_FRIEND);
        }
    }

    private SysUser requireActiveUser(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FRIEND_TARGET_NOT_FOUND);
        }
        return user;
    }

    /** MyBatis 的 @MapKey 查询在无结果时可能返回 null，统一成空 Map 免得处处判空 */
    private <T> Map<Long, T> orEmpty(Map<Long, T> map) {
        return map == null ? Map.of() : map;
    }
}
