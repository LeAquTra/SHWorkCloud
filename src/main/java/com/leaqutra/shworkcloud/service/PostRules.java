package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;

/**
 * 社区发帖与审核的规则常量与纯校验。
 * <p>
 * 与 {@link FriendRules} / {@link AnnouncementRules} 同一套路：<b>数字只有一处定义</b>，
 * 且纯函数可以脱离 Spring 直接单测。
 */
public final class PostRules {

    /**
     * 正文最大字符数。
     * <p>与 {@code post.content} 的 TEXT 类型相比小得多，是**产品口径**而不是存储限制：
     * 这是"发一条动态"，不是写文章；上限小一点同时压住了审核成本
     * （审核员要逐条读）和刷屏的收益。
     */
    public static final int MAX_CHARS = 2000;

    /** 时间线一页的默认 / 最大条数 */
    public static final int FEED_DEFAULT_PAGE = 20;
    public static final int FEED_MAX_PAGE = 50;

    /** 审核队列一页的默认 / 最大条数 */
    public static final int REVIEW_DEFAULT_PAGE = 20;
    public static final int REVIEW_MAX_PAGE = 100;

    /** 拒绝理由最大长度，与 {@code post.reject_reason} 的 VARCHAR(200) 对齐 */
    public static final int MAX_REJECT_REASON_CHARS = 200;

    /**
     * 单用户发帖频率上限（/小时）。
     * <p>防的是"一口气刷 200 条把审核队列淹掉"：审核是人工的，
     * 刷帖的真正代价由审核员承担，所以必须在这里挡住。
     * 10 条/小时对正常使用（一节课发两三条）完全够用。
     */
    public static final int POST_HOUR_LIMIT = 10;

    private PostRules() {
    }

    /**
     * 校验并规范化正文。
     * <p>规则：去首尾空白后不能为空、不能超过 {@link #MAX_CHARS}。
     * <p><b>不做 HTML 转义</b>：正文以纯文本原样入库，转义是渲染层的责任
     * （而且这里根本不用转义 —— 链接是服务端解析分段下发的，
     * 前端也不允许 {@code v-html}）。在入库时就转义会把 {@code <} 变成
     * {@code &lt;}，用户之后看到自己发的原话就变形了。
     */
    public static String normalizeContent(String raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "内容不能为空");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "内容不能为空");
        }
        if (trimmed.length() > MAX_CHARS) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "内容不能超过 " + MAX_CHARS + " 个字符");
        }
        return trimmed;
    }

    /**
     * 规范化拒绝理由。
     * <p>拒绝时<b>建议</b>填理由但不强制：审核员在忙的时候可能只想快速拒掉明显违规的内容，
     * 强制填写会让他改为"一律通过"。宁可理由偶尔为空，也不要让审核流于形式。
     * <p>通过时理由一律置空（避免"上一条的拒绝理由"残留在通过的帖子上）。
     */
    public static String normalizeRejectReason(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_REJECT_REASON_CHARS
                ? trimmed.substring(0, MAX_REJECT_REASON_CHARS) : trimmed;
    }

    /** 规范化时间线分页大小 */
    public static int normalizeFeedSize(Integer size) {
        if (size == null || size < 1) {
            return FEED_DEFAULT_PAGE;
        }
        return Math.min(size, FEED_MAX_PAGE);
    }

    /** 规范化审核队列分页大小 */
    public static int normalizeReviewSize(Long size) {
        if (size == null || size < 1) {
            return REVIEW_DEFAULT_PAGE;
        }
        return (int) Math.min(size, REVIEW_MAX_PAGE);
    }

    /**
     * 校验状态取值。
     *
     * @return 合法状态；{@code null} 表示"不筛状态"
     * @throws BizException 取值不在 0/1/2 内
     */
    public static Integer normalizeStatus(Integer status) {
        if (status == null) {
            return null;
        }
        if (status < 0 || status > 2) {
            throw new BizException(ErrorCode.BAD_PARAM, "状态只能是 0(待审核) / 1(已通过) / 2(已拒绝)");
        }
        return status;
    }
}
