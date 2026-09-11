package com.leaqutra.shworkcloud.security;

import cn.hutool.json.JSONUtil;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户状态短缓存。
 * <p>
 * 每个受保护请求都要判断「账号是否被禁用/删除」「是否需强制改密」，
 * 逐请求查库会成为热点，因此缓存 60 秒。
 * <p>
 * <b>缓存失效必须显式做</b>：禁用/重置密码/删除账号时调用 {@link #evict(long)}，
 * 否则最长会有 60 秒的窗口期内被禁用的人仍能操作。
 */
@Component
@RequiredArgsConstructor
public class UserCache {

    private static final String KEY_PREFIX = "uc:user:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    public void evict(long userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    /** 账号可用 = 存在 且 未删除 且 status=1 */
    public boolean isActive(long userId) {
        CachedUser cached = load(userId);
        return cached != null && cached.getStatus() != null && cached.getStatus() == 1;
    }

    public int getRole(long userId) {
        CachedUser cached = load(userId);
        return cached == null || cached.getRole() == null ? 0 : cached.getRole();
    }

    /** pwd_changed = 0 时需要强制改密 */
    public boolean needChangePassword(long userId) {
        CachedUser cached = load(userId);
        return cached != null && cached.getPwdChanged() != null && cached.getPwdChanged() == 0;
    }

    private CachedUser load(long userId) {
        String key = KEY_PREFIX + userId;
        String json = redis.opsForValue().get(key);
        if (json != null) {
            try {
                return JSONUtil.toBean(json, CachedUser.class);
            } catch (Exception ignored) {
                // 缓存内容损坏：删掉后回源，不影响主流程
                redis.delete(key);
            }
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        CachedUser cached = new CachedUser();
        cached.setId(user.getId());
        cached.setRole(user.getRole() == null ? 0 : user.getRole().intValue());
        cached.setStatus(user.getStatus() == null ? 0 : user.getStatus().intValue());
        cached.setDeleted(user.getDeleted() == null ? 0 : user.getDeleted().intValue());
        cached.setPwdChanged(user.getPwdChanged() == null ? 1 : user.getPwdChanged().intValue());
        redis.opsForValue().set(key, JSONUtil.toJsonStr(cached), TTL);
        return cached;
    }
}
