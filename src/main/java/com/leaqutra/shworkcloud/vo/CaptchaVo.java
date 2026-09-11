package com.leaqutra.shworkcloud.vo;

import java.util.List;

/**
 * 图片验证码响应体。
 * <p><b>绝不含答案</b>：只下发签名图片 URL、题目 ID、题型、尺寸与可展示的提示信息。
 */
public final class CaptchaVo {

    private CaptchaVo() {
    }

    /**
     * @param captchaId  答题会话 ID（服务端 Redis 里对应一份含答案的会话）
     * @param type       1 字符输入 / 2 单选 / 3 点选
     * @param imageUrl   5 分钟有效的 OSS 签名 URL
     * @param prompts    题型 2 为选项标签列表；题型 3 为需要点击的文字标签（顺序已被打乱）
     */
    public record CaptchaVoBody(String captchaId, Integer type, String imageUrl,
                                Integer width, Integer height, List<String> prompts) {
    }

    public record CaptchaVerifyVo(String captchaPassToken) {
    }
}
