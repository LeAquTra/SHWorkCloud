package com.leaqutra.shworkcloud.dto;

import java.util.List;

/**
 * 后台管理相关请求体。
 */
public final class AdminDto {

    private AdminDto() {
    }

    public record QuotaReq(long quotaBytes) {
    }

    public record StatusReq(byte status) {
    }

    public record RoleReq(byte role) {
    }

    /** 批量重置密码 */
    public record ResetPwdBatchReq(List<Long> ids) {
    }

    /** 新增/修改验证码题目（图片数据部分） */
    public record CaptchaUpsertReq(Integer type, String answer, String dataJson,
                                   Integer width, Integer height, Integer weight, String remark) {
    }

    /** 机房清场：按 IP 前缀/网段批量踢出会话 */
    public record SessionFlushReq(String ipPrefix) {
    }

    /**
     * 新增/修改公告。
     *
     * @param level      注意力分级：1 普通 / 2 重要 / 3 紧急
     * @param content    纯文本正文（不接受 HTML）
     * @param expireTime 为空表示不过期
     */
    public record AnnouncementUpsertReq(String title, String content, Integer level,
                                       java.time.LocalDateTime expireTime) {
    }
}
