package com.leaqutra.shworkcloud.dto;

import lombok.Data;

/**
 * 后台用户列表查询条件。
 */
@Data
public class AdminUserQuery {

    /** 学号 / 姓名 / 邮箱 关键字 */
    private String keyword;

    private String className;

    private Byte role;

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
        return Math.min(size, 200);
    }
}
