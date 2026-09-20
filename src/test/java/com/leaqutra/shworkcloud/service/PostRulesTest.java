package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 社区发帖 / 审核的规则边界。
 * <p>
 * 重点在两处：<b>正文长度</b>（与前端 maxlength 必须一致）与
 * <b>状态取值</b>（越界的 status 不能被当成"不筛"，否则后台会静默显示出全部帖子）。
 */
class PostRulesTest {

    @Test
    @DisplayName("正文长度上限 2000：正好 2000 收下，2001 拒绝")
    void contentLengthBoundary() {
        assertEquals(2000, PostRules.MAX_CHARS);

        String ok = "字".repeat(2000);
        assertEquals(ok, PostRules.normalizeContent(ok));

        BizException e = assertThrows(BizException.class,
                () -> PostRules.normalizeContent("字".repeat(2001)));
        assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
    }

    @Test
    @DisplayName("正文去首尾空白；只有空白的正文视为为空；null 也报错")
    void contentNormalization() {
        assertEquals("今天有作业", PostRules.normalizeContent("  今天有作业  "));
        assertThrows(BizException.class, () -> PostRules.normalizeContent("    "));
        assertThrows(BizException.class, () -> PostRules.normalizeContent(null));
    }

    @Test
    @DisplayName("正文不做 HTML 转义（存原话，链接由服务端解析分段）")
    void contentIsNotEscaped() {
        String raw = "<b>x</b> & https://a.com";
        assertEquals(raw, PostRules.normalizeContent(raw));
    }

    @Test
    @DisplayName("拒绝理由可以为空（不强制填写，否则审核员会改为一律通过）")
    void rejectReasonIsOptional() {
        assertNull(PostRules.normalizeRejectReason(null));
        assertNull(PostRules.normalizeRejectReason(""));
        assertNull(PostRules.normalizeRejectReason("   "));
        assertEquals("含不良信息", PostRules.normalizeRejectReason("  含不良信息  "));
    }

    @Test
    @DisplayName("拒绝理由超长时截断到 200（与 VARCHAR(200) 对齐），不是报错")
    void rejectReasonTruncated() {
        assertEquals(200, PostRules.MAX_REJECT_REASON_CHARS);
        String longReason = "很".repeat(500);
        assertEquals(200, PostRules.normalizeRejectReason(longReason).length());
    }

    @Test
    @DisplayName("状态只允许 0/1/2；null 表示不筛")
    void statusValidation() {
        assertNull(PostRules.normalizeStatus(null));
        assertEquals(0, PostRules.normalizeStatus(0));
        assertEquals(1, PostRules.normalizeStatus(1));
        assertEquals(2, PostRules.normalizeStatus(2));
        // 越界必须报错，不能被当成"不筛" —— 否则后台会静默显示出全部帖子
        assertThrows(BizException.class, () -> PostRules.normalizeStatus(-1));
        assertThrows(BizException.class, () -> PostRules.normalizeStatus(3));
    }

    @Test
    @DisplayName("分页大小上界：size=100000 不能真去查十万行")
    void pageSizeCaps() {
        assertEquals(PostRules.FEED_DEFAULT_PAGE, PostRules.normalizeFeedSize(null));
        assertEquals(PostRules.FEED_DEFAULT_PAGE, PostRules.normalizeFeedSize(0));
        assertEquals(20, PostRules.normalizeFeedSize(20));
        assertEquals(PostRules.FEED_MAX_PAGE, PostRules.normalizeFeedSize(100000));

        assertEquals(PostRules.REVIEW_DEFAULT_PAGE, PostRules.normalizeReviewSize(null));
        assertEquals(20L, PostRules.normalizeReviewSize(20L));
        assertEquals(PostRules.REVIEW_MAX_PAGE, PostRules.normalizeReviewSize(100000L));
    }

    @Test
    @DisplayName("发帖频率上限比聊天严得多（刷帖的代价由审核员承担）")
    void postLimitIsStricterThanChat() {
        // 10 条/小时：够"一节课发两三条"，但拦得住"一口气刷 200 条淹掉审核队列"
        assertEquals(10, PostRules.POST_HOUR_LIMIT);
    }
}
