package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文件正文提取，用于「在线阅览」。
 * <p>
 * 覆盖两类：
 * <ol>
 *   <li><b>纯文本</b>（txt/md/csv/json/日志/源码…）：直接解码。
 *       编码用 {@link CsvSupport#decodeWithCharset}，兼容 UTF-8 BOM 与 GBK ——
 *       学生用记事本存的文件在中文 Windows 上默认就是 GBK，不处理会整篇乱码。</li>
 *   <li><b>docx / pptx</b>：本质是 zip + xml，用 JDK 自带的 {@code ZipInputStream}
 *       就能取出正文，无需第三方库。</li>
 *   <li><b>doc</b>（Word 97-2003）：OLE2 复合文档，容器用 POI 的 {@code poifs} 打开，
 *       Word 侧再按 FIB + piece table 取正文（见 {@link #extractDoc(byte[])}）。</li>
 * </ol>
 * 提取的是<b>纯文本</b>，会丢失排版/表格/图片 —— 对"快速看一眼作业内容"够用，
 * 想要完整还原版面请下载原文件。旧版 {@code .ppt} 是二进制格式且无可靠解析方案，
 * {@link FileViewType} 仍把它判为 {@code none}。
 * <p>
 * 安全：zip 有"解压炸弹"风险，因此限制单次解压总字节数；
 * 输入与输出都有上限，避免一个 200MB 的 docx 把服务打挂。
 */
@Slf4j
@Service
public class TextExtractService {

    /** 纯文本文件最大可读字节 */
    public static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    /** Office 文件最大可读字节（docx/pptx 压缩率高；.doc 不压缩，20MB 已经是很长的文档） */
    public static final int MAX_OFFICE_BYTES = 20 * 1024 * 1024;
    /** 单次解压累计字节上限，防解压炸弹 */
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    /** 返回给前端的最大字符数 */
    public static final int MAX_CHARS = 200_000;

    private static final Pattern XML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern TAB_TAG = Pattern.compile("<w:tab\\s*/>");
    private static final Pattern BREAK_TAG = Pattern.compile("<w:br\\s*/?>");

    /** 提取结果 */
    public record Extracted(String content, boolean truncated, String charset, String hint) {
    }

    /**
     * 提取正文。
     *
     * @param suffix 文件扩展名（小写）
     * @param bytes  文件内容（调用方需保证已按大小上限校验过）
     */
    public Extracted extract(String suffix, byte[] bytes) {
        String viewType = FileViewType.of(suffix);
        if (FileViewType.TEXT.equals(viewType)) {
            return extractPlainText(bytes);
        }
        if (FileViewType.OFFICE.equals(viewType)) {
            return extractOffice(suffix, bytes);
        }
        throw new BizException(ErrorCode.PREVIEW_NOT_SUPPORTED,
                "该文件类型不支持在线阅览，请下载后查看");
    }

    // ---------------------------------------------------------------- 纯文本

    private Extracted extractPlainText(byte[] bytes) {
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "文本文件超过 " + (MAX_TEXT_BYTES / 1024 / 1024) + "MB，请下载后查看");
        }
        CsvSupport.Decoded decoded = CsvSupport.decodeWithCharset(bytes);
        String content = decoded.text();
        boolean truncated = content.length() > MAX_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CHARS);
        }
        return new Extracted(content, truncated, decoded.charset(), null);
    }

    // ---------------------------------------------------------------- Office

    private Extracted extractOffice(String suffix, byte[] bytes) {
        if (bytes.length > MAX_OFFICE_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "文档超过 " + (MAX_OFFICE_BYTES / 1024 / 1024) + "MB，请下载后查看");
        }
        String normalized = suffix == null ? "" : suffix.trim().toLowerCase();
        String text = switch (normalized) {
            case "docx" -> extractDocx(bytes);
            case "doc" -> extractDoc(bytes);
            case "pptx" -> extractPptx(bytes);
            default -> throw new BizException(ErrorCode.PREVIEW_NOT_SUPPORTED,
                    "该 Office 格式暂不支持在线阅览，请下载后查看");
        };
        boolean truncated = text.length() > MAX_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_CHARS);
        }
        return new Extracted(text, truncated, "UTF-8",
                "已提取纯文本，排版与表格会丢失；需要完整版面请下载原文件");
    }

    /** docx：读 word/document.xml，按段落切分后去掉标签 */
    private String extractDocx(byte[] bytes) {
        String xml = readZipEntry(bytes, "word/document.xml");
        if (xml == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 Word 文档（.docx）");
        }
        StringBuilder out = new StringBuilder();
        // 段落之间用 \n 分隔，保留基本可读性
        for (String paragraph : xml.split("</w:p>")) {
            String line = toPlainText(paragraph);
            if (!line.isBlank()) {
                out.append(line).append('\n');
            }
        }
        if (out.length() == 0) {
            return "(文档没有可提取的文字内容，可能是纯图片或扫描件)";
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- doc（Word 97-2003）

    private static final int FIB_W_IDENT = 0xA5EC;
    private static final int FIB_F_ENCRYPTED = 0x0100;
    private static final int FIB_F_WHICH_TBL_STM = 0x0200;
    private static final int FIB_FC_MIN = 0x18;
    private static final int FIB_FC_MAC = 0x1C;
    private static final int FIB_CCP_TEXT = 0x4C;
    private static final int FIB_FC_CLX = 0x01A2;
    private static final int FIB_LCB_CLX = 0x01A6;
    /** 读到 lcbClx 所需的最小 FIB 长度 */
    private static final int FIB_MIN_BYTES = 0x01AA;

    /** 旧版 .doc 的 8 位正文字符集（Word 的 ANSI 代码页） */
    private static final Charset CP1252 = Charset.forName("windows-1252");

    /**
     * doc（Word 97-2003 二进制格式）取正文。
     * <p>
     * .doc 是 OLE2 复合文档，正文不在固定偏移上，得走三步：
     * <ol>
     *   <li>用 POI 的 <b>poifs</b>（{@code org.apache.poi.poifs}，在 poi 核心包里）打开容器，
     *       取出 {@code WordDocument} 与 {@code 0Table}/{@code 1Table} 两个流；</li>
     *   <li>读 FIB（File Information Block），拿到 {@code fcClx}/{@code lcbClx} 与 {@code ccpText}；</li>
     *   <li>按 Clx 里的 <b>piece table</b> 逐段取文字 —— 每段各自声明编码
     *       （8 位 CP1252 还是 UTF-16LE）和自己的字节偏移，
     *       所以"快速保存"过的、正文被切成多段的文档也能正确还原。</li>
     * </ol>
     * 容器解析交给 POI：CFB 的 FAT / MiniFAT / DIFAT 链很容易写错；
     * 而 Word 侧的 FIB + piece table 只是几十行偏移运算，自己写反而少一个
     * {@code poi-scratchpad} 依赖（本机离线，取不到该包；HWPF 在那个包里）。
     */
    private String extractDoc(byte[] bytes) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            byte[] word = readStream(fs, "WordDocument");
            if (word == null || word.length < FIB_MIN_BYTES || u16(word, 0x00) != FIB_W_IDENT) {
                throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 Word 文档（.doc）");
            }
            int flags = u16(word, 0x0A);
            if ((flags & FIB_F_ENCRYPTED) != 0) {
                throw new BizException(ErrorCode.PREVIEW_NOT_SUPPORTED,
                        "该 .doc 已设置密码保护，无法在线阅览，请下载后用 Word 打开");
            }
            int ccpText = i32(word, FIB_CCP_TEXT);
            byte[] table = readStream(fs, (flags & FIB_F_WHICH_TBL_STM) != 0 ? "1Table" : "0Table");

            String raw = null;
            int nFib = u16(word, 0x02);
            int fcClx = i32(word, FIB_FC_CLX);
            int lcbClx = i32(word, FIB_LCB_CLX);
            if (table != null && lcbClx > 0 && fcClx >= 0 && fcClx + lcbClx <= table.length) {
                raw = pieceTableText(word, table, fcClx, lcbClx, ccpText);
            }
            if (raw == null) {
                // 没有可用的 piece table。Word 6/95（nFib < 193）的 fcMin/fcMac 就是正文边界，
                // 且正文是 8 位；Word 97+ 官方规定<b>忽略</b>这两个字段，只有它们"看起来合理"时才兜底，
                // 否则宁可报错，也不要吐出一堆二进制乱码当成正文。
                if (nFib >= 193) {
                    log.warn("doc 的 piece table 不可用（lcbClx={}, Table 流={}），尝试按 fcMin/fcMac 兜底",
                            lcbClx, table == null ? "缺失" : "存在");
                }
                raw = simpleText(word, nFib >= 193, ccpText);
            }
            if (raw == null) {
                throw new BizException(ErrorCode.BAD_PARAM, "无法解析该 .doc 文件，结构可能已损坏");
            }
            String text = normalizeWordText(raw);
            if (text.isEmpty()) {
                return "(文档没有可提取的文字内容，可能是纯图片或扫描件)";
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (UnsupportedFileFormatException e) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "文件不是 Word 97-2003 格式（.doc），可能是 .docx 或其它格式");
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.BAD_PARAM, "无法解析该 .doc 文件，可能已加密或损坏");
        }
    }

    /** 从 OLE2 容器里按名字读一个流；该流不存在时返回 {@code null} */
    private static byte[] readStream(POIFSFileSystem fs, String name) throws IOException {
        DirectoryNode root = fs.getRoot();
        if (!root.hasEntry(name)) {
            return null;
        }
        try (DocumentInputStream in = root.createDocumentInputStream(name)) {
            return in.readAllBytes();
        }
    }

    /** 没有 piece table 的老文档：正文就是 WordDocument 流里 fcMin..fcMac 这一段 */
    private static String simpleText(byte[] word, boolean unicode, int ccpText) {
        int fcMin = i32(word, FIB_FC_MIN);
        int fcMac = i32(word, FIB_FC_MAC);
        if (fcMin < 0 || fcMac <= fcMin || fcMac > word.length) {
            return null;
        }
        int bytes = fcMac - fcMin;
        String text = unicode
                ? new String(word, fcMin, bytes - bytes % 2, StandardCharsets.UTF_16LE)
                : new String(word, fcMin, bytes, CP1252);
        return ccpText > 0 && ccpText < text.length() ? text.substring(0, ccpText) : text;
    }

    /**
     * Clx = 若干 Prc（0x01 + 2 字节长度）后跟一个 Pcdt（0x02 + 4 字节长度）。
     * Pcdt 里就是 piece table：{@code (n+1)} 个 4 字节 CP 边界 + {@code n} 个 8 字节 PCD。
     * <p>PCD 里的 fc 第 30 位是 fCompressed：置位表示这一段是 8 位 CP1252，
     * 且字节偏移是"字符偏移的一半"，所以要除以 2。
     * <p>CP 空间里前 {@code ccpText} 个字符才是正文，后面依次是脚注/页眉/批注/文本框，
     * 因此按 {@code ccpText} 截断，免得把脚注也当成正文吐出来。
     *
     * @return 提取到的原始正文；结构不符合预期时返回 {@code null}，由调用方回退
     */
    private static String pieceTableText(byte[] word, byte[] table, int fcClx, int lcbClx, int ccpText) {
        int end = fcClx + lcbClx;
        int p = fcClx;
        while (p < end) {
            int marker = table[p] & 0xFF;
            if (marker == 0x01) {
                if (p + 3 > end) {
                    return null;
                }
                p += 3 + u16(table, p + 1);
            } else if (marker == 0x02) {
                p++;
                break;
            } else {
                return null;
            }
        }
        if (p + 4 > end) {
            return null;
        }
        int lcb = i32(table, p);
        p += 4;
        if (lcb < 4 || p + lcb > end) {
            return null;
        }
        int pieces = (lcb - 4) / 12;
        if (pieces <= 0) {
            return null;
        }
        int cps = p;
        int pcds = p + (pieces + 1) * 4;
        int limit = ccpText > 0 ? ccpText : Integer.MAX_VALUE;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pieces && out.length() < limit; i++) {
            int count = i32(table, cps + (i + 1) * 4) - i32(table, cps + i * 4);
            if (count <= 0) {
                continue;
            }
            count = Math.min(count, limit - out.length());
            int fc = i32(table, pcds + i * 8 + 2);
            int offset = fc & 0x3FFFFFFF;
            if ((fc & 0x40000000) != 0) {
                offset /= 2;
                if (offset + count > word.length) {
                    return null;
                }
                out.append(new String(word, offset, count, CP1252));
            } else {
                if (offset + count * 2 > word.length) {
                    return null;
                }
                out.append(new String(word, offset, count * 2, StandardCharsets.UTF_16LE));
            }
        }
        return out.toString();
    }

    /**
     * Word 正文里夹着大量控制字符：段落标记 0x0D、单元格结束 0x07、换行 0x0B、分页 0x0C，
     * 域用 0x13/0x14/0x15 包裹（中间先是域代码、再是域结果），图片与图形占位是 0x01/0x08。
     * <p>这里把它们转成可读纯文本：段落标记变换行（最多留一个空行），
     * <b>域代码丢弃、域结果保留</b>（目录条目、页码这类"看得见的字"就在域结果里），其余控制字符清除。
     */
    private static String normalizeWordText(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int newlines = 0;
        boolean inFieldCode = false;
        for (int i = 0; i < raw.length() && out.length() <= MAX_CHARS; i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\u0007'
                    || c == '\u2028' || c == '\u2029') {
                if (newlines < 2) {
                    out.append('\n');
                }
                newlines++;
                continue;
            }
            if (c == '\u0013') {
                inFieldCode = true;
                continue;
            }
            if (c == '\u0014' || c == '\u0015') {
                inFieldCode = false;
                continue;
            }
            if (inFieldCode) {
                continue;
            }
            if (c == '\t') {
                out.append('\t');
                newlines = 0;
                continue;
            }
            if (c == '\u00A0') {
                out.append(' ');
                newlines = 0;
                continue;
            }
            if (c == '\u001E') {
                out.append('-');
                newlines = 0;
                continue;
            }
            if (c < 0x20 || c == '\u007F') {
                continue;
            }
            out.append(c);
            newlines = 0;
        }
        return out.toString().strip();
    }

    private static int u16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }

    /** pptx：按页码顺序读 ppt/slides/slideN.xml */
    private String extractPptx(byte[] bytes) {
        List<String> xmls = readZipEntriesByRegex(bytes, "^ppt/slides/slide\\d+\\.xml$");
        if (xmls.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 PowerPoint 文档（.pptx）");
        }
        // 保证按 slide2 在 slide10 之前
        xmls.sort(Comparator.comparingInt(this::numberIn));
        StringBuilder out = new StringBuilder();
        int page = 0;
        for (String xml : xmls) {
            page++;
            out.append("--- 第 ").append(page).append(" 页 ---\n");
            for (String paragraph : xml.split("</a:p>")) {
                String line = toPlainText(paragraph);
                if (!line.isBlank()) {
                    out.append(line).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** 去掉标签、还原转义字符 */
    private String toPlainText(String xmlFragment) {
        String text = TAB_TAG.matcher(xmlFragment).replaceAll("\t");
        text = BREAK_TAG.matcher(text).replaceAll("\n");
        text = XML_TAG.matcher(text).replaceAll("");
        return unescapeXml(text).trim();
    }

    private String unescapeXml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int end = text.indexOf(';', i);
            if (end < 0 || end - i > 10) {
                out.append(c);
                i++;
                continue;
            }
            String entity = text.substring(i + 1, end);
            switch (entity) {
                case "amp" -> out.append('&');
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "quot" -> out.append('"');
                case "apos" -> out.append('\'');
                case "nbsp" -> out.append(' ');
                default -> {
                    if (entity.startsWith("#x") || entity.startsWith("#X")) {
                        try {
                            out.append((char) Integer.parseInt(entity.substring(2), 16));
                        } catch (NumberFormatException e) {
                            out.append('&').append(entity).append(';');
                        }
                    } else if (entity.startsWith("#")) {
                        try {
                            out.append((char) Integer.parseInt(entity.substring(1)));
                        } catch (NumberFormatException e) {
                            out.append('&').append(entity).append(';');
                        }
                    } else {
                        out.append('&').append(entity).append(';');
                    }
                }
            }
            i = end + 1;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- zip 读取

    /** 读取指定 entry 的文本内容；不存在返回 null */
    private String readZipEntry(byte[] bytes, String entryName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                total += entry.getSize() > 0 ? entry.getSize() : 0;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new BizException(ErrorCode.BAD_PARAM, "文档解压后过大，已停止解析");
                }
                if (entryName.equals(entry.getName())) {
                    return readEntryText(zip);
                }
            }
        } catch (IOException e) {
            log.warn("读取 zip entry 失败 entry={} err={}", entryName, e.getMessage());
            return null;
        }
        return null;
    }

    /** 读取所有名称匹配的 entry（按名称里的数字升序） */
    private List<String> readZipEntriesByRegex(byte[] bytes, String nameRegex) {
        Pattern pattern = Pattern.compile(nameRegex);
        List<int[]> order = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                total += entry.getSize() > 0 ? entry.getSize() : 0;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new BizException(ErrorCode.BAD_PARAM, "文档解压后过大，已停止解析");
                }
                if (pattern.matcher(entry.getName()).matches()) {
                    String content = readEntryText(zip);
                    if (content != null) {
                        order.add(new int[]{numberIn(entry.getName()), contents.size()});
                        contents.add(content);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取 pptx slides 失败 err={}", e.getMessage());
            return List.of();
        }
        // 按名称里的数字排序（slide2 要在 slide10 之前）
        order.sort(Comparator.comparingInt(a -> a[0]));
        List<String> sorted = new ArrayList<>(contents.size());
        for (int[] item : order) {
            sorted.add(contents.get(item[1]));
        }
        return sorted;
    }

    private int numberIn(String name) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(name);
        int last = 0;
        while (matcher.find()) {
            try {
                last = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // 忽略
            }
        }
        return last;
    }

    /** 读一个 entry 的全部字节并转成 UTF-8 字符串 */
    private String readEntryText(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long read = 0;
        int len;
        while ((len = zip.read(chunk)) > 0) {
            read += len;
            if (read > MAX_UNCOMPRESSED_BYTES) {
                throw new BizException(ErrorCode.BAD_PARAM, "文档解压后过大，已停止解析");
            }
            buffer.write(chunk, 0, len);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
