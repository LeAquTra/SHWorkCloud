package com.leaqutra.shworkcloud.service;

import org.springframework.util.StringUtils;

/**
 * HTTP Range 请求头解析（支持断点续传 / 续传下载）。
 * <p>
 * 只处理<b>单个</b> range（{@code bytes=start-end}、{@code bytes=start-}、{@code bytes=-suffix}）。
 * 多段 range 与非法语法一律返回 {@code null}，即"按完整内容返回 200"——
 * 对下载场景来说，宁可多传一次也不要因为一个畸形请求头让下载失败。
 * <p>
 * 抽成独立类是为了可单元测试。
 */
public final class HttpRange {

    private HttpRange() {
    }

    /** 闭区间 [start, end]，单位字节 */
    public record ByteRange(long start, long end) {
        public long length() {
            return end - start + 1;
        }
    }

    /**
     * 解析 Range 头。
     *
     * @param header    {@code Range} 请求头，可为 null
     * @param totalSize 对象总大小（字节）
     * @return 可用区间；返回 null 表示应按完整内容返回
     */
    public static ByteRange parse(String header, long totalSize) {
        if (!StringUtils.hasText(header) || totalSize <= 0) {
            return null;
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = value.substring(6).trim();
        // 多段 range（含逗号）不支持，退回完整内容
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();

        try {
            if (startPart.isEmpty()) {
                // bytes=-N：最后 N 字节
                if (endPart.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    return null;
                }
                long start = Math.max(0, totalSize - suffix);
                return new ByteRange(start, totalSize - 1);
            }

            long start = Long.parseLong(startPart);
            if (start < 0 || start >= totalSize) {
                // 起点越界：不可满足，退回完整内容
                return null;
            }
            long end = endPart.isEmpty() ? totalSize - 1 : Long.parseLong(endPart);
            if (end < start) {
                return null;
            }
            return new ByteRange(start, Math.min(end, totalSize - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
