package com.leaqutra.shworkcloud.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台管理相关响应体。
 */
public final class AdminVo {

    private AdminVo() {
    }

    /** 用户列表项：不含密码等敏感字段 */
    public record AdminUserVo(Long id, String username, String studentNo, String realName,
                              String className, String email, Integer role, Integer status,
                              long quota, long used, LocalDateTime lastLoginTime,
                              String lastLoginIp, LocalDateTime createTime) {
    }

    /** 新建账号时一次性返回初始密码，前端必须提示「仅显示一次」 */
    public record ResetPasswordVo(Long userId, String username, String initialPassword) {
    }

    public record ImportFailureVo(int row, String studentNo, String type, String reason) {
    }

    public record ImportResultVo(int total, int success, int skipped, int failed,
                                 boolean defaultPasswordUsed, String initialPassword,
                                 List<ImportFailureVo> failures) {
    }

    /** 孤儿对象（OSS 有对象但数据库无索引） */
    public record OrphanVo(Long sessionId, Long userId, String objectKey,
                           LocalDateTime createTime) {
    }

    public record ReconcileVo(int checkedUsers, int correctedUsers, long maxDiffBytes) {
    }

    public record SessionFlushVo(int kickedCount) {
    }
}
