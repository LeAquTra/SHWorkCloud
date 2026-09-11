package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 文件名处理：清洗、重名追加序号、扩展名解析。
 * <p>
 * 后缀的"是什么类型"判定统一由 {@link FileViewType} 负责，本类只做转发，
 * 避免出现两份后缀白名单。
 * <p>抽成独立类是为了可单元测试（不依赖 Spring 容器与数据库）。
 */
public final class FileNaming {

    private static final int MAX_NAME_LENGTH = 255;

    /** 非法字符：Windows 与 URL 语义下都会出问题的那些 */
    private static final String ILLEGAL_CHARS = "<>:\"/\\|?*";

    /** Windows 保留设备名（不区分大小写、不带扩展名） */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNaming() {
    }

    /**
     * 清洗文件名。
     * <p>去掉控制字符与非法字符、去掉首尾空格与结尾的点（Windows 语义），
     * 并拦截 Windows 保留设备名。
     */
    public static String sanitize(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            throw new BizException(ErrorCode.ILLEGAL_FILE_NAME);
        }
        // 只取最后一段，防止传入 a/b/c.txt 造成路径穿越
        String name = rawName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }

        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            if (ILLEGAL_CHARS.indexOf(c) >= 0) {
                continue;
            }
            sb.append(c);
        }
        name = sb.toString().trim();
        // 去掉结尾的点（Windows 不允许以点结尾）
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.ILLEGAL_FILE_NAME);
        }
        if (isReserved(name)) {
            throw new BizException(ErrorCode.ILLEGAL_FILE_NAME, "文件名 " + name + " 为系统保留名");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            // 截断时保留扩展名
            String ext = extension(name);
            int keep = MAX_NAME_LENGTH - (ext.isEmpty() ? 0 : ext.length() + 1);
            name = name.substring(0, Math.max(1, keep)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return name;
    }

    private static boolean isReserved(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        String base = dot > 0 ? upper.substring(0, dot) : upper;
        return RESERVED_NAMES.contains(base);
    }

    /** 小写扩展名（不含点）；无扩展名返回空串 */
    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 生成第 index 个候选名：{@code 作业.docx -> 作业(1).docx}。
     *
     * @param index 从 1 开始
     */
    public static String appendIndex(String name, int index) {
        String ext = extension(name);
        if (ext.isEmpty()) {
            return name + "(" + index + ")";
        }
        String base = name.substring(0, name.length() - ext.length() - 1);
        return base + "(" + index + ")." + ext;
    }

    // ---------------------------------------------------------------- 类型判定
    // 以下四个方法统一转发到 FileViewType，保证全项目只有一份后缀白名单。

    /** 是否可以在线阅览 */
    public static boolean previewable(String suffix) {
        return FileViewType.viewable(suffix);
    }

    /** 是否可安全内联的位图（jpg/jpeg/png/gif/webp/bmp） */
    public static boolean isRasterImage(String suffix) {
        return FileViewType.isRasterImage(suffix);
    }

    /** 分类筛选：把 suffix 映射为 image / document / video / audio / other */
    public static String category(String suffix) {
        return FileViewType.category(suffix);
    }

    /** 某个分类对应的后缀集合，供 SQL IN 查询使用 */
    public static Set<String> suffixesOf(String category) {
        return FileViewType.suffixesOfCategory(category);
    }

    /** 全部已知分类后缀的并集，用于表达「其他」分类 */
    public static Set<String> allKnownSuffixes() {
        return FileViewType.allKnownSuffixes();
    }
}
