package com.leaqutra.shworkcloud.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PartListing;
import com.aliyun.oss.model.PartSummary;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * OSS 低层封装：签名 URL、对象元数据、复制、删除、列举。
 * <p>
 * 说明：aliyun-sdk-oss 3.17.4 里「响应头覆盖」的类是
 * {@link ResponseHeaderOverrides}（不是 {@code ResponseHeaderParameters}），
 * 且 {@code GeneratePresignedUrlRequest} 没有 {@code withXxx} 链式方法，
 * 只能逐个 setter 设置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssSignService {

    /** 单次批量删除上限（OSS 限制 1000） */
    private static final int DELETE_BATCH = 1000;

    private final OSS ossClient;
    private final OssProperties oss;

    // ------------------------------------------------------------ 签名 URL

    /** 生成下载用的签名 URL（attachment，强制使用网盘里的显示名） */
    public String presignedDownloadUrl(String objectKey, String displayName, long expireSeconds) {
        ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
        overrides.setContentDisposition("attachment;filename="
                + URLEncoder.encode(displayName, StandardCharsets.UTF_8));
        return presign(objectKey, overrides, expireSeconds);
    }

    /** 生成预览用的签名 URL（inline） */
    public String presignedPreviewUrl(String objectKey, String displayName, long expireSeconds) {
        ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
        overrides.setContentDisposition("inline;filename="
                + URLEncoder.encode(displayName, StandardCharsets.UTF_8));
        overrides.setContentType(previewContentType(displayName));
        return presign(objectKey, overrides, expireSeconds);
    }

    private String presign(String objectKey, ResponseHeaderOverrides overrides, long expireSeconds) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(oss.getBucketName(), objectKey, HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + expireSeconds * 1000L));
        request.setResponseHeaders(overrides);
        return ossClient.generatePresignedUrl(request).toString();
    }

    /** 普通签名 GET URL（验证码图片等，不需要响应头覆盖） */
    public String presignedObjectUrl(String objectKey, long expireSeconds) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(oss.getBucketName(), objectKey, HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + expireSeconds * 1000L));
        return ossClient.generatePresignedUrl(request).toString();
    }

    private String previewContentType(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    // ------------------------------------------------------------ 对象操作

    public ObjectMetadata metadata(String objectKey) {
        return ossClient.getObjectMetadata(oss.getBucketName(), objectKey);
    }

    /**
     * 打开对象的读取流（服务端流式下载用）。
     * <p>调用方必须 close 返回的 {@link OSSObject}，否则会占用连接池。
     *
     * @param start 区间起点（含）；与 {@code end} 同时为 null 时读整个对象
     * @param end   区间终点（含）
     */
    public OSSObject openStream(String objectKey, Long start, Long end) {
        GetObjectRequest request = new GetObjectRequest(oss.getBucketName(), objectKey);
        if (start != null && end != null) {
            request.setRange(start, end);
        }
        return ossClient.getObject(request);
    }

    /**
     * 把对象整体读进内存（正文提取用）。
     * <p>先用 headObject 的长度做闸门，超限直接拒绝，避免把一个几百 MB 的文件读爆内存。
     *
     * @param maxBytes 允许读取的最大字节数
     */
    public byte[] readAll(String objectKey, long maxBytes) {
        long size;
        try {
            size = ossClient.getObjectMetadata(oss.getBucketName(), objectKey).getContentLength();
        } catch (Exception e) {
            throw new BizException(ErrorCode.OSS_OBJECT_NOT_FOUND);
        }
        if (size > maxBytes) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "文件大小 " + (size / 1024 / 1024) + "MB 超过在线阅览上限 "
                            + (maxBytes / 1024 / 1024) + "MB，请下载后查看");
        }
        try (OSSObject object = openStream(objectKey, null, null);
             var in = object.getObjectContent()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.error("读取 OSS 对象失败 key={}", objectKey, e);
            throw new BizException(ErrorCode.OSS_ERROR, "读取文件失败");
        }
    }

    public boolean exists(String objectKey) {
        try {
            return ossClient.doesObjectExist(oss.getBucketName(), objectKey);
        } catch (Exception e) {
            log.warn("探测 OSS 对象失败 key={} err={}", objectKey, e.getMessage());
            return false;
        }
    }

    /** 读取对象真实大小（commit 时的权威 size，不信任前端传值） */
    public long sizeOf(String objectKey) {
        try {
            return ossClient.getObjectMetadata(oss.getBucketName(), objectKey).getContentLength();
        } catch (Exception e) {
            throw new BizException(ErrorCode.OSS_OBJECT_NOT_FOUND);
        }
    }

    public void copy(String sourceKey, String destinationKey) {
        try {
            ossClient.copyObject(new CopyObjectRequest(
                    oss.getBucketName(), sourceKey, oss.getBucketName(), destinationKey));
        } catch (Exception e) {
            log.error("OSS CopyObject 失败 src={} dest={}", sourceKey, destinationKey, e);
            throw new BizException(ErrorCode.OSS_ERROR, "OSS 复制失败");
        }
    }

    public void delete(String objectKey) {
        try {
            ossClient.deleteObject(oss.getBucketName(), objectKey);
        } catch (Exception e) {
            // 删除失败不阻断业务：对象会成为孤儿，由对账任务回收
            log.error("OSS 删除失败 key={}", objectKey, e);
        }
    }

    public void deleteBatch(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < objectKeys.size(); i += DELETE_BATCH) {
            batches.add(objectKeys.subList(i, Math.min(i + DELETE_BATCH, objectKeys.size())));
        }
        for (List<String> batch : batches) {
            try {
                ossClient.deleteObjects(new DeleteObjectsRequest(oss.getBucketName())
                        .withKeys(new ArrayList<>(batch)));
            } catch (Exception e) {
                log.error("OSS 批量删除失败 batchSize={}", batch.size(), e);
            }
        }
    }

    /**
     * 列举某个前缀下的对象（孤儿对象对账用）。
     * <p>注意：用户侧 STS Policy 不含 ListObjects，此方法只在服务端使用主凭证调用。
     */
    public List<OSSObjectSummary> listAll(String prefix) {
        List<OSSObjectSummary> result = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(oss.getBucketName())
                    .withPrefix(prefix)
                    .withMaxKeys(1000);
            if (marker != null) {
                request.withMarker(marker);
            }
            ObjectListing listing = ossClient.listObjects(request);
            result.addAll(listing.getObjectSummaries());
            marker = listing.isTruncated() ? listing.getNextMarker() : null;
        } while (marker != null);
        return result;
    }

    /** 服务端上传（管理员题库图片走这里，不经用户 STS） */
    public void put(String objectKey, byte[] content, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(content.length);
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        try (var in = new java.io.ByteArrayInputStream(content)) {
            ossClient.putObject(oss.getBucketName(), objectKey, in, meta);
        } catch (Exception e) {
            log.error("OSS 上传失败 key={}", objectKey, e);
            throw new BizException(ErrorCode.OSS_ERROR, "OSS 上传失败");
        }
    }

    // -------------------------------------------------- 预签名上传（浏览器直传）

    /**
     * 单次 PUT 的预签名 URL（小文件用）。
     * <p>签名由服务端完成，浏览器只负责把字节 PUT 上去 ——
     * 因此**不需要把任何 AK/SK（哪怕是临时的）下发到浏览器**。
     * <p>⚠️ OSS V1 签名把 {@code Content-Type} 计入待签字符串，
     * 所以 URL 必须用与请求头**完全一致**的 Content-Type 生成，
     * 调用方也必须原样发送；否则会报 SignatureDoesNotMatch。
     */
    public String presignedPutUrl(String objectKey, String contentType, long expireSeconds) {
        String type = normalizeContentType(contentType);
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(oss.getBucketName(), objectKey, HttpMethod.PUT);
        request.setExpiration(new Date(System.currentTimeMillis() + expireSeconds * 1000L));
        request.setContentType(type);
        return ossClient.generatePresignedUrl(request).toString();
    }

    /** 分片上传统一使用这个 Content-Type 参与签名 */
    public static final String PART_CONTENT_TYPE = "application/octet-stream";

    /**
     * 分片上传的预签名 URL。
     * <p>{@code uploadId} 与 {@code partNumber} 属于 OSS 签名的「子资源」，
     * 必须通过 query parameter 加入，签名才会覆盖它们。
     * <p>Content-Type 固定为 {@code application/octet-stream} 并计入签名，
     * 调用方必须对每个分片请求都发送这个头。
     */
    public String presignedPartUrl(String objectKey, String uploadId, int partNumber,
                                   long expireSeconds) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(oss.getBucketName(), objectKey, HttpMethod.PUT);
        request.setExpiration(new Date(System.currentTimeMillis() + expireSeconds * 1000L));
        request.setContentType(PART_CONTENT_TYPE);
        request.addQueryParameter("uploadId", uploadId);
        request.addQueryParameter("partNumber", String.valueOf(partNumber));
        return ossClient.generatePresignedUrl(request).toString();
    }

    private static String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? PART_CONTENT_TYPE : contentType.trim();
    }

    /**
     * 分片上传的上传 ID。
     * <p>ObjectMetadata 里带上 content-type，保证最终对象类型正确。
     */
    public String initiateMultipart(String objectKey, String contentType) {
        InitiateMultipartUploadRequest request =
                new InitiateMultipartUploadRequest(oss.getBucketName(), objectKey);
        if (contentType != null && !contentType.isBlank()) {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(contentType);
            request.setObjectMetadata(meta);
        }
        try {
            return ossClient.initiateMultipartUpload(request).getUploadId();
        } catch (Exception e) {
            log.error("OSS 初始化分片上传失败 key={}", objectKey, e);
            throw new BizException(ErrorCode.OSS_ERROR, "初始化分片上传失败");
        }
    }

    /** 完成分片上传；parts 必须按 partNumber 升序 */
    public void completeMultipart(String objectKey, String uploadId, List<PartETag> parts) {
        try {
            ossClient.completeMultipartUpload(new CompleteMultipartUploadRequest(
                    oss.getBucketName(), objectKey, uploadId, parts));
        } catch (Exception e) {
            log.error("OSS 完成分片上传失败 key={} uploadId={}", objectKey, uploadId, e);
            throw new BizException(ErrorCode.OSS_ERROR, "完成分片上传失败");
        }
    }

    /** 放弃分片上传（清理未完成分片，避免持续计费） */
    public void abortMultipart(String objectKey, String uploadId) {
        try {
            ossClient.abortMultipartUpload(
                    new AbortMultipartUploadRequest(oss.getBucketName(), objectKey, uploadId));
        } catch (Exception e) {
            log.warn("OSS 放弃分片上传失败 key={} uploadId={} err={}", objectKey, uploadId, e.getMessage());
        }
    }

    /** 已上传的分片（断点续传与校验用） */    public List<PartSummary> listParts(String objectKey, String uploadId) {
        List<PartSummary> result = new ArrayList<>();
        Integer marker = null;
        do {
            ListPartsRequest request =
                    new ListPartsRequest(oss.getBucketName(), objectKey, uploadId);
            if (marker != null) {
                request.setPartNumberMarker(marker);
            }
            PartListing listing = ossClient.listParts(request);
            result.addAll(listing.getParts());
            marker = listing.isTruncated() ? listing.getNextPartNumberMarker() : null;
        } while (marker != null);
        return result;
    }

    public String baseUrl() {
        return oss.baseUrl();
    }
}
