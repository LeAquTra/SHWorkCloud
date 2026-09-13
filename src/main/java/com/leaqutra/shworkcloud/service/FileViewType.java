package com.leaqutra.shworkcloud.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 文件「可阅览类型」判定 —— <b>全项目唯一的分类来源</b>。
 * <p>
 * 为什么要有这个类：前端需要知道"这个文件该怎么展示"，如果前后端各写一份后缀白名单，
 * 迟早会不一致（后端说能预览、前端没实现，或者反过来）。
 * 所以后端在文件列表里直接下发 {@code viewType}，前端按 viewType 分流即可。
 * <p>
 * viewType 取值：
 * <table border="1">
 *   <tr><th>值</th><th>渲染方式</th><th>接口</th></tr>
 *   <tr><td>{@code image}</td><td>{@code <img>} 或 {@code <el-image>}</td>
 *       <td>{@code preview-url} 签名地址，或 {@code GET /files/{id}/preview} 流式</td></tr>
 *   <tr><td>{@code pdf}</td><td>浏览器内置 PDF 阅读器 / pdf.js</td><td>同上</td></tr>
 *   <tr><td>{@code video}</td><td>{@code <video>}（支持拖动进度条）</td><td>同上（服务端支持 Range）</td></tr>
 *   <tr><td>{@code audio}</td><td>{@code <audio>}</td><td>同上</td></tr>
 *   <tr><td>{@code text}</td><td>代码/文本查看器</td>
 *       <td>{@code GET /files/{id}/text}（返回已解码文本，自动处理 GBK/BOM）</td></tr>
 *   <tr><td>{@code office}</td><td>文本视图（提取正文）：doc / docx / pptx</td><td>{@code GET /files/{id}/text}</td></tr>
 *   <tr><td>{@code none}</td><td>只能下载</td><td>—</td></tr>
 * </table>
 * <p>
 * ⚠️ {@code svg}/{@code ico} 属于图像文件（分类筛选算"图片"），但
 * <b>不参与在线预览</b>：SVG 内部可以带脚本，内联到本站源等于给自己开了 XSS。
 */
public final class FileViewType {

    public static final String IMAGE = "image";
    public static final String PDF = "pdf";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";
    public static final String TEXT = "text";
    public static final String OFFICE = "office";
    public static final String NONE = "none";

