package com.leaqutra.shworkcloud.dto;

import lombok.Data;

/**
 * 目录列表查询条件。
 * <p>查询参数用 JavaBean（Spring MVC 需要无参构造 + setter），
 * 而不是 record —— record 无法从 query string 绑定。
 */
@Data
public class FileQuery {

    /** 0 = 根目录 */
    private Long parentId = 0L;

    /** 分类筛选：image / document / video / audio / other，留空为全部 */
    private String category;

    /** 关键字：前缀匹配（全模糊 LIKE '%x%' 无法命中索引） */
    private String keyword;

    private long page = 1;
    private long size = 50;

    public long normalizedPage() {
        return page < 1 ? 1 : page;
    }

    public long normalizedSize() {
        if (size < 1) {
            return 50;
        }
        return Math.min(size, 200);
    }
}
