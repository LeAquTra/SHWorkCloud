package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.service.AnnouncementService;
import com.leaqutra.shworkcloud.vo.AnnouncementVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公告（用户端）。
 * <p>
 * 只读：任何<b>已登录</b>用户都能拿，不需要额外角色 —— 公告就是发给所有人的。
 * 管理端在 {@link com.leaqutra.shworkcloud.controller.admin.AdminAnnouncementController}。
 * <p>
 * 权限上不放进 PUBLIC_PATHS：未登录时页面都进不去，没必要把公告也公开出去。
 */
@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /**
     * 当前生效的公告列表（已发布、已到时间、未过期），
     * 按注意力分级降序（紧急在前）、同级按发布时间倒序。
     * <p>前端据 {@code level} 决定展示方式：1 普通横幅 / 2 警示横幅 / 3 强制弹窗确认。
     */
    @GetMapping("/active")
    public R<List<AnnouncementVo.Active>> active() {
        return R.ok(announcementService.active());
    }
}