    /** 可安全内联显示的位图格式（需求要求的 jpg/png 在这里） */
    private static final Set<String> RASTER_IMAGE =
            ordered("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 属于"图片"分类但不可内联（SVG 可带脚本） */
    private static final Set<String> UNSAFE_IMAGE = ordered("svg", "ico");

    private static final Set<String> PDF_SET = ordered("pdf");

    private static final Set<String> TEXT_SET = ordered(
            "txt", "text", "md", "markdown", "csv", "tsv", "json", "xml", "yml", "yaml",
            "log", "ini", "conf", "cfg", "properties", "sql", "html", "htm", "css", "scss",
            "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue",
            "java", "kt", "py", "rb", "go", "rs", "php", "c", "h", "cpp", "hpp", "cs",
            "sh", "bash", "bat", "cmd", "ps1");

    /**
     * 可提取正文的 Office 格式。
     * <p>docx/pptx 本质是 zip+xml，用 JDK 自带的 zip 解析即可；
     * doc 是 OLE2 二进制格式，容器交给 Apache POI 的 {@code poifs}，
     * Word 侧的 FIB + piece table 由 {@link TextExtractService} 自己解析。
     * <p>旧版 {@code .ppt} 是二进制格式且没有可靠的无依赖解析方案，不在集合里。
     * <p>xlsx 同样是 zip+xml，可渲染成 HTML 表格；旧版 {@code .xls}（二进制）不在集合里。
     */
    private static final Set<String> OFFICE_SET = ordered("doc", "docx", "pptx", "xlsx");

    /** 压缩包：与视频一起走「单独配置的传输上限」（见 app.upload.transfer-limits） */
    private static final Set<String> ARCHIVE_SET = ordered(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso");

    /**
     * 传输大小档位：用于查 {@code app.upload.transfer-limits} 里的分类上限。
     * <p>返回 {@code null} 表示该后缀没有单独档位，用全局的
     * {@code app.upload.max-file-size-bytes}。
     * <p>刻意<b>不动</b> {@link #category}：分类值（image/document/video/audio/other）
     * 是前端筛选用的公开契约，加一个新分类会牵动界面；这里只做一个独立的判定。
     */
    public static String transferClass(String suffix) {
        String s = normalize(suffix);
        if (VIDEO_SET.contains(s)) {
            return "video";
        }
        if (ARCHIVE_SET.contains(s)) {
            return "archive";
        }
        return null;
    }

    public static boolean isArchive(String suffix) {
        return ARCHIVE_SET.contains(normalize(suffix));
    }

    private static final Set<String> VIDEO_SET = ordered(
            "mp4", "webm", "mov", "m4v", "ogv", "avi", "mkv", "wmv", "flv");

    private static final Set<String> AUDIO_SET = ordered(
            "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "wma", "opus");

    private static final Set<String> DOCUMENT_SET = new LinkedHashSet<>();

    static {
        DOCUMENT_SET.addAll(ordered(
                "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "pdf", "txt", "md", "csv", "rtf", "odt", "ods", "odp", "wps", "et", "dps"));
    }

    private FileViewType() {
    }

    /** 小工具：保持顺序（分类查询生成 IN 列表时可读） */
    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(java.util.List.of(values));
    }

    private static String normalize(String suffix) {
        return suffix == null ? "" : suffix.trim().toLowerCase(Locale.ROOT);
    }

    /** 判定该后缀应如何在线阅览 */
    public static String of(String suffix) {
        String s = normalize(suffix);
        if (s.isEmpty()) {
            return NONE;
        }
        if (RASTER_IMAGE.contains(s)) {
            return IMAGE;
        }
        if (PDF_SET.contains(s)) {
            return PDF;
        }
        if (VIDEO_SET.contains(s)) {
            return VIDEO;
        }
        if (AUDIO_SET.contains(s)) {
            return AUDIO;
        }
        if (TEXT_SET.contains(s)) {
            return TEXT;
        }
        if (OFFICE_SET.contains(s)) {
            return OFFICE;
        }
        return NONE;
    }

    /** 是否可以在线阅览（任何非 none 的类型） */
    public static boolean viewable(String suffix) {
        return !NONE.equals(of(suffix));
    }

    /** 是否用「流式预览」接口（{@code GET /files/{id}/preview}），而不是 /text */
    public static boolean streamable(String viewType) {
        return IMAGE.equals(viewType) || PDF.equals(viewType)
                || VIDEO.equals(viewType) || AUDIO.equals(viewType);
    }

    /** 是否用「文本提取」接口（{@code GET /files/{id}/text}） */
    public static boolean extractable(String viewType) {
        return TEXT.equals(viewType) || OFFICE.equals(viewType);
    }

    /** 是否支持 Range（视频/音频拖动进度条） */
    public static boolean rangeSupported(String viewType) {
        return VIDEO.equals(viewType) || AUDIO.equals(viewType) || PDF.equals(viewType);
    }

    /** 是否为可安全内联的位图 */
    public static boolean isRasterImage(String suffix) {
        return RASTER_IMAGE.contains(normalize(suffix));
    }

    /** 分类筛选用的"图片"后缀集合（含 SVG/ICO：它们是图片，只是不预览） */
    public static Set<String> categoryImageSuffixes() {
        Set<String> all = new LinkedHashSet<>(RASTER_IMAGE);
        all.addAll(UNSAFE_IMAGE);
        return all;
    }

    /** 图片管理（相册）用的后缀集合：只含可在线预览的位图 */
    public static Set<String> gallerySuffixes() {
        return RASTER_IMAGE;
    }

    public static Set<String> videoSuffixes() {
        return VIDEO_SET;
    }

    public static Set<String> audioSuffixes() {
        return AUDIO_SET;
    }

    public static Set<String> textSuffixes() {
        return TEXT_SET;
    }

    public static Set<String> officeSuffixes() {
        return OFFICE_SET;
    }

    /**
     * 内联响应使用的 Content-Type。
     * <p><b>刻意由服务端映射，绝不回显数据库里存的 contentType</b>：
     * 那个值是上传时客户端给的，用户可以传一个 .png 声明成 text/html，
     * 一旦内联返回，就在我们的源上执行了脚本。
     */
    public static String inlineContentType(String suffix) {
        String s = normalize(suffix);
        return switch (s) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "ogv" -> "video/ogg";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga", "opus" -> "audio/ogg";
            case "m4a", "aac" -> "audio/mp4";
            case "flac" -> "audio/flac";
            default -> "application/octet-stream";
        };
    }

    // ---------------------------------------------------------------- 分类筛选

    /** 分类筛选：image / document / video / audio / other */
    public static String category(String suffix) {
        String s = normalize(suffix);
        if (s.isEmpty()) {
            return "other";
        }
        if (RASTER_IMAGE.contains(s) || UNSAFE_IMAGE.contains(s)) {
            return "image";
        }
        if (DOCUMENT_SET.contains(s)) {
            return "document";
        }
        if (VIDEO_SET.contains(s)) {
            return "video";
        }
        if (AUDIO_SET.contains(s)) {
            return "audio";
        }
        return "other";
    }

    /** 某个分类对应的后缀集合（供 SQL IN 使用）；未知分类返回空集合 */
    public static Set<String> suffixesOfCategory(String category) {
        return switch (category == null ? "" : category) {
            case "image" -> categoryImageSuffixes();
            case "document" -> DOCUMENT_SET;
            case "video" -> VIDEO_SET;
            case "audio" -> AUDIO_SET;
            default -> Set.of();
        };
    }

    /** 全部已知分类后缀的并集，用于表达「其他」分类 */
    public static Set<String> allKnownSuffixes() {
        Set<String> all = new LinkedHashSet<>(categoryImageSuffixes());
        all.addAll(DOCUMENT_SET);
        all.addAll(VIDEO_SET);
        all.addAll(AUDIO_SET);
        return all;
    }
}
