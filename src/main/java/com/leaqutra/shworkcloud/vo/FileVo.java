package com.leaqutra.shworkcloud.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件与上传相关响应体。
 */
public final class FileVo {

    private FileVo() {
    }

    /**
     * 上传凭证。
     * <p>accessKeyId/accessKeySecret/securityToken 是 STS <b>临时</b>凭证，
     * 绝不是服务端主 AK/SK。
     * <p>{@code uploadKey} 由服务端签发，前端必须使用它作为 ObjectKey，
     * 不得自行生成（内网 http 非安全上下文下 crypto.randomUUID 并不存在）。
     */
    public record StsVo(String accessKeyId, String accessKeySecret, String securityToken,
                        String expiration, String region, String endpoint, String bucket,
                        String prefix, String uploadKey, String uploadToken) {
    }

    /** commit 结果；receipt 是给学生/教师核对的提交凭证 */
    public record CommitVo(Long fileId, String name, long size, String receipt,
                           String commitTime, boolean renamed, boolean duplicated, boolean hit) {

        /** 秒传未命中：让前端走正常直传流程 */
        public static CommitVo miss() {
            return new CommitVo(null, null, 0L, null, null, false, false, false);
        }
    }

    /**
     * 文件/文件夹列表项。
     * <p>{@code viewType} 由服务端判定，前端据此决定怎么渲染（不要再自己写一份后缀白名单）：
     * {@code image} / {@code pdf} / {@code video} / {@code audio} 用流式预览接口，
     * {@code text} / {@code office} 用 {@code /files/{id}/text}，{@code none} 只能下载。
     */
    public record FileItemVo(Long id, String name, boolean folder, long size, String suffix,
                             String viewType, String contentType,
                             LocalDateTime createTime, LocalDateTime updateTime,
                             boolean previewable) {
    }

    /**
     * 图片管理（相册）列表项。
     * <p>{@code previewUrl} 是 1 小时有效的 OSS 签名地址，可直接放进 {@code <img src>}
     * （私有 Bucket 必须签名，而 {@code <img>} 无法携带 Authorization 头）。
     * <p>{@code previewApiUrl} 是服务端流式接口的相对路径，需要自行带 Authorization 头。
     */
    public record ImageItemVo(Long id, String name, String suffix, long size, Long parentId,
                              String viewType, String previewUrl, String previewApiUrl,
                              LocalDateTime createTime, LocalDateTime updateTime) {
    }

    /** 文本/Office 正文阅览结果 */
    public record TextContentVo(Long id, String name, String suffix, String viewType, long size,
                                String charset, String content, boolean truncated,
                                int maxChars, String hint) {
    }

    public record FolderNodeVo(Long id, String name, List<FolderNodeVo> children) {
    }

    public record BreadcrumbVo(Long id, String name) {
    }

    public record UploadSessionVo(Long uploadId, String uploadKey, long partSize) {
    }

    /**
     * 上传凭证（推荐路径）。
     * <p>与 {@link StsVo} 的区别：这里<b>不下发任何 AK/SK</b>。
     * 浏览器只拿到 uploadToken，之后所有上传动作都通过服务端预签名 URL 完成，
     * 因此即使在内网 http 环境下也不需要在浏览器里做任何签名计算。
     */
    public record UploadTicketVo(String uploadToken, String objectKey, long partSize,
                                 long partCount, int expireSeconds,
                                 long instantThresholdBytes, long maxFileSizeBytes) {
    }

    /** 分片上传的一个预签名 URL */
    public record PartUrlVo(int partNumber, String url) {
    }

    /**
     * 单次 PUT 的预签名 URL。
     * <p>⚠️ {@code contentType} 是<b>签名时使用的 Content-Type</b>，
     * 客户端必须原样发送该请求头，否则 OSS 会返回 SignatureDoesNotMatch。
     */
    public record PutUrlVo(String url, String objectKey, String contentType, int expireSeconds) {
    }

    /**
     * 一批分片上传 URL。
     * <p>⚠️ {@code contentType} 必须由每个分片 PUT 请求原样携带。
     */
    public record PartUrlsVo(String contentType, List<PartUrlVo> urls) {
    }

    /** 已上传的分片（断点续传用） */
    public record UploadedPartVo(int partNumber, String etag, long size) {
    }
}
