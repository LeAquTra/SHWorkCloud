package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.FriendDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.ChatService;
import com.leaqutra.shworkcloud.vo.FriendVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 好友私聊（仅文字）。
 * <p>
 * <b>为什么是拉取而不是推送</b>：见 {@code ChatService} 的类注释 —— 本项目的
 * 会话在内存（{@code sa-token-redis-jackson} 未引入），WebSocket 依赖也不在
 * 离线仓库里；而 Nginx 侧已经配好 {@code proxy_buffering off}，将来换 SSE 或
 * WebSocket 时<b>只有传输层要改</b>：{@code GET /chat/messages} 的
 * {@code afterId} 游标契约可以原样保留。
 * <p>
 * 前端建议的轮询策略：聊天窗打开时 3~5 秒拉一次增量，窗口关闭即停止；
 * 顶栏红点用 {@code GET /chat/unread}（很轻，只有两个 COUNT）。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ClientIpUtil clientIpUtil;

    /** 会话列表：好友 + 最后一条消息 + 未读数，按最后消息时间倒序 */
    @GetMapping("/conversations")
    public R<List<FriendVo.Conversation>> conversations() {
        return R.ok(chatService.conversations());
    }

    /**
     * 与某人的消息。
     * <p>三种用法：
     * <pre>
     *   # 首次进入会话：取最新一页（默认 30 条）
     *   GET /api/chat/messages?peerId=1002
     *   # 轮询增量：只要比 8123 新的（返回结果同时给出下一次的游标 maxId）
     *   GET /api/chat/messages?peerId=1002&amp;afterId=8123
     *   # 向上翻历史：取比 8123 更旧的（hasMore 表示还有更早的）
     *   GET /api/chat/messages?peerId=1002&amp;beforeId=8123&amp;size=30
     * </pre>
     */
    @GetMapping("/messages")
    public R<FriendVo.Thread> messages(FriendDto.MessageQuery query) {
        return R.ok(chatService.thread(query));
    }

    /** 发消息。发送者取自登录会话，请求体里<b>没有</b> fromUserId 这种字段 */
    @PostMapping("/messages")
    public R<FriendVo.Message> send(@RequestBody FriendDto.MessageReq req,
                                    HttpServletRequest request) {
        return R.ok(chatService.send(req, clientIpUtil.get(request)));
    }

    /**
     * 把与某人的会话标记为已读。
     * <p>返回实际置位的条数；前端据此把未读红点减掉，不必再查一次。
     */
    @PostMapping("/read/{peerId}")
    public R<Map<String, Integer>> markRead(@PathVariable Long peerId) {
        return R.ok(Map.of("updated", chatService.markRead(peerId)));
    }

    /**
     * 未读汇总（顶栏红点）。
     * <p>{@code total} = 未读消息 + 待处理好友申请。两类都是"有人在等你"，
     * 合成一个红点比拆成两个更容易被注意到 —— 拆开的话用户往往只看到其中一个。
     */
    @GetMapping("/unread")
    public R<FriendVo.Unread> unread() {
        return R.ok(chatService.unread());
    }

    /**
     * 未读总数的便捷写法，供只想要一个数字的调用方使用。
     * <p>与 {@code /unread} 并存是有意的：只有一个数字时让前端不必解析对象，
     * 顶栏红点每 30 秒轮询一次，响应体越小越好。
     */
    @GetMapping("/unread/count")
    public R<Integer> unreadCount() {
        return R.ok(chatService.unread().total());
    }
}
