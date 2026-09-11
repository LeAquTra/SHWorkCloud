package com.leaqutra.shworkcloud.service;

import java.nio.charset.StandardCharsets;

/**
 * 构造 {@code Content-Disposition} 头。
 * <p>
 * 中文文件名必须走 RFC 5987 的 {@code filename*=UTF-8''...} 形式：
 * 老式 {@code filename="作业.docx"} 会因为响应头只能是 ISO-8859-1 而变成乱码，
 * 浏览器就会把文件存成 "???.docx" 甚至丢掉扩展名。
 * <p>
 * 因此同时给出两段：ASCII 回退名（给老客户端）+ UTF-8 编码名（给现代浏览器）。
 */
public final class ContentDisposition {

    /** RFC 5987 允许直接出现的字符（unreserved） */
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private ContentDisposition() {
    }

    /** {@code attachment}：用于下载 */
    public static String attachment(String fileName) {
        return build("attachment", fileName);
    }

    /** {@code inline}：用于预览 */
    public static String inline(String fileName) {
        return build("inline", fileName);
    }

    private static String build(String type, String fileName) {
        String name = sanitize(fileName);
        String ascii = toAsciiFallback(name);
        String encoded = rfc5987Encode(name);
        return "%s; filename=\"%s\"; filename*=UTF-8''%s".formatted(type, ascii, encoded);
    }

    /** 去掉控制字符、引号与路径分隔符，避免头注入与歧义 */
    private static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "download";
        }
        StringBuilder sb = new StringBuilder(fileName.length());
        for (char c : fileName.toCharArray()) {
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            if (c == '"' || c == '\\' || c == '/' || c == ';') {
                sb.append('_');
                continue;
            }
            sb.append(c);
        }
        String value = sb.toString().trim();
        return value.isEmpty() ? "download" : value;
    }

    /** 非 ASCII 字符替换为下划线，保证在不支持 filename* 的客户端上也有个可读名字 */
    private static String toAsciiFallback(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(c <= 0x7F ? c : '_');
        }
        String value = sb.toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? value.substring(0, dot) : value;
        String ext = dot > 0 ? value.substring(dot) : "";
        // 名字主体全变成下划线（例如纯中文名）时，用一个通用名 + 原扩展名，
        // 避免出现 "__.docx" 这种既看不懂、又可能被当成隐藏文件的名字
        if (stem.replace("_", "").isEmpty()) {
            return "download" + ext;
        }
        return value;
    }

    /** RFC 5987 百分号编码（按 UTF-8 字节） */
    private static String rfc5987Encode(String name) {
        StringBuilder sb = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int value = b & 0xFF;
            char c = (char) value;
            if (value <= 0x7F && UNRESERVED.indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", value));
            }
        }
        return sb.toString();
    }
}
