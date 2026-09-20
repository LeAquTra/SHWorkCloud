package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;

/**
 * 好友与私聊的规则常量与纯校验。
 * <p>
 * 抽成独立的规则类（与 {@link AvatarRules} / {@link ProfileRules} / {@link AccountRules}
 * 同一套路）有两个好处：<b>数字只有一处定义</b>（服务端校验与前端提示不会各写一个），
 * 以及纯函数可以脱离 Spring 直接单测。
 */
public final class FriendRules {

    /**
     * 好友数量上限（需求指定）。
     * <p>
     * <b>为什么是硬编码常量而不是配置项：</b>与 {@code AvatarRules.CHANGE_INTERVAL_HOURS}
     * 一致 —— 改这个数要走一次代码评审。它同时出现在服务端校验和前端文案里，
     * 做成配置项就会出现"配置改了但前端还写着 50"的漂移。
     */
    public static final int MAX_FRIENDS = 50;

    /** 单条消息最大字符数，必须与 {@code chat_message.content} 的 VARCHAR(1000) 一致 */
    public static final int MESSAGE_MAX_CHARS = 1000;

    /** 浏览器/接口一次最多取多少条历史消息 */
    public static final int HISTORY_MAX_PAGE = 100;
    public static final int HISTORY_DEFAULT_PAGE = 30;

    private FriendRules() {
    }

    /**
     * 校验"再加一个好友"是否还在上限内。
     * <p>
     * {@code current} 是<b>已用名额</b>，调用方要把"已确认的好友数"和
     * "待处理的申请数"一起算进去：只算好友数的话，49 个好友时同时发 10 个申请，
     * 对方全部接受就会变成 59 个 —— 上限必须在"发申请"这一步就开始守。
     *
     * @param current 已占用的好友名额
     * @throws BizException 已到上限
     */
    public static void ensureRoomForOneMore(int current) {
        if (current >= MAX_FRIENDS) {
            throw new BizException(ErrorCode.FRIEND_LIMIT_REACHED,
                    "好友已达上限（" + MAX_FRIENDS + " 人），请先删除部分好友后再添加");
        }
    }

    /**
     * 校验并规范化待发送的消息正文。
     * <p>
     * 规则：去首尾空白后不能为空、不能超过 {@link #MESSAGE_MAX_CHARS}。
     * <p><b>这里不做 HTML 转义</b>：正文以纯文本原样入库，转义是渲染层的责任
     * （前端用文本插值而不是 {@code v-html}）。在入库时就转义会把 {@code <}
     * 变成 {@code &lt;}，用户之后在别处看到自己发的原话就变形了 ——
     * 存储层存原话、展示层负责转义，这条边界不能混。
     */
    public static String normalizeMessage(String raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "消息内容不能为空");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "消息内容不能为空");
        }
        if (trimmed.length() > MESSAGE_MAX_CHARS) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "单条消息不能超过 " + MESSAGE_MAX_CHARS + " 个字符");
        }
        return trimmed;
    }

    /**
     * 规范化分页大小。
     * <p>上界收到 {@link #HISTORY_MAX_PAGE}：前端传 {@code size=100000} 时
     * 不能真去查十万行，那是一条能打挂服务的请求。
     */
    public static int normalizePageSize(Integer size) {
        if (size == null || size < 1) {
            return HISTORY_DEFAULT_PAGE;
        }
        return Math.min(size, HISTORY_MAX_PAGE);
    }
}
