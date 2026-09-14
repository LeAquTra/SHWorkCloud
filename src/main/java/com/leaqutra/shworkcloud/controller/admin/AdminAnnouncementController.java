package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.AdminDto;
import com.leaqutra.shworkcloud.dto.AnnouncementQuery;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.AdminAnnouncementService;
import com.leaqutra.shworkcloud.vo.AnnouncementVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台公告管理。
 * <p>
 * <b>仅超级管理员</b>：公告会影响全站每一个人（紧急公告还会强制弹窗打断用户操作），
 * 这个权力不适合下放给普通管理员或机房教师。
 * <p>
 * 路由级规则（{@code /admin/**} 需 admin 及以上）已由 {@code SaTokenConfigure} 兜底，
 * 这里的 {@code @SaCheckRole("super_admin")} 是收窄而不是唯一防线。
 * <p>
 * 注解逐个标在方法上（而不是标在类上）：与项目其它 Controller 保持一致，
 * 也让"哪个接口要什么角色"在方法本地就能看到。
 */
@RestController
@RequestMapping("/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AdminAnnouncementService adminAnnouncementService;
    private final ClientIpUtil clientIpUtil;

    /** 列表：状态 / 分级 / 关键字可筛，每项带 effective 标识当下是否真的对用户可见 */
    @SaCheckRole("super_admin")
    @GetMapping
    public R<PageVO<AnnouncementVo.Manage>> page(AnnouncementQuery query) {
        return R.ok(adminAnnouncementService.page(query));
    }

    @SaCheckRole("super_admin")
    @GetMapping("/{id}")
    public R<AnnouncementVo.Manage> detail(@PathVariable Long id) {
        return R.ok(adminAnnouncementService.detail(id));
    }

    /** 新建：落为草稿，需再调 publish 才会对用户可见 */
    @SaCheckRole("super_admin")
    @PostMapping
    public R<Long> create(@RequestBody AdminDto.AnnouncementUpsertReq req,
                          HttpServletRequest request) {
        return R.ok(adminAnnouncementService.create(req, clientIpUtil.get(request)));
    }

    /** 修改：仅草稿 / 已撤回可改 */
    @SaCheckRole("super_admin")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody AdminDto.AnnouncementUpsertReq req,
                          HttpServletRequest request) {
        adminAnnouncementService.update(id, req, clientIpUtil.get(request));
        return R.ok();
    }

    /** 发布：草稿 / 已撤回 → 已发布 */
    @SaCheckRole("super_admin")
    @PutMapping("/{id}/publish")
    public R<Void> publish(@PathVariable Long id, HttpServletRequest request) {
        adminAnnouncementService.publish(id, clientIpUtil.get(request));
        return R.ok();
    }

    /** 撤回：已发布 → 已撤回（只改状态，不删数据） */
    @SaCheckRole("super_admin")
    @PutMapping("/{id}/recall")
    public R<Void> recall(@PathVariable Long id, HttpServletRequest request) {
        adminAnnouncementService.recall(id, clientIpUtil.get(request));
        return R.ok();
    }

    /** 删除：仅草稿 / 已撤回可删，物理删除 */
    @SaCheckRole("super_admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        adminAnnouncementService.delete(id, clientIpUtil.get(request));
        return R.ok();
    }
}
