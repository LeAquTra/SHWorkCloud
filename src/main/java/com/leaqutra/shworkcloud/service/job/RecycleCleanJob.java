package com.leaqutra.shworkcloud.service.job;

import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收站过期清理：每日 03:00 把超过保留期的记录彻底删除。
 * <p>
 * 分批（每批 500）而不是一次全量：避免长事务与大批量 OSS 删除。
 * 按用户分组调用 {@link FileService#purgeAs(long, List)}，
 * 这样容量返还与 OSS 删除都复用与用户手工"彻底删除"完全相同的逻辑，
 * 不会出现两套清理代码互相不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleCleanJob {

    private static final int BATCH_SIZE = 500;
    /** 单次任务最多处理多少批，防止异常数据导致无限循环 */
    private static final int MAX_ROUNDS = 200;

    private final FileEntryMapper fileEntryMapper;
    private final FileService fileService;
    private final AppProperties appProperties;

    @Scheduled(cron = "0 0 3 * * ?")
    public void run() {
        int retentionDays = appProperties.getRecycle().getRetentionDays();
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        int purged = 0;
        int rounds = 0;

        while (rounds++ < MAX_ROUNDS) {
            List<FileEntry> expired = fileEntryMapper.selectExpiredRecycled(before, BATCH_SIZE);
            if (expired.isEmpty()) {
                break;
            }
            Map<Long, List<Long>> byUser = new LinkedHashMap<>();
            for (FileEntry entry : expired) {
                byUser.computeIfAbsent(entry.getUserId(), k -> new ArrayList<>()).add(entry.getId());
            }
            for (Map.Entry<Long, List<Long>> group : byUser.entrySet()) {
                try {
                    purged += fileService.purgeAs(group.getKey(), group.getValue());
                } catch (Exception e) {
                    log.error("回收站清理失败 userId={} count={}", group.getKey(), group.getValue().size(), e);
                }
            }
        }

        if (purged > 0) {
            log.info("回收站过期清理完成 retentionDays={} purged={}", retentionDays, purged);
        }
    }
}
