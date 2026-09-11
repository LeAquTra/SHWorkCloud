package com.leaqutra.shworkcloud.dto;

import java.util.List;

/**
 * OSS 直传（预签名）相关请求体。
 */
public final class OssDto {

    private OssDto() {
    }

    /** 申请上传凭证 */
    public record TicketReq(String name, long size, String contentType) {
    }

    /** 取单次 PUT 的预签名 URL（小文件）；contentType 会参与签名，客户端必须原样发送 */
    public record PutUrlReq(String uploadToken, String contentType) {
    }

    /** 初始化分片上传 */
    public record MultipartInitReq(String uploadToken, String contentType) {
    }

    /** 批量取分片上传 URL */
    public record PartUrlsReq(String uploadToken, String uploadId, List<Integer> partNumbers) {
    }

    /** 单个已上传分片 */
    public record PartItem(int partNumber, String etag) {
    }

    /** 完成分片上传 */
    public record MultipartCompleteReq(String uploadToken, String uploadId, List<PartItem> parts) {
    }

    /** 放弃分片上传 */
    public record MultipartAbortReq(String uploadToken, String uploadId) {
    }
}
