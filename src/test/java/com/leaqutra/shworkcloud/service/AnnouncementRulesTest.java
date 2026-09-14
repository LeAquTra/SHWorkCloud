package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.entity.Announcement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公告规则。
 * <p>
 * 这里守的是"公告什么时候该打扰用户"这条线：<b>已发布 + 已到时间 + 未过期</b>。
 * 用户端查询与后台列表的 {@code effective} 都调用同一个 {@link AnnouncementRules#isEffective}，
 * 所以这组用例也是在给两个接口同时上锁。
 */
class AnnouncementRulesTest {

    private static Announcement announcement(Integer status, Integer level,
                                             LocalDateTime publishTime, LocalDateTime expireTime) {
        Announcement item = new Announcement();
        item.setId(1L);
        item.setTitle("标题");
        item.setContent("正文");
        item.setStatus(status);
        item.setLevel(level);
        item.setPublishTime(publishTime);
        item.setExpireTime(expireTime);
        return item;
    }

    private static String repeated(String unit, int count) {
        return unit.repeat(count);
    }

    // ------------------------------------------------------------ 标题

    @Test
    @DisplayName("标题：去首尾空格后落库")
    void titleTrimmed() {
        assertEquals("系统维护通知", AnnouncementRules.normalizeTitle("  系统维护通知  "));
    }

    @Test
    @DisplayName("标题：空 / 纯空白被拒绝（40000）")
    void titleRequired() {
        for (String raw : new String[]{null, "", "   ", "\n\t"}) {
            BizException e = assertThrows(BizException.class,
                    () -> AnnouncementRules.normalizeTitle(raw));
            assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
        }
    }

    @Test
    @DisplayName("标题：正好 120 字符通过，121 字符被拒")
    void titleLengthBoundary() {
        assertEquals(AnnouncementRules.TITLE_MAX,
                AnnouncementRules.normalizeTitle(repeated("标", AnnouncementRules.TITLE_MAX)).length());
        assertThrows(BizException.class, () -> AnnouncementRules.normalizeTitle(
                repeated("标", AnnouncementRules.TITLE_MAX + 1)));
    }

    @Test
    @DisplayName("标题：按码点计数，emoji 不会被算成 2 个字符")
    void titleCountsCodePoints() {
        // "🌙" 在 UTF-16 里占 2 个 char、1 个码点；按码点计数才不会被误判超长
        String emoji = "🌙";
        int max = AnnouncementRules.TITLE_MAX;
        assertEquals(2, emoji.length());
        assertDoesNotThrow(() -> AnnouncementRules.normalizeTitle(emoji.repeat(max)));
        assertThrows(BizException.class,
                () -> AnnouncementRules.normalizeTitle(emoji.repeat(max + 1)));
    }

    // ------------------------------------------------------------ 正文

    @Test
    @DisplayName("正文：只去掉首尾空白，中间换行原样保留（前端按换行渲染）")
    void contentKeepsInnerLines() {
        assertEquals("第一行\n第二行", AnnouncementRules.normalizeContent("\n  第一行\n第二行  \n"));
    }

    @Test
    @DisplayName("正文：空被拒；正好 5000 字符通过")
    void contentRequiredAndBounded() {
        assertThrows(BizException.class, () -> AnnouncementRules.normalizeContent(null));
        assertThrows(BizException.class, () -> AnnouncementRules.normalizeContent("  "));
        assertEquals(AnnouncementRules.CONTENT_MAX, AnnouncementRules
                .normalizeContent(repeated("文", AnnouncementRules.CONTENT_MAX)).length());
        assertThrows(BizException.class, () -> AnnouncementRules.normalizeContent(
                repeated("文", AnnouncementRules.CONTENT_MAX + 1)));
    }

    // ------------------------------------------------------------ 注意力分级

    @Test
    @DisplayName("注意力分级：不传默认 1（普通）；1/2/3 均可")
    void levelDefaultsAndAccepts() {
        assertEquals(Announcement.LEVEL_NORMAL, AnnouncementRules.normalizeLevel(null));
        assertEquals(1, AnnouncementRules.normalizeLevel(1));
        assertEquals(2, AnnouncementRules.normalizeLevel(2));
        assertEquals(3, AnnouncementRules.normalizeLevel(3));
    }

    @Test
    @DisplayName("注意力分级：0 / 4 / 99 一律拒绝，不能悄悄降级成普通公告")
    void levelRejectsOutOfRange() {
        for (int level : new int[]{0, -1, 4, 99}) {
            BizException e = assertThrows(BizException.class,
                    () -> AnnouncementRules.normalizeLevel(level));
            assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
        }
    }

    // ------------------------------------------------------------ 过期时间

    @Test
    @DisplayName("过期时间：不传表示永不过期")
    void expireTimeOptional() {
        assertNull(AnnouncementRules.normalizeExpireTime(null));
    }

    @Test
    @DisplayName("过期时间：过去的时刻被拒绝（防止发出去就立刻消失）")
    void expireTimeMustBeFuture() {
        assertThrows(BizException.class, () -> AnnouncementRules
                .normalizeExpireTime(LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    @DisplayName("过期时间：最远一年，超一天即拒绝")
    void expireTimeBoundedToOneYear() {
        LocalDateTime ok = LocalDateTime.now().plusDays(AnnouncementRules.EXPIRE_MAX_DAYS).minusHours(1);
        assertDoesNotThrow(() -> AnnouncementRules.normalizeExpireTime(ok));

        assertThrows(BizException.class, () -> AnnouncementRules.normalizeExpireTime(
                LocalDateTime.now().plusDays(AnnouncementRules.EXPIRE_MAX_DAYS).plusDays(1)));
    }

    // ------------------------------------------------------------ 是否生效

    @Test
    @DisplayName("空对象 / 状态为空 → 不生效（不抛异常，查询链路不能因此 500）")
    void effectiveHandlesNull() {
        LocalDateTime now = LocalDateTime.now();
        assertFalse(AnnouncementRules.isEffective(null, now));
        assertFalse(AnnouncementRules.isEffective(announcement(null, 1, null, null), now));
    }

    @Test
    @DisplayName("草稿 / 已撤回 → 不生效（只有已发布才对用户可见）")
    void effectiveOnlyWhenPublished() {
        LocalDateTime now = LocalDateTime.now();
        assertFalse(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_DRAFT, 3, now.minusHours(1), null), now));
        assertFalse(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_RECALLED, 3, now.minusHours(1), null), now));
    }

    @Test
    @DisplayName("已发布 + 无发布时间/无过期时间 → 生效（历史数据与手工插入的兜底）")
    void effectiveWithNoTimeBounds() {
        assertTrue(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_PUBLISHED, 1, null, null), LocalDateTime.now()));
    }

    @Test
    @DisplayName("已发布但发布时间在未来 → 不生效（定时公告）")
    void effectiveRespectsFuturePublishTime() {
        LocalDateTime now = LocalDateTime.now();
        assertFalse(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_PUBLISHED, 2, now.plusMinutes(30), null), now));
        assertTrue(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_PUBLISHED, 2, now.minusMinutes(30), null), now));
    }

    @Test
    @DisplayName("过期判定用严格大于：正好到点的瞬间就不再展示")
    void effectiveExpireBoundary() {
        LocalDateTime now = LocalDateTime.now();
        assertFalse(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_PUBLISHED, 2, now.minusHours(1), now), now));
        assertFalse(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_PUBLISHED, 2, now.minusHours(1), now.minusSeconds(1)), now));
        assertTrue(AnnouncementRules.isEffective(
                announcement(Announcement.STATUS_PUBLISHED, 2, now.minusHours(1), now.plusSeconds(1)), now));
    }

    // ------------------------------------------------------------ 常量契约

    @Test
    @DisplayName("分级与状态常量：与数据库注释、前端约定一致")
    void constantContract() {
        assertEquals(1, Announcement.LEVEL_NORMAL);
        assertEquals(2, Announcement.LEVEL_IMPORTANT);
        assertEquals(3, Announcement.LEVEL_URGENT);
        assertEquals(0, Announcement.STATUS_DRAFT);
        assertEquals(1, Announcement.STATUS_PUBLISHED);
        assertEquals(2, Announcement.STATUS_RECALLED);
        // 分级区间与 normalizeLevel 的接受范围必须由常量推导，不能各写一份数字
        assertDoesNotThrow(() -> AnnouncementRules.normalizeLevel(Announcement.LEVEL_URGENT));
        assertThrows(BizException.class,
                () -> AnnouncementRules.normalizeLevel(Announcement.LEVEL_URGENT + 1));
    }
}
