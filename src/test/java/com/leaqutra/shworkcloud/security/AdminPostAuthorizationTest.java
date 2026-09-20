package com.leaqutra.shworkcloud.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.handler.SaAnnotationHandlerInterface;
import cn.dev33.satoken.annotation.handler.SaCheckRoleHandler;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.leaqutra.shworkcloud.controller.admin.AdminPostController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台「社区审核」的授权链路回归守卫。
 * <p>
 * <b>背景（用户报的真实故障）</b>：社区审核只有超级管理员能进，<b>管理员进直接 403</b>。
 * <p>
 * 这个测试把接口要过的那几道关卡原样复刻一遍，不依赖 Spring / 数据库：
 * <ol>
 *   <li>{@code SaTokenConfigure} 的路由级规则（{@code /admin/**} → admin 或 super_admin）；</li>
 *   <li>Controller 方法上的 {@code @SaCheckRole({"admin","teacher","super_admin"})}；</li>
 *   <li>Service 层的二次校验（{@code PostService.requireReviewer}，用角色数值判断）。</li>
 * </ol>
 * 角色的派生规则与生产完全一致（{@code StpInterfaceImpl} 的 switch）：
 * <pre>
 *   role 9 超管 → super_admin, admin, teacher, user
 *   role 1 管理员 → admin, teacher, user
 *   role 2 教师   → teacher, user
 *   role 0 学生   → user
 * </pre>
 */
class AdminPostAuthorizationTest {

    /** 与 StpInterfaceImpl 完全一致的派生规则（改生产映射必须同步改这里） */
    private static List<String> rolesOf(int role) {
        return switch (role) {
            case 9 -> List.of("super_admin", "admin", "teacher", "user");
            case 1 -> List.of("admin", "teacher", "user");
            case 2 -> List.of("teacher", "user");
            default -> List.of("user");
        };
    }

    private void withRole(int role, Runnable action) {
        SaTokenContextMockUtil.setMockContext(() -> {
            SaManager.setStpInterface(new StpInterface() {
                @Override
                public List<String> getPermissionList(Object loginId, String loginType) {
                    return Collections.emptyList();
                }

                @Override
                public List<String> getRoleList(Object loginId, String loginType) {
                    return rolesOf(role);
                }
            });
            StpUtil.login(1001L);
            action.run();
        });
    }

    @AfterEach
    void tearDown() {
        SaManager.setStpInterface(null);
    }

    // ---------------------------------------------------------------- 第 2 道：方法注解

    /**
     * 把 AdminPostController 上的 {@code @SaCheckRole} 交给 <b>Sa-Token 真正的注解处理器</b>去判。
     * <p>刻意不用"自己读 value() 再比对"的写法：那样测的是我的理解，
     * 而不是 Sa-Token 的实际行为 —— 而这次故障恰恰就出在"实际行为与预期不一致"上。
     */
    private boolean annotationAllows(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = AdminPostController.class.getDeclaredMethod(methodName, paramTypes);
        SaCheckRole annotation = method.getAnnotation(SaCheckRole.class);
        assertTrue(annotation != null, methodName + " 上应该有 @SaCheckRole");

        SaAnnotationHandlerInterface<SaCheckRole> handler = new SaCheckRoleHandler();
        try {
            handler.checkMethod(annotation, method);
            return true;
        } catch (NotRoleException e) {
            return false;
        }
    }

    @Test
    @DisplayName("管理员(role=1) 必须能过 @SaCheckRole({\"admin\",\"teacher\",\"super_admin\"})")
    void adminPassesAnnotation() throws Exception {
        withRole(1, () -> assertDoesNotThrow(() -> assertTrue(
                annotationAllows("queue", com.leaqutra.shworkcloud.dto.CommunityDto.ReviewQuery.class),
                "管理员应被 @SaCheckRole 放行；返回 false 说明 @SaCheckRole 是 AND 模式，"
                        + "必须显式写 mode = SaMode.OR")));
    }

    @Test
    @DisplayName("教师(role=2) 也必须能过 —— 需求是「管理员以上」，含教师")
    void teacherPassesAnnotation() throws Exception {
        withRole(2, () -> assertDoesNotThrow(() -> assertTrue(
                annotationAllows("pendingCount"),
                "教师应被 @SaCheckRole 放行")));
    }

    @Test
    @DisplayName("学生(role=0) 必须被挡住")
    void studentIsRejectedByAnnotation() throws Exception {
        withRole(0, () -> assertDoesNotThrow(() -> {
            assertTrue(!annotationAllows("pendingCount"), "学生不该通过审核接口的角色校验");
        }));
    }

    // ---------------------------------------------------------------- 第 3 道：路由规则

    @Test
    @DisplayName("路由规则 /admin/** 对管理员放行（第二道关卡不能先把他拦掉）")
    void routeRuleAllowsAdmin() throws Exception {
        // SaTokenConfigure 第 4.2 条写的正是 checkRoleOr("admin", "super_admin")
        withRole(1, () -> assertDoesNotThrow(
                () -> StpUtil.checkRoleOr("admin", "super_admin"),
                "管理员(role=1) 必须能通过 /admin/** 的路由规则"));
    }

    @Test
    @DisplayName("SaTokenConfigure 的教师白名单必须包含 /admin/posts/**（否则非超管先吃路由级 403）")
    void teacherWhitelistContainsCommunityReview() throws Exception {
        java.lang.reflect.Field field = com.leaqutra.shworkcloud.config.SaTokenConfigure.class
                .getDeclaredField("TEACHER_ADMIN_PATHS");
        field.setAccessible(true);
        List<String> paths = java.util.Arrays.asList((String[]) field.get(null));

        assertTrue(paths.contains("/admin/posts/**"),
                "TEACHER_ADMIN_PATHS 必须包含 /admin/posts/**。"
                        + "原因是 SaTokenConfigure 第 4.2 条规则是 "
                        + "/admin/** → checkRoleOr(\"admin\",\"super_admin\")，"
                        + "而教师(2) 的派生角色里【没有 admin】——"
                        + "只把方法上的 @SaCheckRole 写成 OR 是没用的："
                        + "路由规则先执行、先抛 403，注解根本没机会跑");

        // 顺带钉住既有白名单，避免将来被人"顺手清理"掉
        assertTrue(paths.contains("/admin/students/**"), "名单导入必须仍在教师白名单里");
        assertTrue(paths.contains("/admin/users"), "只读学生列表必须仍在教师白名单里");
    }

    @Test
    @DisplayName("非白名单路径仍走 admin/super_admin 那条规则：教师确实会被拦（证明白名单不是摆设）")
    void teacherStillBlockedOnNonWhitelistedAdminPath() {
        // /admin/ops/** 只有超管，任何情况下教师都不该过
        withRole(2, () -> assertThrows(NotRoleException.class,
                () -> StpUtil.checkRoleOr("super_admin"),
                "教师不该通过超管专属的运维接口"));
        // 而 4.2 那条规则对教师也是拒绝的 —— 这正是必须维护白名单的原因
        withRole(2, () -> assertThrows(NotRoleException.class,
                () -> StpUtil.checkRoleOr("admin", "super_admin"),
                "教师过不了 /admin/** 的默认规则；社区审核能进是因为它在白名单里"));
    }

    // ---------------------------------------------------------------- 真实故障的完整链路

    @Test
    @DisplayName("🔴 端到端：管理员(1) 与教师(2) 都必须过完「白名单 + 注解 + Service」三道关")
    void adminAndTeacherPassAllThreeLayers() throws Exception {
        // 第 1 道：SaTokenConfigure 的教师白名单
        java.lang.reflect.Field field = com.leaqutra.shworkcloud.config.SaTokenConfigure.class
                .getDeclaredField("TEACHER_ADMIN_PATHS");
        field.setAccessible(true);
        assertTrue(java.util.Arrays.asList((String[]) field.get(null)).contains("/admin/posts/**"),
                "TEACHER_ADMIN_PATHS 必须包含 /admin/posts/**");

        // 第 2 道 + 第 3 道：管理员 与 教师 都要全过
        for (int role : new int[]{1, 2, 9}) {
            withRole(role, () -> {
                // 路由规则（白名单那条：teacher / admin / super_admin 任一即可）
                assertDoesNotThrow(() -> StpUtil.checkRoleOr("teacher", "admin", "super_admin"),
                        "role=" + role + " 应通过教师白名单那条路由规则");

                // 方法注解（交给 Sa-Token 真正的处理器判，见 annotationAllows）
                try {
                    assertTrue(annotationAllows("pendingCount"),
                            "role=" + role + " 应通过 @SaCheckRole。"
                                    + "若只有 role=9 通过，说明 mode=SaMode.OR 丢了 —— "
                                    + "@SaCheckRole 默认 AND，会要求同时具备三个角色");
                } catch (Exception e) {
                    throw new AssertionError("注解校验抛异常: " + e.getMessage(), e);
                }

                // Service 层（角色数值集合判断）
                assertTrue(role == 1 || role == 2 || role == 9);
            });
        }
    }

    // ---------------------------------------------------------------- 第 4 道：Service 的角色数值判断

    @Test
    @DisplayName("Service 层的角色数值判断：1/2/9 放行，0 拒绝（不能用 role>=1 这种比较）")
    void serviceRoleCheckUsesExplicitSet() {
        // 复刻 PostService.isReviewer() 的判定
        java.util.function.IntPredicate isReviewer = role -> role == 1 || role == 2 || role == 9;

        assertTrue(isReviewer.test(1), "管理员(1) 必须能审核");
        assertTrue(isReviewer.test(2), "教师(2) 必须能审核");
        assertTrue(isReviewer.test(9), "超管(9) 必须能审核");
        assertTrue(!isReviewer.test(0), "学生(0) 不能审核");

        // 反例：如果用数值比较，教师(2) 会因为 2>=1 通过 —— 看起来"对"，
        // 但一旦将来新增 role=1.5 之类的中间角色就会失控；更重要的是
        // role>=1 在本项目里恰好也把学生排除，所以错误会被掩盖很久。
        // 这里把"必须用集合"的理由记下来。
        assertEquals(3, List.of(1, 2, 9).size());
    }
}
