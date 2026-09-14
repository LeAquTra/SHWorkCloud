package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.dto.AuthDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.AuthService;
import com.leaqutra.shworkcloud.service.CaptchaRules;
import com.leaqutra.shworkcloud.service.CaptchaService;
import com.leaqutra.shworkcloud.service.EmailCodeService;
import com.leaqutra.shworkcloud.vo.AuthVo;
import com.leaqutra.shworkcloud.vo.CaptchaVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 * <p>注意：Controller 的映射路径<b>不含</b> {@code /api} —— 它由
 * {@code server.servlet.context-path} 统一提供。写成 {@code /api/auth} 会变成
 * {@code /api/api/auth}（这是 v1.1 文档最容易踩的坑）。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final EmailCodeService emailCodeService;
    private final ClientIpUtil clientIpUtil;
    private final AppProperties appProperties;

    /** 登录：学生直接填学号 */
    @PostMapping("/login")
    public R<AuthVo.LoginVo> login(@RequestBody AuthDto.LoginReq req, HttpServletRequest request) {
        return R.ok(authService.login(req, clientIpUtil.get(request)));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /** 修改密码（首登强制改密也走这里） */
    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody AuthDto.ChangePwdReq req) {
        authService.changePassword(req);
        return R.ok();
    }

    // -------------------------------------------------- 自助注册（可选通道）

    /**
     * 注册流程说明（公开接口）。
     * <p>让 API 调用方在动手之前就知道当前部署到底要求哪几步校验 ——
     * 否则调用方会困惑于"到底要不要先过图片验证码"。
     */
    @GetMapping("/register-config")
    public R<AuthVo.RegisterConfigVo> registerConfig() {
        AppProperties.Register register = appProperties.getRegister();
        String hint = !register.isEnabled()
                ? "自助注册已关闭，请使用学号登录或联系老师开户"
                : register.isMailEnabled()
                        ? "需先获取邮箱验证码，再提交注册"
                        : "服务端处于开发模式（mail-enabled=false），验证码只写服务端日志";
        return R.ok(new AuthVo.RegisterConfigVo(
                register.isEnabled(),
                // 这里给的是**生效值**：题库为空时会自动变成 false，
                // 前端据此决定要不要弹验证码窗口（详见 CaptchaRules）
                captchaService.required(CaptchaRules.Scope.REGISTER),
                register.isMailEnabled(),
                register.getEmailPattern(),
                hint));
    }

    /**
     * 人机验证配置（公开接口）。
     * <p>登录页在<b>用户还没登录时</b>就需要知道"点登录要不要先弹验证码"，
     * 所以这个接口必须公开。三个值都是生效值（题库为空自动为 false）。
     */
    @GetMapping("/human-check")
    public R<AuthVo.HumanCheckVo> humanCheck() {
        return R.ok(captchaService.humanCheck());
    }

    /** 获取图片验证码（题库为空时返回 40104） */
    @GetMapping("/captcha")
    public R<CaptchaVo.CaptchaVoBody> captcha(HttpServletRequest request) {
        return R.ok(captchaService.issue(clientIpUtil.get(request)));
    }

    @PostMapping("/captcha/verify")
    public R<CaptchaVo.CaptchaVerifyVo> verifyCaptcha(@RequestBody AuthDto.CaptchaVerifyReq req) {
        return R.ok(new CaptchaVo.CaptchaVerifyVo(captchaService.verify(req)));
    }

    /** 发送邮箱验证码：必须携带图片验证通过后拿到的一次性 passToken */
    @PostMapping("/email-code")
    public R<Void> sendEmailCode(@RequestBody AuthDto.EmailCodeReq req, HttpServletRequest request) {
        emailCodeService.sendRegisterCode(req.email(), req.captchaPassToken(), clientIpUtil.get(request));
        return R.ok();
    }

    @PostMapping("/register")
    public R<Void> register(@RequestBody AuthDto.RegisterReq req, HttpServletRequest request) {
        authService.register(req, clientIpUtil.get(request));
        return R.ok();
    }
}
