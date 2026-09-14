package com.leaqutra.shworkcloud.dto;

import lombok.Data;

/**
 * 后台公告列表查询条件。
 */
@Data
public class AnnouncementQuery {

    /** 关键字：标题/正文模糊匹配 */
    private String keyword;

    /** 0 草稿 / 1 已发布 / 2 已撤回；不传表示全部 */
    private Integer status;

    /** 注意力分级：1/2/3 */
    private Integer level;

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
