package com.leaqutra.shworkcloud.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.function.Function;

/**
 * 分页返回体。
 * <p>
 * 不直接返回 MyBatis-Plus 的 {@code IPage}：它的实现类带有较多内部字段，
 * 直接序列化会把 orders/optimizeCountSql/searchCount 等实现细节暴露给前端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {

    private long page;
    private long size;
    private long total;
    private List<T> records;

    public static <T> PageVO<T> of(IPage<T> page) {
        return new PageVO<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 把实体分页映射为 VO 分页 */
    public static <E, T> PageVO<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageVO<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(mapper).toList());
    }

    public static <T> PageVO<T> of(long page, long size, long total, List<T> records) {
        return new PageVO<>(page, size, total, records);
    }
}
