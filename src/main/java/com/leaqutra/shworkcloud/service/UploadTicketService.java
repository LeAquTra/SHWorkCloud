package com.leaqutra.shworkcloud.service;

import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PartSummary;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.config.OssProperties;
import com.leaqutra.shworkcloud.dto.OssDto;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.RateLimiter;
import com.leaqutra.shworkcloud.vo.FileVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 浏览器直传（预签名 URL 方案）。
 * <p>
 * <b>为什么不用 STS + ali-oss SDK：</b>
 * <ol>
 *   <li>本项目运行环境是<b>内网 http</b>，不是安全上下文，浏览器端做签名所需要
 *       的 {@code crypto.subtle} 不可用；</li>
 *   <li>把签名交给服务端后，浏览器<b>完全不需要接触任何 AK/SK</b>（哪怕是临时的），
 *       安全面更小；</li>
 *   <li>字节流仍然是「浏览器 → OSS」直达，不经过业务服务器，带宽目标不变。</li>
 * </ol>
 * 因此 {@code /api/oss/sts} 保留为可选路径，Web 前端使用本类提供的凭证 + 预签名 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadTicketService {

    /** OSS 分片数上限 */
    private static final int MAX_PARTS = 10000;
    /** 分片大小下限（OSS 要求，最后一片除外） */
    private static final long MIN_PART_SIZE = 100 * 1024L;
    /** 默认分片大小 */
    private static final long DEFAULT_PART_SIZE = 5L * 1024 * 1024;
    /** 大文件分片大小 */
    private static final long LARGE_PART_SIZE = 10L * 1024 * 1024;
    private static final long LARGE_FILE_THRESHOLD = 500L * 1024 * 1024;
    /** 预签名 URL 有效期（秒）。要覆盖学生上传一个大文件的时间，给足 2 小时 */
    private static final long URL_EXPIRE_SECONDS = 2 * 60 * 60;

    private final OssProperties oss;
    private final AppProperties appProperties;
    private final OssSignService ossSignService;
    private final UploadTokenService uploadTokenService;
    private final QuotaService quotaService;
    private final RateLimiter rateLimiter;
    private final LoginUser loginUser;

    // ---------------------------------------------------------------- 申请凭证

    /**
     * 申请上传凭证。
     * <p>这一步就把「ObjectKey」定下来了（服务端生成），
     * 后续所有上传动作只认这个 uploadToken。
     */
    public FileVo.UploadTicketVo issueTicket(OssDto.TicketReq req) {
        long userId = loginUser.id();
        rateLimiter.checkStsIssue(userId);

        long size = req == null ? 0L : req.size();
        String suffix = FileNaming.extension(req == null ? null : req.name());
        // 分类上限优先：视频与压缩包默认压到 100MB（见 app.upload.transfer-limits），
        // 其余类型仍走全局的 max-file-size-bytes
        long maxFileSize = effectiveLimit(suffix);
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_PARAM, "文件大小不合法");
        }
        if (maxFileSize > 0 && size > maxFileSize) {
            String label = FileViewType.transferClass(suffix) == null ? "单个文件" : "该类型文件";
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    label + "不能超过 " + (maxFileSize / 1024 / 1024) + "MB");
        }
        // 配额预检：避免学生白传几个 GB 之后才在 commit 被拒
        quotaService.ensureFree(userId, size);

        String objectKey = ObjectKeys.newUserKey(oss.userPrefix(userId));
        String uploadToken = uploadTokenService.mint(userId, objectKey);
        long partSize = choosePartSize(size);

        return new FileVo.UploadTicketVo(
                uploadToken,
                objectKey,
                partSize,
                (size + partSize - 1) / partSize,
                (int) appProperties.getUpload().getUploadTokenSeconds(),
                appProperties.getUpload().getInstantThresholdBytes(),
                maxFileSize);
    }

    /**
     * 该文件实际适用的单文件上限：分类上限优先，否则全局上限。
     * <p>结果会随 ticket 一起下发，前端据此在<b>选文件时就拦</b>，不必等传完才被拒。
     */
    private long effectiveLimit(String suffix) {
        AppProperties.Upload upload = appProperties.getUpload();
        String transferClass = FileViewType.transferClass(suffix);
        if (transferClass != null && upload.getTransferLimits() != null) {
            Long limit = upload.getTransferLimits().get(transferClass);
            if (limit != null && limit > 0) {
                return limit;
            }
        }
        return upload.getMaxFileSizeBytes();
    }

    /** 前端上传预检参数：各类上限集中下发，避免前端把数字再硬编码一遍 */
    public FileVo.UploadConfigVo uploadConfig() {
        AppProperties.Upload upload = appProperties.getUpload();
        return new FileVo.UploadConfigVo(
                upload.getMaxFileSizeBytes(),
                upload.getTransferLimits() == null ? Map.of() : upload.getTransferLimits(),
                upload.getFolderMaxTotalBytes());
    }

    /**
     * 选择分片大小。
     * <p>既要满足「分片数 ≤ 10000」，也要避免小文件被切得太碎。
     */
    static long choosePartSize(long size) {
        long partSize = size >= LARGE_FILE_THRESHOLD ? LARGE_PART_SIZE : DEFAULT_PART_SIZE;
        if (size / partSize > MAX_PARTS) {
            // 向上取整到 1MB 的倍数
            long needed = (size + MAX_PARTS - 1) / MAX_PARTS;
            partSize = ((needed + 1024 * 1024 - 1) / (1024 * 1024)) * 1024 * 1024;
        }
        return Math.max(partSize, MIN_PART_SIZE);
    }

    // ------------------------------------------------------------ 预签名 URL

    /**
     * 小文件：单次 PUT 的预签名 URL。
     * <p>返回的 {@code contentType} 是签名时使用的值，客户端<b>必须原样发送</b>该请求头。
     */
    public FileVo.PutUrlVo putUrl(OssDto.PutUrlReq req) {
        long userId = loginUser.id();
        String objectKey = uploadTokenService.requireObjectKey(req.uploadToken(), userId);
        String contentType = StringUtils.hasText(req.contentType())
                ? req.contentType().trim() : OssSignService.PART_CONTENT_TYPE;
        String url = ossSignService.presignedPutUrl(objectKey, contentType, URL_EXPIRE_SECONDS);
        return new FileVo.PutUrlVo(url, objectKey, contentType, (int) URL_EXPIRE_SECONDS);
    }

    /** 初始化分片上传，返回 uploadId */
    public String initMultipart(OssDto.MultipartInitReq req) {
        long userId = loginUser.id();
        String objectKey = uploadTokenService.requireObjectKey(req.uploadToken(), userId);
        return ossSignService.initiateMultipart(objectKey, req.contentType());
    }

    /**
     * 批量取分片上传 URL。
     * <p>批量是必要的：一个 2GB 文件有 400 个分片，逐个请求会让服务端成为瓶颈。
     * <p>返回的 {@code contentType} 必须由每个分片 PUT 请求原样携带，否则签名不匹配。
     */
    public FileVo.PartUrlsVo partUrls(OssDto.PartUrlsReq req) {
        long userId = loginUser.id();
        String objectKey = uploadTokenService.requireObjectKey(req.uploadToken(), userId);
        if (!StringUtils.hasText(req.uploadId())) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少 uploadId");
        }
        if (req.partNumbers() == null || req.partNumbers().isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少 partNumbers");
        }
        if (req.partNumbers().size() > 500) {
            throw new BizException(ErrorCode.BAD_PARAM, "单次最多申请 500 个分片 URL");
        }
        List<FileVo.PartUrlVo> urls = new ArrayList<>(req.partNumbers().size());
        for (Integer partNumber : req.partNumbers()) {
            if (partNumber == null || partNumber < 1 || partNumber > MAX_PARTS) {
                throw new BizException(ErrorCode.BAD_PARAM, "分片序号非法: " + partNumber);
            }
            urls.add(new FileVo.PartUrlVo(partNumber,
                    ossSignService.presignedPartUrl(objectKey, req.uploadId(), partNumber,
                            URL_EXPIRE_SECONDS)));
        }
        return new FileVo.PartUrlsVo(OssSignService.PART_CONTENT_TYPE, urls);
    }

    /** 完成分片上传 */
    public String completeMultipart(OssDto.MultipartCompleteReq req) {
        long userId = loginUser.id();
        String objectKey = uploadTokenService.requireObjectKey(req.uploadToken(), userId);
        if (!StringUtils.hasText(req.uploadId())) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少 uploadId");
        }
        if (req.parts() == null || req.parts().isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少已上传分片列表");
        }
        // OSS 要求按 partNumber 升序提交
        List<PartETag> partETags = req.parts().stream()
                .sorted(Comparator.comparingInt(OssDto.PartItem::partNumber))
                .map(p -> new PartETag(p.partNumber(), p.etag()))
                .toList();
        ossSignService.completeMultipart(objectKey, req.uploadId(), partETags);
        return objectKey;
    }

    /** 放弃分片上传（清理未完成分片，避免持续计费） */
    public void abortMultipart(OssDto.MultipartAbortReq req) {
        long userId = loginUser.id();
        String objectKey = uploadTokenService.requireObjectKey(req.uploadToken(), userId);
        if (!StringUtils.hasText(req.uploadId())) {
            return;
        }
        ossSignService.abortMultipart(objectKey, req.uploadId());
    }

    /**
     * 已上传的分片清单。
     * <p>用途：页面刷新后重新选择同一文件时，前端据此跳过已完成分片，
     * 真正做到「刷新后继续传」—— 这也是 v1.1 文档里说错的地方
     * （ali-oss 的 checkpoint 无法跨刷新，服务端 listParts 可以）。
     */
    public List<FileVo.UploadedPartVo> uploadedParts(String uploadToken, String uploadId) {
        long userId = loginUser.id();
        String objectKey = uploadTokenService.requireObjectKey(uploadToken, userId);
        if (!StringUtils.hasText(uploadId)) {
            return List.of();
        }
        List<PartSummary> parts = ossSignService.listParts(objectKey, uploadId);
        return parts.stream()
                .map(p -> new FileVo.UploadedPartVo(p.getPartNumber(), p.getETag(), p.getSize()))
                .toList();
    }
}
