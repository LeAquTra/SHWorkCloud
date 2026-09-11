package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.AdminDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.OpsService;
import com.leaqutra.shworkcloud.service.OssReconcileService;
import com.leaqutra.shworkcloud.vo.AdminVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维接口（仅超管）：孤儿对象、路径重算、机房清场、容量对账。
 */
@RestController
@RequestMapping("/admin/ops")
@RequiredArgsConstructor
public class AdminOpsController {

    private final OpsService opsService;
    private final ClientIpUtil clientIpUtil;

    /** 疑似孤儿对象（已签发上传凭证但 24 小时未 commit） */
    @SaCheckRole("super_admin")
    @GetMapping("/orphan-objects")
    public R<List<AdminVo.OrphanVo>> orphans(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(opsService.listOrphans(limit));
    }

    @SaCheckRole("super_admin")
    @GetMapping("/orphan-objects/count")
    public R<Long> orphanCount() {
        return R.ok(opsService.countOrphans());
    }

    @SaCheckRole("super_admin")
    @PostMapping("/orphan-objects/clean")
    public R<Integer> cleanOrphans(@RequestParam(defaultValue = "200") int limit,
                                  HttpServletRequest request) {
        return R.ok(opsService.cleanOrphans(limit, clientIpUtil.get(request)));
    }

    /** 按 parent_id 重算物化路径（维护窗口执行） */
    @SaCheckRole("super_admin")
    @PostMapping("/rebuild-paths")
    public R<Integer> rebuildPaths(HttpServletRequest request) {
        return R.ok(opsService.rebuildPaths(clientIpUtil.get(request)));
    }

    /** 机房清场：按登录 IP 前缀批量踢出会话 */
    @SaCheckRole("super_admin")
    @PostMapping("/sessions/flush")
    public R<AdminVo.SessionFlushVo> flushSessions(@RequestBody AdminDto.SessionFlushReq req,
                                                  HttpServletRequest request) {
        int kicked = opsService.flushSessions(req.ipPrefix(), clientIpUtil.get(request));
        return R.ok(new AdminVo.SessionFlushVo(kicked));
    }

    /** 全量容量对账 */
    @SaCheckRole("super_admin")
    @PostMapping("/reconcile-storage")
    public R<AdminVo.ReconcileVo> reconcileStorage(HttpServletRequest request) {
        return R.ok(opsService.reconcileStorage(clientIpUtil.get(request)));
    }

    /**
     * OSS 与数据库对账：清理无人引用的垃圾对象（用户文件孤儿 / 过期头像 / 验证码孤儿图）。
     * <p>建议先用 dryRun=true 预览，确认数量合理再正式执行。
     */
    @SaCheckRole("super_admin")
    @PostMapping("/oss-reconcile")
    public R<Map<String, Object>> ossReconcile(@RequestParam(defaultValue = "true") boolean dryRun,
                                            HttpServletRequest request) {
        OssReconcileService.ReconcileResult result =
                opsService.reconcileOss(dryRun, clientIpUtil.get(request));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("dryRun", dryRun);
        data.put("scanned", result.scanned());
        data.put("deleted", result.deleted());
        data.put("kept", result.kept());
        data.put("truncated", result.truncated());
        data.put("message", result.describe());
        return R.ok(data);
    }

    /** 当前配置摘要（便于确认 register 是否开启、配额默认值等） */
    @SaCheckRole("super_admin")
    @GetMapping("/config-summary")
    public R<Map<String, Object>> configSummary() {
        return R.ok(opsService.configSummary());
    }
}
