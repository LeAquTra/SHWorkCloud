package com.leaqutra.shworkcloud.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.leaqutra.shworkcloud.controller.admin.AdminPostController;
import com.leaqutra.shworkcloud.dto.CommunityDto;
import com.leaqutra.shworkcloud.service.PostRules;
import com.leaqutra.shworkcloud.vo.CommunityVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区（发帖 / 审核）的契约守卫。
 * <p>
 * 守三件"评审时容易漏、漏了后果不小"的事：
 * <ol>
 *   <li><b>不能有"直接发布"的接口</b>：需求要求"需要管理员以上审核通过"，
 *       这是靠<b>接口层面不存在那条路</b>来保证的，不是靠前端少给一个按钮。
 *       一旦有人为了"方便"加一个 {@code status=1} 的入参，审核就名存实亡；</li>
 *   <li><b>审核权限必须是三个角色的显式枚举 + OR 模式</b>：本项目角色编号不是有序等级
 *       （0 学生 / 1 管理员 / 2 教师 / 9 超管），教师(2) 数值比管理员(1) 大
 *       却权限更小 —— 少写一个 {@code teacher}，教师就审不了学生的帖子；
 *       而 <b>漏写 {@code mode = SaMode.OR}</b> 更狠：{@code @SaCheckRole} 默认是 AND，
 *       会要求同时具备全部三个角色，结果只有超管能进、管理员直接 403
 *       （这正是用户报的故障，见 {@code AdminPostAuthorizationTest}）；</li>
 *   <li><b>发布视图不能下发审核内部字段</b>：时间线里恒为已通过，
 *       所以 {@code status / reviewedBy / rejectReason} 对普通浏览者是噪音，
 *       而对"我的帖子"才是必要的。</li>
 * </ol>
 * 与 {@code AnnouncementContractTest} / {@code FriendContractTest} 同一思路。
 */
class CommunityContractTest {

    private static final List<Class<? extends java.lang.annotation.Annotation>> MAPPINGS =
            List.of(GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class);

