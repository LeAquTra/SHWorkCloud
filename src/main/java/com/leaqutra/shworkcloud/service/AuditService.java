package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.entity.OperationLog;
import com.leaqutra.shworkcloud.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 操作审计。
 * <p>
 * 异步落库，避免拖慢上传/删除等主流程；审计失败绝不影响业务，
 * 因此这里把所有异常吞掉只记日志。
 * <p>
 * <b>禁止</b>把密码、Token、AK/SK 写进 {@code detail}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final OperationLogMapper operationLogMapper;

    @Async("auditExecutor")
    public void log(Long userId, String username, String action, String targetType,
                    String targetId, String ip, boolean success, String detail) {
        try {
            OperationLog entity = new OperationLog();
            entity.setUserId(userId);
            entity.setUsername(truncate(username, 50));
            entity.setAction(truncate(action, 64));
            entity.setTargetType(truncate(targetType, 32));
            entity.setTargetId(truncate(targetId, 64));
            entity.setDetail(truncate(detail, 1000));
            entity.setIp(truncate(ip, 64));
            entity.setSuccess((byte) (success ? 1 : 0));
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写审计日志失败 action={} targetId={} err={}", action, targetId, e.getMessage());
        }
    }

    public void log(Long userId, String action, String targetType, String targetId,
                    String ip, boolean success) {
        log(userId, null, action, targetType, targetId, ip, success, null);
    }

    /** 不显式传 username 的重载（username 允许为空，排查时可由 userId 反查） */
    public void log(Long userId, String action, String targetType, String targetId,
                    String ip, boolean success, String detail) {
        log(userId, null, action, targetType, targetId, ip, success, detail);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
