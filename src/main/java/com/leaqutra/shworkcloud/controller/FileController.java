package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.dto.FileDto;
import com.leaqutra.shworkcloud.dto.FileQuery;
import com.leaqutra.shworkcloud.service.ContentDisposition;
import com.leaqutra.shworkcloud.service.DownloadTarget;
import com.leaqutra.shworkcloud.service.FileService;
import com.leaqutra.shworkcloud.service.UploadService;
import com.leaqutra.shworkcloud.vo.FileVo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 文件与目录管理。
 * <p>路径约定见 AuthController 的类注释：这里写业务路径，{@code /api} 由 context-path 提供。
 */
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final UploadService uploadService;

    // ---------------------------------------------------------------- 查询

    /** 目录列表：?parentId=0&category=&keyword=&page=1&size=50 */
    @GetMapping("/files")
    public R<PageVO<FileVo.FileItemVo>> list(FileQuery query) {
        return R.ok(fileService.list(query));
    }

    /** 当前用户的文件夹树（移动对话框用） */
    @GetMapping("/files/tree")
    public R<List<FileVo.FolderNodeVo>> tree() {
        return R.ok(fileService.tree());
    }

    @GetMapping("/files/breadcrumb")
    public R<List<FileVo.BreadcrumbVo>> breadcrumb(@org.springframework.web.bind.annotation.RequestParam Long id) {
        return R.ok(fileService.breadcrumb(id));
    }

    // ---------------------------------------------------------------- 写操作

    @PostMapping("/folders")
    public R<Long> createFolder(@RequestBody FileDto.FolderCreateReq req) {
        return R.ok(fileService.createFolder(req.parentId(), req.name()));
    }

    @PutMapping("/files/rename")
    public R<Void> rename(@RequestBody FileDto.RenameReq req) {
        fileService.rename(req.id(), req.name());
        return R.ok();
    }

    @PostMapping("/files/move")
    public R<Void> move(@RequestBody FileDto.MoveReq req) {
        fileService.move(req.id(), req.targetParentId());
        return R.ok();
    }

    @PostMapping("/files/copy")
    public R<Void> copy(@RequestBody FileDto.CopyReq req) {
        fileService.copy(req.id(), req.targetParentId(), req.name());
        return R.ok();
    }

    /** 批量软删除进回收站 */
    @DeleteMapping("/files")
    public R<Integer> softDelete(@RequestBody FileDto.IdsReq req) {
        return R.ok(fileService.softDelete(req.ids()));
    }

    // ---------------------------------------------------------------- 上传

    /** 直传完成后建立索引（幂等，核心接口） */
    @PostMapping("/files/commit")
    public R<FileVo.CommitVo> commit(@RequestBody FileDto.CommitReq req) {
        return R.ok(uploadService.commit(req));
    }

    /** 尝试秒传；hit=false 表示未命中，前端应走正常直传 */
    @PostMapping("/files/instant-upload")
    public R<FileVo.CommitVo> instantUpload(@RequestBody FileDto.InstantUploadReq req) {
        return R.ok(uploadService.instantUpload(req));
    }

    // ---------------------------------------------------------------- 下载预览

    /**
     * 取 10 分钟有效的签名下载地址。
     * <p>⚠️ 返回的是 <b>{@code { "url": "..." }}</b> 对象（不是裸字符串），
     * 与 {@code docs/后端接口手册.md} §6 的契约一致；详见 {@link FileVo.UrlVo}。
     */
    @GetMapping("/files/{id}/download-url")
    public R<FileVo.UrlVo> downloadUrl(@PathVariable Long id) {
        return R.ok(new FileVo.UrlVo(fileService.downloadUrl(id)));
    }

    /**
     * 服务端流式下载（"下载回本地"）。
     * <p>
     * 与 {@code /download-url} 的区别：那个返回 OSS 签名 URL，由客户端直接去 OSS 取；
     * 这个由服务端把字节流转发回来，调用方不需要能访问 OSS 域名，
     * 且支持 {@code Range} 请求头（断点续传）。
     * <p>用法示例：
     * <pre>
     *   curl -H "Authorization: &lt;token&gt;" -OJ http://localhost:8081/api/files/123/download
     *   curl -H "Authorization: &lt;token&gt;" -C - -o out.zip .../download   # 断点续传
     * </pre>
     */
    @GetMapping("/files/{id}/download")
    public void download(@PathVariable Long id,
                         @RequestHeader(value = "Range", required = false) String range,
                         HttpServletResponse response) throws IOException {
        try (DownloadTarget target = fileService.prepareDownload(id, range)) {
            response.setStatus(target.partial()
                    ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
            response.setContentType(target.contentType());
            response.setContentLengthLong(target.contentLength());
            // 声明支持 Range，下载管理器/curl -C - 才会尝试续传
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment(target.fileName()));
            if (target.partial()) {
                response.setHeader(HttpHeaders.CONTENT_RANGE,
                        "bytes %d-%d/%d".formatted(target.start(), target.end(), target.totalSize()));
            }
            try (InputStream in = target.content();
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        }
    }

    /**
     * 在线阅览：图片 / PDF / 视频 / 音频。
     * <p>{@code Content-Disposition: inline}，浏览器直接渲染；视频/音频支持 {@code Range}
     * （{@code <video>} / {@code <audio>} 会自动带 Range，从而支持拖动进度条）。
     * <p>⚠️ 浏览器的 {@code <img>}/{@code <video>} 标签<b>无法携带 Authorization 头</b>：
     * 这类标签请用 {@code /files/{id}/preview-url} 返回的签名地址；
     * 本接口适合能自行设置请求头的客户端，或前端用 fetch 取回后转成 blob URL。
     */
    @GetMapping("/files/{id}/preview")
    public void preview(@PathVariable Long id,
                        @RequestHeader(value = "Range", required = false) String range,
                        HttpServletResponse response) throws IOException {
        try (DownloadTarget target = fileService.preparePreview(id, range)) {
            response.setStatus(target.partial()
                    ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
            response.setContentType(target.contentType());
            response.setContentLengthLong(target.contentLength());
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline(target.fileName()));
            // 禁止浏览器"猜"类型：Content-Type 必须来自服务端白名单，防改扩展名做 XSS
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (target.partial()) {
                response.setHeader(HttpHeaders.CONTENT_RANGE,
                        "bytes %d-%d/%d".formatted(target.start(), target.end(), target.totalSize()));
            }
            try (InputStream in = target.content();
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        }
    }

    /**
     * 在线阅览文本 / Office 正文。
     * <p>返回已解码的文本（自动识别 GBK 与 UTF-8 BOM）；doc/docx/pptx 提取纯文本（排版会丢失）。
     * 超过大小上限返回 40082，并提示下载后查看。
     */
    @GetMapping("/files/{id}/text")
    public R<FileVo.TextContentVo> text(@PathVariable Long id) {
        return R.ok(fileService.extractText(id));
    }

    /**
     * 在线阅览 docx / pptx 里<b>内嵌的图片</b>。
     * <p>
     * 正文提取会把 XML 标签连同图片一起剥掉，所以图文作业光看 {@code /text}
     * 是"只有字、没有图"。这里把 {@code word/media/}（pptx 为 {@code ppt/media/}）
     * 里的位图取出来，以 data URL 返回，前端直接渲染。
     * <p>非 Office 文件返回空列表；不可渲染的矢量图（emf/wmf）与 svg 计入 {@code skipped}。
     */
    @GetMapping("/files/{id}/embedded-images")
    public R<FileVo.EmbeddedImagesVo> embeddedImages(@PathVariable Long id) {
        return R.ok(fileService.extractEmbeddedImages(id));
    }

    /**
     * 图片管理（相册）：跨目录列出本人全部可在线预览的图片，按上传时间倒序。
     * <p>与 {@code GET /files?category=image} 不同：那个只筛当前目录，这个摊平整个网盘。
     * <p>每项都带 {@code previewUrl}（1 小时签名地址，可直接给 {@code <img src>}）。
     */
    @GetMapping("/images")
    public R<PageVO<FileVo.ImageItemVo>> images(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "60") long size) {
        return R.ok(fileService.listImages(page, size));
    }

    /**
     * 取签名预览地址（{@code <img src>} / {@code <video src>} 可直接用，无需请求头）。
     * <p>同样返回 <b>{@code { "url": "..." }}</b> 对象，理由见 {@link FileVo.UrlVo}。
     */
    @GetMapping("/files/{id}/preview-url")
    public R<FileVo.UrlVo> previewUrl(@PathVariable Long id) {
        return R.ok(new FileVo.UrlVo(fileService.previewUrl(id)));
    }
}
