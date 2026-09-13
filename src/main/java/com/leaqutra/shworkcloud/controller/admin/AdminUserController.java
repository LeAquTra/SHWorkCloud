package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.AdminDto;
import com.leaqutra.shworkcloud.dto.AdminUserQuery;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.AdminUserService;
import com.leaqutra.shworkcloud.vo.AdminVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台用户管理。
 * <p>
 * <b>角色注解只能标在 Controller 方法上。</b> HandlerInterceptor 与 Sa-Token 的注解鉴权
 * 都只处理 Controller 层方法；标在 Service 上不会生效（v1.1 的后台裸奔正是这个原因）。
 * <p>这里与 {@code SaTokenConfigure} 的路由级规则形成两层防线。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final ClientIpUtil clientIpUtil;

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping
    public R<PageVO<AdminVo.AdminUserVo>> page(AdminUserQuery query) {
        return R.ok(adminUserService.page(query));
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/classes")
    public R<List<String>> classes() {
        return R.ok(adminUserService.classes());
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestBody AdminDto.StatusReq req,
                               HttpServletRequest request) {
        adminUserService.changeStatus(id, req.status(), clientIpUtil.get(request));
        return R.ok();
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/{id}/quota")
    public R<Void> updateQuota(@PathVariable Long id, @RequestBody AdminDto.QuotaReq req,
                              HttpServletRequest request) {
        adminUserService.updateQuota(id, req.quotaBytes(), clientIpUtil.get(request));
        return R.ok();
    }

    /** 教师即可重置学生密码：机房场景下这是学生忘记密码时的唯一找回路径 */
    @SaCheckRole(value = {"teacher", "admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/{id}/reset-password")
    public R<AdminVo.ResetPasswordVo> resetPassword(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(adminUserService.resetPassword(id, clientIpUtil.get(request)));
    }

    @SaCheckRole(value = {"teacher", "admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/reset-password-batch")
    public R<Integer> resetPasswordBatch(@RequestBody AdminDto.ResetPwdBatchReq req,
                                        HttpServletRequest request) {
        return R.ok(adminUserService.resetPasswordBatch(req.ids(), clientIpUtil.get(request)));
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/{id}/recalc-storage")
    public R<Long> recalcStorage(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(adminUserService.recalcStorage(id, clientIpUtil.get(request)));
    }

    /**
     * 代改用户资料（<b>部分更新</b>）。
     * <p>
     * 允许的键：{@code realName} / {@code studentNo} / {@code className} / {@code email} / {@code nickname}。
     * <ul>
     *   <li><b>不传的键不改动</b>；传空串表示<b>清空</b>该字段；</li>
     *   <li>学号与邮箱会做唯一性校验（学号同时是登录名，撞车会让人登错账号）；</li>
     *   <li>登录名 / 角色 / 状态 / 配额 / 密码 / 头像不能走这里，会返回明确提示。</li>
     * </ul>
     * <p>仅超管可调用；注意路由 {@code /{id}} 与 {@code /reset-password-batch} 不冲突
     * （Spring 会优先匹配字面量路径）。
     */
    @SaCheckRole("super_admin")
    @PutMapping("/{id}")
    public R<Void> updateProfile(@PathVariable Long id,
                                 @RequestBody Map<String, Object> body,
                                 HttpServletRequest request) {
        adminUserService.updateProfile(id, body, clientIpUtil.get(request));
        return R.ok();
    }

    /** 任命/撤销管理员或教师：仅超管 */
    @SaCheckRole("super_admin")
    @PutMapping("/{id}/role")
    public R<Void> changeRole(@PathVariable Long id, @RequestBody AdminDto.RoleReq req,
                             HttpServletRequest request) {
        adminUserService.changeRole(id, req.role(), clientIpUtil.get(request));
        return R.ok();
    }

    /** 删除账号（逻辑删除 + 踢下线 + 封禁）：仅超管 */
    @SaCheckRole("super_admin")
    @DeleteMapping("/{id}")
    public R<Void> deleteUser(@PathVariable Long id,
                             @RequestParam(defaultValue = "false") boolean purgeFiles,
                             HttpServletRequest request) {
        adminUserService.deleteUser(id, purgeFiles, clientIpUtil.get(request));
        return R.ok();
    }
}
