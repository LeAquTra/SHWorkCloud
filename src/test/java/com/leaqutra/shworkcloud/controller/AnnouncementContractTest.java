package com.leaqutra.shworkcloud.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.leaqutra.shworkcloud.controller.admin.AdminAnnouncementController;
import com.leaqutra.shworkcloud.vo.AnnouncementVo;
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
 * 公告的接口契约守卫。
 * <p>
 * 这里守的是两件**靠代码评审很容易漏、漏了后果又不小**的事：
 * <ol>
 *   <li>后台公告接口必须逐个标 {@code @SaCheckRole("super_admin")} ——
 *       {@code SaTokenConfigure} 对 {@code /admin/**} 只要求"admin 及以上"，
 *       少标一个注解，普通管理员（role=1）就能发布紧急公告、给全站弹窗；</li>
 *   <li>用户端视图不能下发 {@code status} / {@code createdBy} 等管理字段 ——
 *       草稿与已撤回的公告、以及"是谁发的"都不该被普通用户看到。</li>
 * </ol>
 * 与 {@code FileUrlContractTest} 同一思路：把口头约定变成会失败的测试。
 */
class AnnouncementContractTest {

    /** 只认这四个直接标注的映射注解（它们本身都带 @RequestMapping 元注解） */
    private static final List<Class<? extends java.lang.annotation.Annotation>> MAPPING_ANNOTATIONS =
            List.of(GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class);

    private static List<Method> endpointsOf(Class<?> controller) {
        List<Method> methods = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent)) {
                methods.add(method);
            }
        }
        return methods;
    }

    @Test
    @DisplayName("后台公告的每个接口都要求 super_admin（不能只靠 /admin/** 的路由规则）")
    void everyAdminEndpointRequiresSuperAdmin() {
        List<Method> endpoints = endpointsOf(AdminAnnouncementController.class);
        // 先把"接口数量"钉死：否则将来所有注解都被删掉时，这个循环会空转并通过
        assertEquals(7, endpoints.size(),
                "后台公告接口应为 7 个（列表/详情/新建/修改/发布/撤回/删除），"
                        + "数量变化时请同步更新本断言");

        for (Method method : endpoints) {
            SaCheckRole role = method.getAnnotation(SaCheckRole.class);
            assertNotNull(role, method.getName() + " 缺少 @SaCheckRole，普通管理员将可操作公告");
            assertArrayEquals(new String[]{"super_admin"}, role.value(),
                    method.getName() + " 的角色应为 super_admin");
        }
    }

    @Test
    @DisplayName("用户端公告视图不下发 status / createdBy 等管理字段")
    void activeViewDoesNotLeakManagementFields() {
        List<String> names = Arrays.stream(AnnouncementVo.Active.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("id", "title", "content", "level", "publishTime", "expireTime"), names,
                "用户端字段集合变化必须是有意为之：多一个字段就多一个信息泄露面");
        assertFalse(names.contains("status"), "草稿/已撤回状态不应下发给普通用户");
        assertFalse(names.contains("createdBy"), "发布人不应下发给普通用户");
        assertFalse(names.contains("effective"), "effective 是后台判定字段，用户端拿到的本来就都是生效的");
    }

    @Test
    @DisplayName("后台视图带 effective，管理员能一眼看出「发布了但没生效」")
    void manageViewCarriesEffective() {
        List<String> names = Arrays.stream(AnnouncementVo.Manage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertTrue(names.contains("effective"));
        assertTrue(names.contains("status"));
        assertTrue(names.contains("createdBy"));
        assertTrue(names.contains("createTime"));
        assertTrue(names.contains("updateTime"));
    }
}
