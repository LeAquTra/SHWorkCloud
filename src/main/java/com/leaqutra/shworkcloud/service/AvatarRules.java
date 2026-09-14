package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Locale;

/**
 * 自定义头像的校验规则。
 * <p>
 * 安全要点：<b>不信任文件名后缀，也不信任 Content-Type</b>（两者都由客户端提供）。
 * 判定方式是先看文件头的 magic bytes，再用 JDK 的 ImageIO 真正解析一次。
 * <p>
 * 用 {@code ImageReader.getWidth/getHeight} 而不是 {@code ImageIO.read}：
 * 后者会把像素全部解码到内存，一张 5MB 的 PNG 可以解压成几 GB（"解压炸弹"），
 * 足以把服务打挂。只取尺寸不解码像素就能避免这个问题。
 */
public final class AvatarRules {

    /** 用户要求：小于 5MB。这里允许到 5MB 整（更宽松不会误伤用户） */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 边长上限，防止超大图占用过多内存与带宽 */
    public static final int MAX_DIMENSION = 4096;

    /**
     * 头像修改的最小间隔（小时）。
     * <p>
     * 防的是"恶意用户拿头像当免费图床 / 反复换图骚扰"：每次换头像都会往 OSS
     * 写一个新对象并删掉旧的，无限次刷会持续消耗存储与请求额度，
     * 也把对账任务要扫的对象量推高。24 小时是"日常够用、刷子难受"的折中。
     * <p>更换与清除<b>共用</b>这个间隔 —— 否则"换了立刻清掉再换"就把限制绕过去了。
     */
    public static final int CHANGE_INTERVAL_HOURS = 24;

    /**
     * 距离下次可修改头像的时间点；已经可以改时返回 {@code null}。
     * <p>抽成纯函数便于单测，也让"能否修改"的判断在服务端与前端展示之间只有一份口径。
     */
    public static LocalDateTime nextChangeableAt(LocalDateTime lastChangedAt) {
        return lastChangedAt == null ? null : lastChangedAt.plusHours(CHANGE_INTERVAL_HOURS);
    }

    /**
     * 校验现在是否可以修改头像。
     *
     * @param lastChangedAt 上次修改时间（含清除），从未改过传 {@code null}
     * @return 下次可修改时间；为 {@code null} 表示从未改过（现在就可以）
     * @throws BizException 还在冷却期内，message 里带上还剩多久
     */
    public static LocalDateTime ensureChangeAllowed(LocalDateTime lastChangedAt) {
        LocalDateTime next = nextChangeableAt(lastChangedAt);
        if (next == null || !next.isAfter(LocalDateTime.now())) {
            return next;
        }
        long hours = java.time.Duration.between(LocalDateTime.now(), next).toHours() + 1;
        throw new BizException(ErrorCode.AVATAR_CHANGE_TOO_FREQUENT,
                "头像每 " + CHANGE_INTERVAL_HOURS + " 小时只能修改一次，约 " + hours
                        + " 小时后可再次修改（" + next.toLocalDate() + " "
                        + next.toLocalTime().withNano(0).withSecond(0) + " 之后）");
    }

    public static final String FORMAT_PNG = "png";
    public static final String FORMAT_JPEG = "jpeg";

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private AvatarRules() {
    }

    /** 解析出的图片信息 */
    public record ImageInfo(String format, int width, int height) {

        /** 落 OSS 时用的扩展名 */
        public String extension() {
            return FORMAT_JPEG.equals(format) ? "jpg" : "png";
        }

        /** 落 OSS 时用的 Content-Type */
        public String contentType() {
            return FORMAT_JPEG.equals(format) ? "image/jpeg" : "image/png";
        }
    }

    /** 大小校验 */
    public static void validateSize(long size) {
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_PARAM, "头像文件为空");
        }
        if (size > MAX_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "头像不能超过 " + (MAX_BYTES / 1024 / 1024) + "MB");
        }
    }

    /**
     * 按 magic bytes 判断图片格式（纯函数，便于单测）。
     *
     * @return {@code png} / {@code jpeg}；无法识别返回 null
     */
    public static String detectFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return FORMAT_PNG;
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return FORMAT_JPEG;
        }
        return null;
    }

    /**
     * 完整校验：格式必须是 jpg/png，能被真正解析，且尺寸在限制内。
     * <p>只读元数据、不解码像素。
     */
    public static ImageInfo inspect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_PARAM, "头像文件为空");
        }
        validateSize(bytes.length);

        String magic = detectFormat(bytes);
        if (magic == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "头像只支持 JPG 或 PNG 格式");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new BizException(ErrorCode.BAD_PARAM, "头像文件无法读取");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BizException(ErrorCode.BAD_PARAM, "头像文件不是有效的图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = normalizeFormat(reader.getFormatName());
                if (format == null) {
                    throw new BizException(ErrorCode.BAD_PARAM, "头像只支持 JPG 或 PNG 格式");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new BizException(ErrorCode.BAD_PARAM, "头像尺寸异常");
                }
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new BizException(ErrorCode.BAD_PARAM,
                            "头像尺寸不能超过 " + MAX_DIMENSION + "×" + MAX_DIMENSION + " 像素");
                }
                // magic bytes 与实际解码格式必须一致，防止格式伪装
                if (!format.equals(magic)) {
                    throw new BizException(ErrorCode.BAD_PARAM, "头像文件格式不一致，请重新导出后上传");
                }
                return new ImageInfo(format, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_PARAM, "头像文件损坏，无法解析");
        }
    }

    /** 只接受 jpg / png 两种 */
    private static String normalizeFormat(String formatName) {
        if (formatName == null) {
            return null;
        }
        String value = formatName.toLowerCase(Locale.ROOT);
        if (value.contains("png")) {
            return FORMAT_PNG;
        }
        if (value.contains("jpg") || value.contains("jpeg")) {
            return FORMAT_JPEG;
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
