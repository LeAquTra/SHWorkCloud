package com.leaqutra.shworkcloud.dto;

import lombok.Data;

/**
 * 验证码题库列表查询条件。
 */
@Data
public class CaptchaQuery {

    private Integer type;

    private Byte status;

    private long page = 1;
    private long size = 20;

    public long normalizedPage() {
        return page < 1 ? 1 : page;
    }

    public long normalizedSize() {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
