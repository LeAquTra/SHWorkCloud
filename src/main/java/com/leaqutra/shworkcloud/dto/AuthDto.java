package com.leaqutra.shworkcloud.dto;

import java.util.List;

/**
 * 认证相关请求体。
 * <p>
 * 使用 record：本环境未引入 spring-boot-starter-validation，
 * 所以这里不做注解校验，规则集中在 Service 层手写（见 PasswordValidator 等）。
 */
public final class AuthDto {

    private AuthDto() {
    }

    /** 登录：学生直接用学号 */
    public record LoginReq(String login, String password) {
    }

    /** 修改密码（首登强制改密也走这个接口） */
    public record ChangePwdReq(String oldPassword, String newPassword, String confirmPassword) {
    }

    /**
     * 注册（可选辅通道）。
     * <p>注意不再要求传 captchaPassToken：它在「发送邮箱验证码」时已被一次性消费，
     * 注册时校验的是 Redis 中的 cap:pass:used:{email} 标记。
     */
    public record RegisterReq(String email, String emailCode, String password, String username) {
    }

    /** 点选型验证码的一个点击坐标（原图坐标系） */
    public record ClickPoint(int x, int y) {
    }

    /** 校验图片验证码作答 */
    public record CaptchaVerifyReq(String captchaId, String answer, List<ClickPoint> clicks) {
    }

    /** 发送邮箱验证码：必须携带图片验证通过后拿到的一次性 passToken */
    public record EmailCodeReq(String email, String captchaPassToken) {
    }
}
