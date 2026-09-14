package com.leaqutra.shworkcloud.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.security.UserCache;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器与后台路由分级授权。
 * <p>
 * 重要约定：
 * <ol>
 *   <li>SaRouter 匹配的路径**不含** {@code server.servlet.context-path}。
 *       本项目 context-path 为 {@code /api}，因此这里写 {@code /auth/login}
 *       而不是 {@code /api/auth/login}。</li>
 *   <li>角色注解（{@code @SaCheckRole}）只能标注在 <b>Controller</b> 方法上。
 *       HandlerInterceptor 与 Sa-Token 注解鉴权都只处理 Controller 层方法，
 *       标在 Service 上不会生效 —— 这一点是 v1.1 文档的越权缺陷根因。</li>
 *   <li>路由级规则与注解级规则是两层防线，二者都要有。</li>
 * </ol>
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    /** 无需登录即可访问的路径 */
    private static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/auth/register-config",       // 登录页要在“还没有 token”时就知道注册开没开，必须公开
            "/auth/human-check",           // 同上：登录页要先知道“点登录要不要弹验证码”
            "/auth/captcha",
            "/auth/captcha/verify",
            "/auth/email-code",
            "/auth/register",
            "/actuator/health",
            "/error"
    };

    /** 首登强制改密时仍需放行的路径（否则学生无法完成改密，会陷入死循环） */
    private static final String[] FORCE_PWD_EXEMPT = {
            "/auth/**",
            "/user/profile",
            "/actuator/health",
            "/error"
    };

    /** 教师（机房管理员）可访问的后台接口 */
    private static final String[] TEACHER_ADMIN_PATHS = {
            "/admin/students/**",
            "/admin/users",                        // 只读列表；写操作由注解再收窄
            "/admin/users/*/reset-password",
            "/admin/users/reset-password-batch"
    };

    private final UserCache userCache;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {

            // 1) 登录校验
            SaRouter.match("/**")
                    .notMatch(PUBLIC_PATHS)
                    .check(r -> StpUtil.checkLogin());

            // 2) 账号状态二次校验：禁用/逻辑删除后立即失效，无需等 token 过期
            SaRouter.match("/**")
                    .notMatch(PUBLIC_PATHS)
                    .check(r -> {
                        long userId = StpUtil.getLoginIdAsLong();
                        if (!userCache.isActive(userId)) {
                            StpUtil.logout(userId);
                            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
                        }
                    });

            // 3) 首登/重置密码后强制改密：除白名单外全部拦截
            SaRouter.match("/**")
                    .notMatch(FORCE_PWD_EXEMPT)
                    .check(r -> {
                        if (userCache.needChangePassword(StpUtil.getLoginIdAsLong())) {
                            throw new BizException(ErrorCode.MUST_CHANGE_PASSWORD);
                        }
                    });

            // 4) 后台路由分级授权
            // 4.1 教师（机房管理员）可用：名单导入、查看学生列表、重置学生密码
            SaRouter.match(TEACHER_ADMIN_PATHS)
                    .check(r -> StpUtil.checkRoleOr("teacher", "admin", "super_admin"));

            // 4.2 其余后台接口：管理员及以上
            SaRouter.match("/admin/**")
                    .notMatch(TEACHER_ADMIN_PATHS)
                    .check(r -> StpUtil.checkRoleOr("admin", "super_admin"));

            // 4.3 运维接口：仅超管
            SaRouter.match("/admin/ops/**")
                    .check(r -> StpUtil.checkRoleOr("super_admin"));

            // 说明：PUT /admin/users/{id}/role 与 DELETE /admin/users/{id} 的「仅超管」
            // 由对应 Controller 方法上的 @SaCheckRole("super_admin") 精确控制。
            // 不要在这里用 /admin/users/* 通配，否则将来新增 GET /admin/users/{id}
            // 会被误伤为超管专属。

        })).addPathPatterns("/**")
                .excludePathPatterns("/error", "/actuator/health");
    }
}
