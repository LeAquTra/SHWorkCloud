package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.OssDto;
import com.leaqutra.shworkcloud.service.StsService;
import com.leaqutra.shworkcloud.service.UploadTicketService;
import com.leaqutra.shworkcloud.vo.FileVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 上传凭证与预签名 URL。
 * <p>
 * <b>推荐路径（Web 前端使用）</b>：{@code /oss/ticket} 拿 uploadToken，
 * 然后用 {@code /oss/put-url}（小文件）或 {@code /oss/multipart/*}（大文件）直传 OSS。
 * 这条路径下浏览器完全拿不到任何 AK/SK，也不需要做签名计算 ——
 * 对「内网 http、非安全上下文」的机房环境是必需的。
 * <p>
 * <b>可选路径</b>：{@code /oss/sts} 返回 STS 临时凭证，供有 HTTPS 环境且
 * 希望使用 ali-oss SDK 的场景。两条路径签发的 uploadKey 语义一致。
 */
@RestController
@RequestMapping("/oss")
@RequiredArgsConstructor
public class OssController {

    private final StsService stsService;
    private final UploadTicketService uploadTicketService;

    // -------------------------------------------------- 推荐路径（预签名直传）

    /** 申请上传凭证：服务端确定 ObjectKey 并签发一次性 uploadToken */
    @PostMapping("/ticket")
    public R<FileVo.UploadTicketVo> ticket(@RequestBody OssDto.TicketReq req) {
        return R.ok(uploadTicketService.issueTicket(req));
    }

    /**
     * 上传预检参数：全局上限、分类上限（{@code video} / {@code archive}）、文件夹总大小上限。
     * <p>集中下发是为了让前端不必把数字再硬编码一遍 ——
     * 否则改了后端配置就会出现"前端还按旧值拦"的不一致。
     */
    @GetMapping("/upload-config")
    public R<FileVo.UploadConfigVo> uploadConfig() {
        return R.ok(uploadTicketService.uploadConfig());
    }

    /** 小文件：取单次 PUT 的预签名 URL */
    @PostMapping("/put-url")
    public R<FileVo.PutUrlVo> putUrl(@RequestBody OssDto.PutUrlReq req) {
        return R.ok(uploadTicketService.putUrl(req));
    }

    /** 初始化分片上传 */
    @PostMapping("/multipart/init")
    public R<Map<String, String>> initMultipart(@RequestBody OssDto.MultipartInitReq req) {
        return R.ok(Map.of("uploadId", uploadTicketService.initMultipart(req)));
    }

    /** 批量取分片上传 URL（响应里的 contentType 必须由每个分片 PUT 原样携带） */
    @PostMapping("/multipart/part-urls")
    public R<FileVo.PartUrlsVo> partUrls(@RequestBody OssDto.PartUrlsReq req) {
        return R.ok(uploadTicketService.partUrls(req));
    }

    /** 完成分片上传（之后仍需调用 /files/commit 建立索引） */
    @PostMapping("/multipart/complete")
    public R<Map<String, String>> completeMultipart(@RequestBody OssDto.MultipartCompleteReq req) {
        return R.ok(Map.of("objectKey", uploadTicketService.completeMultipart(req)));
    }

    /** 放弃分片上传 */
    @PostMapping("/multipart/abort")
    public R<Void> abortMultipart(@RequestBody OssDto.MultipartAbortReq req) {
        uploadTicketService.abortMultipart(req);
        return R.ok();
    }

    /** 已上传分片清单（刷新页面后断点续传用） */
    @GetMapping("/multipart/parts")
    public R<List<FileVo.UploadedPartVo>> uploadedParts(@RequestParam String uploadToken,
                                                       @RequestParam String uploadId) {
        return R.ok(uploadTicketService.uploadedParts(uploadToken, uploadId));
    }

    // -------------------------------------------------- 可选路径（STS）

    /**
     * STS 临时凭证。
     * <p>返回的是 STS <b>临时</b>凭证与服务端签发的 uploadKey/uploadToken；
     * 服务端主 AK/SK 永远不会离开服务器。
     * <p>⚠️ 与人机验证：这条路径同样受上传验证码约束（否则它就是绕开验证码的后门）。
     * 需要时把 {@code captchaPassToken} 作为查询参数带上。
     */
    @GetMapping("/sts")
    public R<FileVo.StsVo> sts(@RequestParam(required = false) String captchaPassToken) {
        return R.ok(stsService.issueUploadToken(captchaPassToken));
    }
}
