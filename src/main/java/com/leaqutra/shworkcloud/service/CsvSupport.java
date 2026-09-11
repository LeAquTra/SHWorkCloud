package com.leaqutra.shworkcloud.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 解析（含编码兼容）。
 * <p>
 * 为什么不用 {@code split(",")}：
 * <ol>
 *   <li>姓名里可能有逗号或用引号包裹（如 {@code "张,三"}）；</li>
 *   <li>校内旧系统导出的 CSV 常是 GBK，而 Excel「另存为 CSV」带 UTF-8 BOM，
 *       直接用 UTF-8 读 GBK 文件会让中文姓名整列变乱码。</li>
 * </ol>
 */
public final class CsvSupport {

    private CsvSupport() {
    }

    /** 解码结果：文本 + 实际使用的字符集名（排查乱码时有用） */
    public record Decoded(String text, String charset) {
    }

    /**
     * 按编码探测规则解码字节：UTF-8 BOM -> UTF-8；严格 UTF-8 校验失败则回退 GBK。
     */
    public static Decoded decodeWithCharset(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Decoded("", "UTF-8");
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), "UTF-8(BOM)");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new Decoded(text, "UTF-8");
        } catch (CharacterCodingException e) {
            return new Decoded(new String(bytes, Charset.forName("GBK")), "GBK");
        }
    }

    /** 只要文本，不关心字符集 */
    public static String decode(byte[] bytes) {
        return decodeWithCharset(bytes).text();
    }

    /** 解析为行 -> 列；支持引号包裹与 "" 转义、\r\n 与 \n */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString().trim());
                    field.setLength(0);
                }
                case '\r' -> {
                    // 兼容 \r\n：忽略 \r，由 \n 收尾
                }
                case '\n' -> {
                    row.add(field.toString().trim());
                    field.setLength(0);
                    if (!row.isEmpty() && !(row.size() == 1 && row.get(0).isEmpty())) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        row.add(field.toString().trim());
        if (!(row.size() == 1 && row.get(0).isEmpty())) {
            rows.add(row);
        }
        return rows;
    }
}
