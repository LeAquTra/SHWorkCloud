package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.dto.FriendDto;
import com.leaqutra.shworkcloud.vo.FriendVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 好友与私聊的契约守卫。
 * <p>
 * 守三类"评审时容易漏、漏了后果不小"的事：
 * <ol>
 *   <li><b>越权/冒充</b>：私聊的请求体里绝不能出现"发送者"字段 ——
 *       一旦让前端传 {@code fromUserId}，就等于把"冒充别人发消息"做成了接口；</li>
 *   <li><b>信息泄露</b>：{@link FriendVo.UserCard} 是好友列表、搜索、他人主页、
 *       聊天窗四处共用的视图。它多一个字段，等于四个接口同时把用户表的该列开放出去
 *       （邮箱、生日、性别、容量都在 {@code sys_user} 里）；</li>
 *   <li><b>上限口径</b>：{@code maxFriends} 必须由服务端下发，前端不许写死 50。</li>
 * </ol>
 * 与 {@code AnnouncementContractTest} / {@code FileUrlContractTest} 同一思路：
 * 把口头约定变成会失败的测试。
 */
class FriendContractTest {

    private static List<String> componentNames(Class<? extends Record> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    // ---------------------------------------------------------------- 防冒充

    @Test
    @DisplayName("发消息的请求体里没有 fromUser / fromUserId —— 发送者只能来自登录会话")
    void messageRequestHasNoSenderField() {
        List<String> names = componentNames(FriendDto.MessageReq.class);
        assertEquals(List.of("toUserId", "content"), names,
                "MessageReq 的字段集合变化必须是有意为之：多一个发送者字段就是冒充漏洞");

        for (String forbidden : List.of("fromUser", "fromUserId", "senderId", "userId")) {
            assertFalse(names.contains(forbidden),
                    "请求体不能携带发送者（" + forbidden + "），否则可以冒充他人发消息");
        }
    }

    @Test
    @DisplayName("聊天接口的每个写操作都从会话取身份：Controller 方法签名里没有发送者参数")
    void chatEndpointsTakeNoSenderParameter() {
        for (Method method : ChatController.class.getDeclaredMethods()) {
            for (Class<?> type : method.getParameterTypes()) {
                assertFalse(type.getName().contains("FromUser"),
                        method.getName() + " 的入参里出现了发送者类型，身份必须取自 Sa-Token 会话");
            }
        }
    }

    // ---------------------------------------------------------------- 防泄露

    @Test
    @DisplayName("UserCard 只带同班同学互相看得见的字段，不含邮箱/生日/性别/容量")
    void userCardDoesNotLeakPrivateColumns() {
        List<String> names = componentNames(FriendVo.UserCard.class);

        assertEquals(List.of("userId", "username", "displayName", "nickname", "realName",
                        "className", "avatarUrl", "avatarVersion", "signature", "role",
                        "relation", "requestedAt"), names,
                "名片字段集合变化必须是有意为之：它是四个接口共用的视图，多一个字段就多一处泄露");

        for (String sensitive : List.of("email", "birthday", "gender", "storageQuota",
                "usedStorage", "lastLoginIp", "lastLoginTime", "password", "avatarKey")) {
            assertFalse(names.contains(sensitive),
                    sensitive + " 不该出现在他人可见的名片里");
        }
    }

    @Test
    @DisplayName("名片自带 displayName 与 relation，前端不必自己推算")
    void userCardCarriesComputedFields() {
        List<String> names = componentNames(FriendVo.UserCard.class);
        // displayName：昵称→姓名→登录名 的优先级只应该有一处实现
        assertTrue(names.contains("displayName"));
        // relation：前端据此决定按钮是"加好友/已申请/同意/已是好友"
        assertTrue(names.contains("relation"));
    }

    @Test
    @DisplayName("关系取值是固定集合，避免前端 switch 到不存在的分支")
    void relationValuesAreClosed() {
        List<String> relations = List.of(
                FriendVo.UserCard.RELATION_SELF,
                FriendVo.UserCard.RELATION_FRIEND,
                FriendVo.UserCard.RELATION_INCOMING,
                FriendVo.UserCard.RELATION_OUTGOING,
                FriendVo.UserCard.RELATION_NONE);
        assertEquals(5, relations.size());
        assertEquals(5, relations.stream().distinct().count(), "关系取值不能重复");
        for (String relation : relations) {
            assertTrue(relation.equals(relation.toUpperCase()),
                    "关系取值用大写常量，便于前端做字面量分支：" + relation);
        }
    }

    // ---------------------------------------------------------------- 上限口径

    @Test
    @DisplayName("Overview 必须下发 maxFriends：前端不许把 50 写死在代码里")
    void overviewCarriesFriendLimit() {
        List<String> names = componentNames(FriendVo.Overview.class);
        assertTrue(names.contains("maxFriends"), "上限必须由服务端下发，否则改上限要同时发前端");
        assertTrue(names.contains("usedSlots"), "已用名额 = 好友 + 待处理申请，由服务端算");
        assertTrue(names.contains("remaining"), "剩余可加人数由服务端算，避免前端自己减错");
        assertTrue(names.contains("incoming"), "收到的申请要给出来，否则用户看不到有人加他");
        assertTrue(names.contains("outgoing"), "发出的申请也要给出来，否则点完加好友界面没反应");
    }

    @Test
    @DisplayName("消息视图带 mine 标记，前端不必到处比较 fromUserId")
    void messageCarriesMineFlag() {
        assertTrue(componentNames(FriendVo.Message.class).contains("mine"));
    }

    @Test
    @DisplayName("会话视图带未读数与最后一条消息，列表不需要二次请求")
    void conversationCarriesPreview() {
        List<String> names = componentNames(FriendVo.Conversation.class);
        assertTrue(names.contains("unread"));
        assertTrue(names.contains("lastMessage"));
        assertTrue(names.contains("lastMessageTime"));
        assertTrue(names.contains("lastFromMe"));
    }

    @Test
    @DisplayName("会话拉取回执带 maxId 与对方已读位置（游标与已读回执）")
    void threadCarriesCursorAndReadReceipt() {
        List<String> names = componentNames(FriendVo.Thread.class);
        assertTrue(names.contains("maxId"), "没有 maxId，前端无法做增量拉取");
        assertTrue(names.contains("lastReadIdByPeer"), "没有已读位置，己方气泡无法显示已读");
        assertTrue(names.contains("hasMore"), "翻历史需要知道还有没有更早的");
    }
}
