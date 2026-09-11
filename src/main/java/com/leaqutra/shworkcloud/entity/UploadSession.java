package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传会话：支撑 commit 幂等与孤儿对象回收。
 * <p>
 * 生命周期：签发上传凭证时插入 PENDING(0) -> commit 成功置 COMMITTED(1)
 * -> 超时未 commit 由对账任务置 ABANDONED(2) 并删除 OSS 对象。
 */
@Data
@TableName("upload_session")
public class UploadSession {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_COMMITTED = 1;
    public static final int STATUS_ABANDONED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 服务端签发的 OSS Key（唯一） */
    private String objectKey;

    /** 一次性上传令牌（唯一） */
    private String uploadToken;

    private Long parentId;

    private String name;

    /** OSS 返回的真实大小 */
    private Long size;

    private Byte status;

    /** commit 成功后关联的 file_entry.id */
    private Long entryId;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
