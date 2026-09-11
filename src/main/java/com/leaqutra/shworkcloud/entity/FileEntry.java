package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 网盘文件索引：文件与文件夹统一建模。
 * <p>
 * 关键约束（文档 §5.4）：
 * <ul>
 *   <li>{@code uk_object_key} 保证 commit 幂等；文件夹 object_key 为 NULL（唯一索引允许多个 NULL）。</li>
 *   <li>{@code uk_sibling_active(user_id, parent_id, active_name)} 保证同级活跃记录不重名；
 *       {@code active_name} 是数据库生成列（status=1 时为 name，否则为 NULL），
 *       因此回收站里的同名文件不会互相冲突。该列<b>由数据库维护，代码绝不能写</b>，
 *       故 insertStrategy/updateStrategy 都设为 NEVER。</li>
 * </ul>
 */
@Data
@TableName("file_entry")
public class FileEntry {

    /** 正常 */
    public static final int STATUS_NORMAL = 1;
    /** 回收站 */
    public static final int STATUS_RECYCLED = 0;

    /** 目录层级上限（服务端强制校验） */
    public static final int MAX_DEPTH = 20;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 父目录 ID，0 表示根目录 */
    private Long parentId;

    private String name;

    /** 1 文件夹 0 文件 */
    private Byte isFolder;

    /** OSS 物理 Key（服务端签发）；文件夹为 null */
    private String objectKey;

    /** 以 OSS headObject 返回的 ContentLength 为准，不信任前端上传的 size */
    private Long size;

    private String suffix;

    private String contentType;

    /** 文件指纹：≤200MB 为全量 MD5，>200MB 为抽样指纹 */
    private String md5;

    private Byte status;

    /** 祖先 ID 物化路径，如 /12/35/；parent_id 才是权威，本字段是冗余加速列 */
    private String path;

    /**
     * 数据库生成列，仅用于唯一约束。禁止写入。
     */
    @TableField(value = "active_name", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeName;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 进入回收站的时间 */
    private LocalDateTime deleteTime;

    public boolean isFolderEntry() {
        return isFolder != null && isFolder == 1;
    }

    /** 本记录自身的物化路径（含自身），如 /12/35/78/ */
    public String selfPath() {
        String base = path == null ? "/" : path;
        return base + id + "/";
    }
}
