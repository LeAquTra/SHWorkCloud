package com.leaqutra.shworkcloud.service.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 容量对账：每日 05:00 按 file_entry 重算并修正 sys_user.used_storage。
 * <p>
 * 为什么需要它：{@code used_storage} 是增量维护的<b>缓存值</b>。
 * 历史 bug、并发提交、异常中断都可能让它漂移；漂移后用户会看到
 * "文件都删光了但还占着 8GB" 这种无法自愈的状态。
 * 权威值始终是 {@code SUM(size) WHERE status IN (0,1)}（回收站也占容量）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageReconcileJob {

    private static final int PAGE_SIZE = 500;

    private final UserMapper userMapper;
    private final QuotaService quotaService;

    @Scheduled(cron = "0 0 5 * * ?")
    public void run() {
        int checked = 0;
        int corrected = 0;
        long maxDiff = 0;
        long lastId = 0;

        while (true) {
            List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .select(SysUser::getId)
                    .gt(SysUser::getId, lastId)
                    .orderByAsc(SysUser::getId)
                    .last("LIMIT " + PAGE_SIZE));
            if (users.isEmpty()) {
                break;
            }
            for (SysUser user : users) {
                checked++;
                lastId = user.getId();
                try {
                    long diff = quotaService.recalculate(user.getId());
                    if (diff != 0) {
                        corrected++;
                        maxDiff = Math.max(maxDiff, Math.abs(diff));
                    }
                } catch (Exception e) {
                    log.error("容量对账失败 userId={}", user.getId(), e);
                }
            }
            if (users.size() < PAGE_SIZE) {
                break;
            }
        }

        if (corrected > 0) {
            log.warn("容量对账完成 checked={} corrected={} maxDiffBytes={}", checked, corrected, maxDiff);
        } else {
            log.info("容量对账完成 checked={} 无需修正", checked);
        }
    }
}
