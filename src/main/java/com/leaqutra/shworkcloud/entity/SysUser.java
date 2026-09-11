package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户表。
 * <p>
 * 角色：0 学生 / 1 管理员 / 2 教师(机房管理员) / 9 超级管理员（数字是权威值）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 学生：学生角色 */
    public static final int ROLE_STUDENT = 0;
    /** 管理员 */
    public static final int ROLE_ADMIN = 1;
    /** 教师 / 机房管理员 */
    public static final int ROLE_TEACHER = 2;
    /** 超级管理员（系统初始化，不可删除/禁用/降级） */
    public static final int ROLE_SUPER_ADMIN = 9;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名：学生为学号 */
    private String username;

    /** 学号（学生必填，其他角色为 null） */
    private String studentNo;

    private String realName;

    private String className;

    /** QQ 邮箱，可空；唯一索引允许多个 NULL */
    private String email;

    /** BCrypt 密文。@JsonIgnore 只是兜底，接口一律返回 VO 而非实体 */
    @JsonIgnore
    private String password;

    private String nickname;

    /**
     * 自定义头像在 OSS 的 ObjectKey（形如 avatar/{userId}/{uuid}.png）。
     * <p>存的是 Key 而不是完整 URL：换头像时要能定位并删除旧对象；
     * 展示用的签名 URL 在读取资料时临时生成。
     */
    private String avatarKey;

    /** 个性签名 */
    private String signature;

    /** 性别：0 未知 1 男 2 女 */
    private Byte gender;

    /** 生日 */
    private LocalDate birthday;

    private Byte role;

    /** 1 正常 0 禁用 */
    private Byte status;

    /** 逻辑删除：0 未删 1 已删（MyBatis-Plus @TableLogic） */
    @TableLogic
    private Byte deleted;

    private Long storageQuota;

    /** 已用容量（缓存值，权威值由 file_entry 聚合重算） */
    private Long usedStorage;

    /** 1 已改密 0 需强制改密 */
    private Byte pwdChanged;

    private Integer loginFailCount;

    private LocalDateTime lockedUntil;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public boolean isStudent() {
        return role != null && role == ROLE_STUDENT;
    }

    public boolean isSuperAdmin() {
        return role != null && role == ROLE_SUPER_ADMIN;
    }
}
