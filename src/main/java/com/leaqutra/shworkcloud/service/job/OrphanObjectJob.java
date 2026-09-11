package com.leaqutra.shworkcloud.service.job;

import com.leaqutra.shworkcloud.service.OpsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 孤儿对象回收：每日 04:00 清理"已签发上传凭证但超过 24 小时未 commit"的 OSS 对象。
 * <p>
 * 这类对象来自机房最常见的场景：学生传到一半下课关机、commit 请求失败、浏览器被强杀。
 * 它们不占用户配额、在网盘里也看不见，但会一直产生 OSS 存储费用。
 * <p>
 * 注意：<b>未完成分片无法在这里清理</b>（OSS 不能全局枚举 uploadId），
 * 必须依赖 Bucket 的 AbortIncompleteMultipartUpload 生命周期规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanObjectJob {

    private static final int BATCH_SIZE = 200;
    private static final int MAX_ROUNDS = 50;

    private final OpsService opsService;

    @Scheduled(cron = "0 0 4 * * ?")
    public void run() {
        int total = 0;
        for (int i = 0; i < MAX_ROUNDS; i++) {
            int cleaned = opsService.cleanOrphansInternal(BATCH_SIZE);
            total += cleaned;
            if (cleaned < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.warn("孤儿对象回收完成 cleaned={}", total);
        }
    }
}
