package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.entity.Announcement;

import java.time.LocalDateTime;

/**
 * 公告的校验与"是否生效"判定。
 * <p>
 * 抽成纯静态类是为了可单元测试（不依赖 Spring 与数据库），
 * 也让"什么时候该展示这条公告"这件事只在<b>一处</b>定义 ——
 * 用户端查询与后台列表的"生效中"都必须用同一个 {@link #isEffective}。
 */
public final class AnnouncementRules {

    public static final int TITLE_MAX = 120;
    /** 正文上限：公告是"通知"，不是文档；超长内容应该发文件 */
    public static final int CONTENT_MAX = 5000;
    /** 过期时间最远允许多久：防止误填成 2099 年导致公告永久挂着 */
    public static final int EXPIRE_MAX_DAYS = 365;

    private AnnouncementRules() {
    }

    /** 标题：必填、去空格、限长 */
    public static String normalizeTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_PARAM, "公告标题不能为空");
        }
        String value = raw.trim();
        if (value.codePointCount(0, value.length()) > TITLE_MAX) {
            throw new BizException(ErrorCode.BAD_PARAM, "公告标题最多 " + TITLE_MAX + " 个字符");
        }
        return value;
    }

    /** 正文：必填、去首尾空格、限长（纯文本，前端按换行渲染） */
    public static String normalizeContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_PARAM, "公告内容不能为空");
        }
        String value = raw.strip();
        if (value.codePointCount(0, value.length()) > CONTENT_MAX) {
            throw new BizException(ErrorCode.BAD_PARAM, "公告内容最多 " + CONTENT_MAX + " 个字符");
        }
        return value;
    }

    /** 注意力分级：只接受 1 / 2 / 3 */
    public static int normalizeLevel(Integer level) {
        if (level == null) {
            return Announcement.LEVEL_NORMAL;
        }
        if (level < Announcement.LEVEL_NORMAL || level > Announcement.LEVEL_URGENT) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "注意力分级只能是 1(普通) / 2(重要) / 3(紧急)");
        }
        return level;
    }

    /**
     * 过期时间：可为空（永不过期）；填了就必须是将来、且不超过一年。
     * <p>"必须是将来的时间"能挡住"填了昨天的日期，结果发布出去立刻消失"这种低级错误。
     */
    public static LocalDateTime normalizeExpireTime(LocalDateTime expireTime) {
        if (expireTime == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!expireTime.isAfter(now)) {
            throw new BizException(ErrorCode.BAD_PARAM, "过期时间必须晚于当前时间");
        }
        if (expireTime.isAfter(now.plusDays(EXPIRE_MAX_DAYS))) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "过期时间最多设为 " + EXPIRE_MAX_DAYS + " 天后（避免公告被永久挂住）");
        }
        return expireTime;
    }

    /**
     * 这条公告此刻是否对用户可见。
     * <p>三个条件同时成立：已发布、已到发布时间、未过期。
     * <p>发布时间可能为空（历史数据或手工插入），此时视为"发布即可见"。
     */
    public static boolean isEffective(Announcement announcement, LocalDateTime now) {
        if (announcement == null || announcement.getStatus() == null
                || announcement.getStatus() != Announcement.STATUS_PUBLISHED) {
            return false;
        }
        if (announcement.getPublishTime() != null && announcement.getPublishTime().isAfter(now)) {
            return false;
        }
        return announcement.getExpireTime() == null || announcement.getExpireTime().isAfter(now);
    }
}
