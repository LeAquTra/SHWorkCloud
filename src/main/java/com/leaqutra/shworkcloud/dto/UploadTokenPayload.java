package com.leaqutra.shworkcloud.dto;

import lombok.Data;

/**
 * 存 Redis 的一次性上传令牌载荷。
 * <p>
 * key: {@code upload:{uploadToken}}，value: 本对象的 JSON，TTL 5 分钟。
 * commit 时以这里的 {@code uploadKey} 为权威，忽略前端传入的任何 objectKey。
 */
@Data
public class UploadTokenPayload {

    private Long userId;
    private String uploadKey;
    private Long createdAt;

    public UploadTokenPayload() {
    }

    public UploadTokenPayload(Long userId, String uploadKey) {
        this.userId = userId;
        this.uploadKey = uploadKey;
        this.createdAt = System.currentTimeMillis();
    }
}
