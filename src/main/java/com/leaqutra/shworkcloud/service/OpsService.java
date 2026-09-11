package com.leaqutra.shworkcloud.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.entity.UploadSession;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.mapper.UploadSessionMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.AdminVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运维接口（仅超管）：孤儿对象清理、物化路径重算、机房清场、容量对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsService {

    /** 上传会话超过该时长仍未 commit 即视为孤儿 */
    private static final int STALE_HOURS = 24;

    private final UploadSessionMapper uploadSessionMapper;
    private final FileEntryMapper fileEntryMapper;
    private final UserMapper userMapper;
    private final OssSignService ossSignService;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final LoginUser loginUser;
    private final com.leaqutra.shworkcloud.config.AppProperties appProperties;
    private final OssReconcileService ossReconcileService;

    /**
     * 手动触发 OSS 与数据库对账：清理无人引用的垃圾对象。
     *
     * @param dryRun true 只统计不删除（建议先用它预览）
     */
    public OssReconcileService.ReconcileResult reconcileOss(boolean dryRun, String clientIp) {
        OssReconcileService.ReconcileResult result = ossReconcileService.reconcile(dryRun);
        auditService.log(loginUser.id(), dryRun ? "OSS_RECONCILE_DRYRUN" : "OSS_RECONCILE",
                "FILE", null, clientIp, true,
                "scanned=%d,deleted=%d".formatted(result.scanned(), result.deleted()));
        return result;
    }

    /** 当前关键配置摘要，便于运维确认"自助注册是否开启""默认配额多少" */
    public java.util.Map<String, Object> configSummary() {
        return java.util.Map.of(
                "registerEnabled", appProperties.getRegister().isEnabled(),
                "importStrategy", appProperties.getStudentImport().getStrategy(),
                "defaultQuotaBytes", appProperties.getQuota().getDefaultBytes(),
                "maxFileSizeBytes", appProperties.getUpload().getMaxFileSizeBytes(),
                "recycleRetentionDays", appProperties.getRecycle().getRetentionDays(),
                "internalNetworks", appProperties.getSecurity().getInternalNetworks(),
                "trustProxy", appProperties.getSecurity().isTrustProxy());
    }

    // ------------------------------------------------------------ 孤儿对象

    /**
     * 查看疑似孤儿对象。
     * <p>判定依据是「服务端已签发上传凭证，但超过 24 小时仍未 commit」。
     * 这些对象通常来自：学生传到一半关机、commit 请求失败、浏览器被强制关闭。
     */
    public List<AdminVo.OrphanVo> listOrphans(int limit) {
        LocalDateTime before = LocalDateTime.now().minusHours(STALE_HOURS);
        return uploadSessionMapper.selectStalePending(before, Math.max(1, Math.min(limit, 500)))
                .stream()
                .map(s -> new AdminVo.OrphanVo(s.getId(), s.getUserId(), s.getObjectKey(), s.getCreateTime()))
                .toList();
    }

    /** 清理孤儿对象：删 OSS 对象 + 标记会话已放弃 */
    public int cleanOrphans(int limit, String clientIp) {
        int cleaned = cleanOrphansInternal(limit);
        auditService.log(loginUser.id(), "ORPHAN_CLEAN", "SESSION", null, clientIp, true,
                "cleaned=" + cleaned);
        return cleaned;
    }

    /**
     * 无登录上下文版本的孤儿清理，供定时任务调用。
     * <p>定时任务里没有 Sa-Token 会话，调用 loginUser 会抛异常，因此必须分开。
     */
    public int cleanOrphansInternal(int limit) {
        LocalDateTime before = LocalDateTime.now().minusHours(STALE_HOURS);
        List<UploadSession> stale = uploadSessionMapper.selectStalePending(
                before, Math.max(1, Math.min(limit, 500)));
        int cleaned = 0;
        for (UploadSession session : stale) {
            // 防御性检查：万一已被 commit（有索引），绝不能删对象
            if (fileEntryMapper.countByObjectKey(session.getObjectKey()) > 0) {
                uploadSessionMapper.markAbandoned(session.getId());
                continue;
            }
            try {
                if (ossSignService.exists(session.getObjectKey())) {
                    ossSignService.delete(session.getObjectKey());
                }
                uploadSessionMapper.markAbandoned(session.getId());
                cleaned++;
                log.warn("回收孤儿对象 userId={} key={}", session.getUserId(), session.getObjectKey());
            } catch (Exception e) {
                log.error("回收孤儿对象失败 key={}", session.getObjectKey(), e);
            }
        }
        return cleaned;
    }

    public long countOrphans() {
        return uploadSessionMapper.countStalePending(LocalDateTime.now().minusHours(STALE_HOURS));
    }

    // ------------------------------------------------------------ 路径重算

    /**
     * 按 parent_id 重算全表物化路径（修复漂移）。
     * <p>会全表加载并逐条更新，请在维护窗口执行。
     */
    public int rebuildPaths(String clientIp) {
        List<FileEntry> all = fileEntryMapper.selectList(null);
        Map<Long, FileEntry> byId = new HashMap<>(all.size() * 2);
        for (FileEntry entry : all) {
            byId.put(entry.getId(), entry);
        }
        int fixed = 0;
        for (FileEntry entry : all) {
            String expected = expectedPath(entry, byId, new HashSet<>());
            if (!expected.equals(entry.getPath())) {
                fileEntryMapper.updatePathOnly(entry.getId(), expected);
                fixed++;
            }
        }
        auditService.log(loginUser.id(), "REBUILD_PATHS", "FILE", null, clientIp, true,
                "total=%d,fixed=%d".formatted(all.size(), fixed));
        log.warn("物化路径重算完成 total={} fixed={}", all.size(), fixed);
        return fixed;
    }

    /** 递归推导某条记录的祖先路径；遇到环或缺父时退化为根，避免栈溢出 */
    private String expectedPath(FileEntry entry, Map<Long, FileEntry> byId, Set<Long> visiting) {
        if (entry.getParentId() == null || entry.getParentId() == 0L) {
            return FilePaths.root();
        }
        if (!visiting.add(entry.getId())) {
            log.warn("检测到父子环，entryId={}", entry.getId());
            return FilePaths.root();
        }
        try {
            FileEntry parent = byId.get(entry.getParentId());
            if (parent == null || !parent.isFolderEntry()) {
                return FilePaths.root();
            }
            return expectedPath(parent, byId, visiting) + parent.getId() + "/";
        } finally {
            visiting.remove(entry.getId());
        }
    }

    // ------------------------------------------------------------ 机房清场

    /**
     * 按登录 IP 前缀批量踢出会话。
     * <p>用途：下课后把某个机房还登录着的会话全部清掉，
     * 防止下一批学生打开浏览器直接进入上一位同学的网盘。
     */
    public int flushSessions(String ipPrefix, String clientIp) {
        if (!StringUtils.hasText(ipPrefix) || ipPrefix.trim().length() < 5) {
            throw new com.leaqutra.shworkcloud.common.BizException(
                    com.leaqutra.shworkcloud.common.ErrorCode.BAD_PARAM,
                    "请提供至少 5 个字符的 IP 前缀，如 192.168.1.");
        }
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername)
                .likeRight(SysUser::getLastLoginIp, ipPrefix.trim()));
        int kicked = 0;
        for (SysUser user : users) {
            if (StpUtil.isLogin(user.getId())) {
                StpUtil.kickout(user.getId());
                kicked++;
            }
        }
        auditService.log(loginUser.id(), "SESSION_FLUSH", "SESSION", null, clientIp, true,
                "ipPrefix=%s,matched=%d,kicked=%d".formatted(ipPrefix, users.size(), kicked));
        return kicked;
    }

    // ------------------------------------------------------------ 容量对账

    /**
     * 全量容量对账：重算并修正 used_storage 缓存值。
     * <p>used_storage 是缓存值，权威值是 file_entry 的 SUM(size)
     * （活跃 + 回收站都占容量）。这里把漂移修回来。
     */
    public AdminVo.ReconcileVo reconcileStorage(String clientIp) {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId));
        int corrected = 0;
        long maxDiff = 0;
        for (SysUser user : users) {
            long diff = quotaService.recalculate(user.getId());
            if (diff != 0) {
                corrected++;
                maxDiff = Math.max(maxDiff, Math.abs(diff));
            }
        }
        auditService.log(loginUser.id(), "RECONCILE_STORAGE", "USER", null, clientIp, true,
                "checked=%d,corrected=%d".formatted(users.size(), corrected));
        return new AdminVo.ReconcileVo(users.size(), corrected, maxDiff);
    }
}
