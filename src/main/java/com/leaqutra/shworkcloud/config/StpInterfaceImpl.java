package com.leaqutra.shworkcloud.config;

import cn.dev33.satoken.stp.StpInterface;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色数据源。
 * <p>
 * <b>这个类不能省。</b> 缺少 {@code StpInterface} 实现时，Sa-Token 取不到任何角色，
 * {@code StpUtil.checkRole}/{@code checkRoleOr} 会一律判定「无角色」，
 * 结果是所有 /admin/** 接口对所有人返回 403，后台完全不可用。
 * <p>
 * 约定：数据库 {@code sys_user.role} 的数字是权威值，Sa-Token 的角色标识是派生值。
 * 映射关系见文档 §1.6：
 * <pre>
 *   9 超级管理员 -> super_admin, admin, teacher, user
 *   1 管理员     -> admin, teacher, user
 *   2 教师       -> teacher, user
 *   0 学生       -> user
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SysUser user = userMapper.selectById(Long.valueOf(loginId.toString()));
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            return Collections.emptyList();
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            return Collections.emptyList();
        }
        return switch (user.getRole() == null ? 0 : user.getRole()) {
            case 9 -> List.of("super_admin", "admin", "teacher", "user");
            case 1 -> List.of("admin", "teacher", "user");
            case 2 -> List.of("teacher", "user");
            default -> List.of("user");
        };
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 本项目用角色控制即可，不使用细粒度权限码
        return Collections.emptyList();
    }
}