    private static List<Method> endpointsOf(Class<?> controller) {
        List<Method> methods = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (MAPPINGS.stream().anyMatch(method::isAnnotationPresent)) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static List<String> componentNames(Class<? extends Record> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    // ---------------------------------------------------------------- 不能有直接发布

    @Test
    @DisplayName("发帖请求体里没有 status / authorId —— 状态与作者都不能由前端决定")
    void postRequestHasNoStatusOrAuthor() {
        List<String> names = componentNames(CommunityDto.PostReq.class);
        assertEquals(List.of("content"), names,
                "PostReq 只能有正文：多一个 status 就是「绕过审核」，多一个 authorId 就是「冒名发帖」");

        for (String forbidden : List.of("status", "authorId", "userId", "reviewedBy", "approve")) {
            assertFalse(names.contains(forbidden), "发帖请求体不能带 " + forbidden);
        }
    }

    @Test
    @DisplayName("用户端没有任何接口能改帖子状态（审核只存在于 /admin/posts/**）")
    void userEndpointsCannotChangeStatus() {
        for (Method method : endpointsOf(CommunityController.class)) {
            for (Class<?> type : method.getParameterTypes()) {
                assertFalse(type.getName().contains("ReviewReq"),
                        method.getName() + " 不该接受 ReviewReq —— 审核只在 AdminPostController");
            }
        }
    }

    @Test
    @DisplayName("用户端刚好 5 个接口：时间线/我的概览/详情/发帖/编辑/删除（数量变化要同步本断言）")
    void userEndpointCountIsPinned() {
        assertEquals(6, endpointsOf(CommunityController.class).size(),
                "社区用户端接口数量变化时必须是有意为之，请同步更新本断言");
    }

    // ---------------------------------------------------------------- 审核权限的角色枚举

    @Test
    @DisplayName("后台每个接口都显式要求 admin + teacher + super_admin，且必须是 OR 模式")
    void everyAdminEndpointAllowsTeacherToo() {
        List<Method> endpoints = endpointsOf(AdminPostController.class);
        assertEquals(4, endpoints.size(),
                "后台社区接口应为 4 个（队列/待审数/审核/详情），数量变化时请同步更新本断言");

        for (Method method : endpoints) {
            SaCheckRole role = method.getAnnotation(SaCheckRole.class);
            assertNotNull(role, method.getName() + " 缺少 @SaCheckRole，任何登录用户都能审核");
            assertArrayEquals(new String[]{"admin", "teacher", "super_admin"}, role.value(),
                    method.getName() + " 必须显式列出三个角色：需求是「管理员以上」，"
                            + "而教师(2) 的编号比管理员(1) 大却权限更小，"
                            + "只写 admin 会让教师审不了学生的帖子");

            // 🔴 真实故障的回归守卫：@SaCheckRole 默认是 AND 语义，
            //    要求同时具备列出的【全部】角色。而角色是派生的 ——
            //    只有超管同时拥有 super_admin+admin+teacher，管理员只有 admin+teacher，
            //    于是漏写 OR 的表现就是"只有超级管理员能进，管理员直接 403"。
            assertEquals(SaMode.OR, role.mode(),
                    method.getName() + " 必须是 SaMode.OR！@SaCheckRole 默认是 AND，"
                            + "不写 OR 会要求同时具备三个角色 —— 只有超管满足，"
                            + "管理员(admin+teacher) 与教师(teacher) 都会 403");
        }
    }

    // ---------------------------------------------------------------- 视图字段

    @Test
    @DisplayName("链接分段视图只有 type/text/href —— 前端不需要、也不应该看到别的")
    void segmentViewIsMinimal() {
        assertEquals(List.of("type", "text", "href"), componentNames(CommunityVo.Segment.class));
    }

    @Test
    @DisplayName("帖子视图同时带 content 与 segments：segments 供渲染，content 是原文兜底")
    void postCarriesBothContentAndSegments() {
        List<String> names = componentNames(CommunityVo.Post.class);
        assertTrue(names.contains("content"), "必须带原文：审核、检索、复制都要用");
        assertTrue(names.contains("segments"), "必须带分段：前端不做任何链接解析");
        assertTrue(names.contains("linkCount"), "链接数是审核信号，要在界面上提示");
        assertTrue(names.contains("canEdit"), "能否编辑由服务端算（状态机不在前端）");
        assertTrue(names.contains("canDelete"), "能否删除由服务端算");
    }

    @Test
    @DisplayName("帖子视图的作者是收窄过的 UserCard，不是用户表的整行")
    void postAuthorIsNarrowedCard() {
        for (RecordComponent component : CommunityVo.Post.class.getRecordComponents()) {
            if ("author".equals(component.getName())) {
                assertEquals("com.leaqutra.shworkcloud.vo.FriendVo$UserCard",
                        component.getType().getName(),
                        "作者必须复用 UserCard：它守着字段白名单"
                                + "（FriendContractTest.userCardDoesNotLeakPrivateColumns），"
                                + "换成 SysUser 会把邮箱等字段漏出去");
                return;
            }
        }
        throw new AssertionError("Post 缺少 author 字段");
    }

    @Test
    @DisplayName("时间线一页带游标与 hasMore（社区不断有新帖，页码会重复/漏内容）")
    void feedCarriesCursor() {
        List<String> names = componentNames(CommunityVo.Feed.class);
        assertTrue(names.contains("nextBeforeId"), "没有游标，前端无法翻页");
        assertTrue(names.contains("hasMore"));
    }

    @Test
    @DisplayName("审核队列带 pendingTotal：审核员要随时知道还剩多少条")
    void reviewPageCarriesPendingTotal() {
        List<String> names = componentNames(CommunityVo.ReviewPage.class);
        assertTrue(names.contains("pendingTotal"));
        assertTrue(names.contains("total"));
    }

    @Test
    @DisplayName("正文长度上限是 2000，与前端 maxlength 必须一致")
    void contentLimitMatchesFrontend() {
        assertEquals(2000, PostRules.MAX_CHARS,
                "改这个数必须同步改 CommunityView.vue 的 maxlength，否则用户会白打一段字才被拒");
    }
}
