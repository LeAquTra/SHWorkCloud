package com.leaqutra.shworkcloud.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充 createTime / updateTime。
 * <p>
 * 实体上用 {@code @TableField(fill = ...)} 标注了这两个字段，
 * <b>必须有本处理器</b>：否则 MyBatis-Plus 会把这些列以 NULL 写入，
 * 而建表脚本里它们是 NOT NULL，插入会直接失败。
 * <p>
 * 用 LocalDateTime.now() 而不是依赖数据库 DEFAULT，是为了让「应用写入的时间」
 * 与「应用读取的时间」在同一个时钟源上，避免 JDBC 时区换算带来的偏差。
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, UPDATE_TIME, LocalDateTime.class, LocalDateTime.now());
    }
}
