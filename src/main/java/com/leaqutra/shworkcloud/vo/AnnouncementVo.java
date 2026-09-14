package com.leaqutra.shworkcloud.vo;

import java.time.LocalDateTime;

/**
 * 公告视图。
 * <p>
 * 用户端与后台<b>刻意分开</b>：用户端不需要看到"草稿/已撤回"、也不需要知道是谁发的，
 * 少下发一个字段就少一个信息泄露面。
 */
public final class AnnouncementVo {

    /**
     * 用户端：只含<b>当前生效</b>的公告（已发布、已到时间、未过期）。
     *
     * @param level 注意力分级：1 普通 2 重要 3 紧急 —— 前端据此决定
     *              是顶部横幅、警示横幅，还是强制弹窗确认
     */
    public record Active(Long id, String title, String content, Integer level,
                         LocalDateTime publishTime, LocalDateTime expireTime) {
    }

    /**
     * 后台：含状态与审计字段，便于管理与追溯。
     *
     * @param effective 此刻是否真的对用户可见（已发布 + 已到时间 + 未过期）。
     *                  后台列表要让管理员一眼看出"发布了但其实没生效/已过期"，
     *                  否则会出现"我明明发了，用户说没看到"这种扯皮。
     */
    public record Manage(Long id, String title, String content, Integer level, Integer status,
                         LocalDateTime publishTime, LocalDateTime expireTime, Long createdBy,
                         LocalDateTime createTime, LocalDateTime updateTime, boolean effective) {
    }
}
