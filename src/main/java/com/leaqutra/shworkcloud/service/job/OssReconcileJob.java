package com.leaqutra.shworkcloud.service.job;

import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.service.OssReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OSS 与数据库对账（每日 06:00）：清理无人引用的垃圾对象，保证 Bucket 整洁。
 * <p>
 * 已覆盖的三类残留：
 * <ul>
 *   <li>用户文件：直传成功但 commit 失败、进程被强杀、删除时 OSS 调用失败；</li>
 *   <li>头像：换头像时旧对象没删掉；</li>
 *   <li>验证码图：删题时 OSS 调用失败。</li>
 * </ul>
 * 统一有 24 小时宽限期，绝不碰"刚签发凭证、正在上传"的对象。
 * <p>可通过 {@code app.storage.reconcile.enabled} 关闭；
 * 也可由超管调 {@code POST /api/admin/ops/oss-reconcile?dryRun=true} 手动预览。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssReconcileJob {

    private final OssReconcileService ossReconcileService;
    private final AppProperties appProperties;

    @Scheduled(cron = "0 0 6 * * ?")
    public void run() {
        if (!appProperties.getStorage().getReconcile().isEnabled()) {
            log.debug("OSS 对账任务已关闭（app.storage.reconcile.enabled=false）");
            return;
        }
        try {
            OssReconcileService.ReconcileResult result = ossReconcileService.reconcile(false);
            if (result.deleted() > 0) {
                log.warn("OSS 对账清理完成：{}", result.describe());
            }
        } catch (Exception e) {
            // 对账失败不能影响其它任务；下次继续
            log.error("OSS 对账任务执行失败", e);
        }
    }
}
