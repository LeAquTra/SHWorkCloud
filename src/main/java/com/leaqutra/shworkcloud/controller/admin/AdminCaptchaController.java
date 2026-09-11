package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.AdminDto;
import com.leaqutra.shworkcloud.dto.CaptchaQuery;
import com.leaqutra.shworkcloud.entity.CaptchaImage;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.AdminCaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 后台验证码题库管理。
 * <p>列表会返回答案与标注数据 —— 这是后台专用视图，普通用户永远拿不到。
 */
@RestController
@RequestMapping("/admin/captchas")
@RequiredArgsConstructor
public class AdminCaptchaController {

    private final AdminCaptchaService adminCaptchaService;
    private final ClientIpUtil clientIpUtil;

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping
    public R<PageVO<CaptchaImage>> page(CaptchaQuery query) {
        return R.ok(adminCaptchaService.page(query));
    }

    /** 题库概览：题库为空时注册通道不可用，后台需要醒目提示 */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/summary")
    public R<Map<String, Long>> summary() {
        return R.ok(Map.of(
                "enabled", adminCaptchaService.countEnabled(),
                "total", adminCaptchaService.countAll()));
    }

    /**
     * 上传题目：图片由服务端写入 OSS captcha/ 前缀，同时落库答案与标注数据。
     * <p>用 multipart 同时提交文件与 JSON 字段，避免前端把二进制塞进 JSON。
     */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Long> upload(@RequestPart("file") MultipartFile file,
                          @RequestParam Integer type,
                          @RequestParam String answer,
                          @RequestParam(required = false) String dataJson,
                          @RequestParam(required = false) Integer width,
                          @RequestParam(required = false) Integer height,
                          @RequestParam(required = false) Integer weight,
                          @RequestParam(required = false) String remark,
                          HttpServletRequest request) {
        AdminDto.CaptchaUpsertReq req = new AdminDto.CaptchaUpsertReq(
                type, answer, dataJson, width, height, weight, remark);
        return R.ok(adminCaptchaService.upload(file, req, clientIpUtil.get(request)));
    }

    /** 只改图片数据（答案/标注/权重/备注），不重传图片 */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody AdminDto.CaptchaUpsertReq req,
                         HttpServletRequest request) {
        adminCaptchaService.update(id, req, clientIpUtil.get(request));
        return R.ok();
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PutMapping("/{id}/status")
    public R<Void> toggleStatus(@PathVariable Long id, @RequestBody AdminDto.StatusReq req,
                               HttpServletRequest request) {
        adminCaptchaService.toggleStatus(id, req.status(), clientIpUtil.get(request));
        return R.ok();
    }

    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        adminCaptchaService.delete(id, clientIpUtil.get(request));
        return R.ok();
    }
}
