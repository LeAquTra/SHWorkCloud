package com.leaqutra.shworkcloud.security;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 登录上下文。
 * <p>
 * 取代 v1.1 的 {@code ThreadLocal UserContext}：userId 与 role 一律从 Sa-Token 会话
 * 与服务端缓存解析，<b>绝不接受前端传入的 userId</b>。
 */
@Component
@RequiredArgsConstructor
public class LoginUser {

    private final UserCache userCache;

    /** 当前登录用户 ID；未登录时抛 NotLoginException，由全局异常处理器转为 40100 */
    public long id() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 当前用户角色（数字，权威值）。
     * 注意这里查的是服务端缓存/数据库，不是从 token 里解析出来的。
     */
    public int role() {
        return userCache.getRole(id());
    }

    public boolean isSuperAdmin() {
        return role() == 9;
    }
}
