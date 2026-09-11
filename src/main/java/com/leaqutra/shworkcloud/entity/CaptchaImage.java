package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 注册图片验证码题库。
 * <p>
 * 图片实体在 OSS {@code captcha/} 前缀下，答案与坐标标注存本表；
 * 出题接口只下发签名的图片 URL 与必要的展示信息，<b>答案永不下发</b>。
 */
@Data
@TableName("captcha_image")
public class CaptchaImage {

    /** 字符输入 */
    public static final int TYPE_TEXT = 1;
    /** 单选 */
    public static final int TYPE_SINGLE = 2;
    /** 点选（按顺序点击） */
    public static final int TYPE_CLICK = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer type;

    /** OSS 物理 Key，captcha/ 前缀 */
    private String objectKey;

    /** 标准答案：字符 / 选项 key / 点选标注点 ID 顺序（如 "3,1,2"） */
    private String answer;

    /** 结构化标注 JSON：选项列表或点击坐标点 */
    private String dataJson;

    private Integer width;

    private Integer height;

    /** 出题权重，越大越容易被抽中 */
    private Integer weight;

    private Long usedCount;

    /** 1 启用 0 停用 */
    private Byte status;

    private String remark;

    private Long createdBy;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
