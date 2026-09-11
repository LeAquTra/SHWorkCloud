package com.leaqutra.shworkcloud.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 一次服务端流式下载的上下文。
 * <p>
 * 由 {@code FileService.prepareDownload} 准备、Controller 负责写响应、用完必须 close
 * （底层是 OSS 的 HTTP 连接，不关会泄漏连接池）。
 */
public record DownloadTarget(String fileName,
                             String contentType,
                             long totalSize,
                             long start,
                             long end,
                             boolean partial,
                             InputStream content,
                             Closeable source) implements Closeable {

    /** 本次响应体的字节数（Range 场景下是区间长度） */
    public long contentLength() {
        return end - start + 1;
    }

    @Override
    public void close() throws IOException {
        try {
            content.close();
        } finally {
            if (source != null) {
                source.close();
            }
        }
    }
}
