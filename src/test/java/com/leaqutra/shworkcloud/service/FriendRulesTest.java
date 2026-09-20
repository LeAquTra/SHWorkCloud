package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * 好友与私聊规则的边界。
 * <p>
 * 重点是 <b>50 人上限的临界点</b>：{@code current >= 50} 才拒绝，
 * 也就是说第 50 个好友必须加得进来。写成 {@code > 50} 就会"只能加 49 个"，
 * 而写成 {@code >= 49} 又会少一个 —— 两边都差一个人，代码评审时极难看出来。
 */
class FriendRulesTest {

    // ---------------------------------------------------------------- 好友上限

    @Test
    @DisplayName("上限是 50（需求指定），且这个数字只有一个定义处")
    void maxFriendsIsFifty() {
        assertEquals(50, FriendRules.MAX_FRIENDS);
    }

    @Test
    @DisplayName("第 50 个好友加得进来：已占 49 个名额时不应被拦")
    void allowsTheFiftiethFriend() {
        // 49 个已用 → 还能加一个（这一个就是第 50 个）
        FriendRules.ensureRoomForOneMore(49);
    }

    @Test
    @DisplayName("已占满 50 个名额后必须拒绝")
    void rejectsWhenFull() {
        BizException e = assertThrows(BizException.class,
                () -> FriendRules.ensureRoomForOneMore(50));
        assertEquals(ErrorCode.FRIEND_LIMIT_REACHED, e.getErrorCode());
        // 提示里要带上限数字，前端可以原样展示
        assertTrue(e.getMessage().contains("50"), e.getMessage());
    }

    @Test
    @DisplayName("脏数据导致超限时也要拒绝，不能让 '还可添加 -1 人' 出现")
    void rejectsWhenAlreadyOverLimit() {
        assertThrows(BizException.class, () -> FriendRules.ensureRoomForOneMore(51));
    }

    // ---------------------------------------------------------------- 消息正文

    @Test
    @DisplayName("消息去首尾空白；只有空白的消息视为为空")
    void normalizesBlankMessage() {
        assertEquals("在吗", FriendRules.normalizeMessage("  在吗  "));
        assertThrows(BizException.class, () -> FriendRules.normalizeMessage("   "));
        assertThrows(BizException.class, () -> FriendRules.normalizeMessage(null));
    }

    @Test
    @DisplayName("消息长度上限与 chat_message.content 的 VARCHAR(1000) 对齐")
    void messageLengthMatchesColumn() {
        assertEquals(1000, FriendRules.MESSAGE_MAX_CHARS);

        String ok = "字".repeat(1000);
        assertEquals(ok, FriendRules.normalizeMessage(ok));

        BizException e = assertThrows(BizException.class,
                () -> FriendRules.normalizeMessage("字".repeat(1001)));
        assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
    }

    @Test
    @DisplayName("正文不做 HTML 转义：存原话，转义是渲染层的事")
    void doesNotEscapeHtml() {
        // 一旦在这里转义，用户之后看到自己发的原话就变成 &lt;b&gt; 了
        String raw = "<b>加粗</b> & <script>alert(1)</script>";
        assertEquals(raw, FriendRules.normalizeMessage(raw));
    }

    // ---------------------------------------------------------------- 搜人：只允许按 ID

    @Test
    @DisplayName("删好友 / 拒绝申请 一律物理删除（软删除会占住 uk_edge，见 FriendRelationSchemaTest）")
    void deletionIsPhysical() {
        // 这条断言本身没有逻辑可测，它的价值是"把理由钉在测试里"：
        // FriendRelation 上没有 @TableLogic，Mapper 也不再过滤 deleted 列，
        // 由 FriendRelationSchemaTest 静态守卫。这里只做一个提醒式的存在性检查。
        assertTrue(java.util.Arrays.stream(
                        com.leaqutra.shworkcloud.entity.FriendRelation.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("deleted")),
                "FriendRelation 又出现了 deleted 字段：软删除 + uk_edge 会让"
                        + "「拒绝后再申请」撞 Duplicate entry 报 500");
    }

    // ---------------------------------------------------------------- 分页

    @Test
    @DisplayName("分页大小上界收到 100：size=100000 不能真去查十万行")
    void capsPageSize() {
        assertEquals(FriendRules.HISTORY_DEFAULT_PAGE, FriendRules.normalizePageSize(null));
        assertEquals(FriendRules.HISTORY_DEFAULT_PAGE, FriendRules.normalizePageSize(0));
        assertEquals(FriendRules.HISTORY_DEFAULT_PAGE, FriendRules.normalizePageSize(-5));
        assertEquals(20, FriendRules.normalizePageSize(20));
        assertEquals(FriendRules.HISTORY_MAX_PAGE, FriendRules.normalizePageSize(100000));
        assertFalse(FriendRules.normalizePageSize(100000) > FriendRules.HISTORY_MAX_PAGE);
    }
}
