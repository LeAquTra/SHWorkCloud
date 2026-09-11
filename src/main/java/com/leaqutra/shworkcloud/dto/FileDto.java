package com.leaqutra.shworkcloud.dto;

import java.util.List;

/**
 * 文件与上传相关请求体。
 */
public final class FileDto {

    private FileDto() {
    }

    public record FolderCreateReq(Long parentId, String name) {
    }

    public record RenameReq(Long id, String name) {
    }

    public record MoveReq(Long id, Long targetParentId) {
    }

    /** 复制（OSS CopyObject + 新索引），复制文件夹时递归 */
    public record CopyReq(Long id, Long targetParentId, String name) {
    }

    /** 批量操作（删除进回收站 / 还原 / 彻底删除） */
    public record IdsReq(List<Long> ids) {
    }

    /**
     * 直传完成后建立索引（核心接口，幂等）。
     * <p>不接收 objectKey —— 服务端以 uploadToken 对应的 uploadKey 为准；
     * 也不接收 size —— 服务端以 OSS headObject 的 ContentLength 为准。
     */
    public record CommitReq(String uploadToken, Long parentId, String name,
                            String contentType, String md5) {
    }

    /** 尝试秒传；命中则服务端直接 CopyObject 并建索引 */
    public record InstantUploadReq(String md5, Long parentId, String name,
                                   long size, String contentType) {
    }
}
