package com.leaqutra.shworkcloud.dto;

import lombok.Data;

/**
 * 存 Redis 的验证码答题会话。
 * <p>
 * <b>这个对象只存在于服务端</b>：包含标准答案，绝不会随响应下发给前端。
 * 用 JavaBean 而不是 record，因为要让 Hutool 的 JSON 反序列化可靠工作。
 */
@Data
public class CaptchaSession {

    private Long imageId;
    private Integer type;
    private String answer;
    private String dataJson;
    private Integer failLeft;

    public CaptchaSession() {
    }

    public CaptchaSession(Long imageId, Integer type, String answer, String dataJson, int maxFail) {
        this.imageId = imageId;
        this.type = type;
        this.answer = answer;
        this.dataJson = dataJson;
        this.failLeft = maxFail;
    }
}
