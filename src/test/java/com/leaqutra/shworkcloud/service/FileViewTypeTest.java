package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件可阅览类型判定。
 * <p>这是前端渲染分流的依据：判错了前端就会拿图片去调文本接口，或者把 SVG 当图片内联（XSS 风险）。
 */
class FileViewTypeTest {

    @Test
    @DisplayName("图片：jpg/jpeg/png/gif/webp/bmp 都可在线预览")
    void rasterImages() {
        for (String suffix : new String[]{"jpg", "jpeg", "png", "gif", "webp", "bmp"}) {
            assertEquals(FileViewType.IMAGE, FileViewType.of(suffix), suffix);
            assertTrue(FileViewType.isRasterImage(suffix), suffix);
        }
    }

    @Test
    @DisplayName("大小写与空格不敏感")
    void caseInsensitive() {
        assertEquals(FileViewType.IMAGE, FileViewType.of("JPG"));
        assertEquals(FileViewType.IMAGE, FileViewType.of("  Png  "));
        assertEquals(FileViewType.PDF, FileViewType.of("PDF"));
    }

    @Test
    @DisplayName("SVG/ICO 算图片分类，但不参与在线预览（SVG 可带脚本）")
    void svgIsImageCategoryButNotPreviewable() {
        assertEquals(FileViewType.NONE, FileViewType.of("svg"));
        assertEquals(FileViewType.NONE, FileViewType.of("ico"));
        assertFalse(FileViewType.isRasterImage("svg"));
        // 分类筛选里仍算"图片"，否则用户在"图片"分类里找不到 svg 文件
        assertEquals("image", FileViewType.category("svg"));
        assertTrue(FileViewType.categoryImageSuffixes().contains("svg"));
        // 相册只收可预览的位图
        assertFalse(FileViewType.gallerySuffixes().contains("svg"));
        assertTrue(FileViewType.gallerySuffixes().contains("png"));
    }

    @Test
    @DisplayName("PDF / 视频 / 音频 / 文本 / Office 各自归类")
    void otherTypes() {
        assertEquals(FileViewType.PDF, FileViewType.of("pdf"));
        assertEquals(FileViewType.VIDEO, FileViewType.of("mp4"));
        assertEquals(FileViewType.AUDIO, FileViewType.of("mp3"));
        assertEquals(FileViewType.TEXT, FileViewType.of("txt"));
        assertEquals(FileViewType.TEXT, FileViewType.of("md"));
        assertEquals(FileViewType.TEXT, FileViewType.of("json"));
        assertEquals(FileViewType.TEXT, FileViewType.of("java"));
        assertEquals(FileViewType.OFFICE, FileViewType.of("docx"));
        assertEquals(FileViewType.OFFICE, FileViewType.of("doc"));
        assertEquals(FileViewType.OFFICE, FileViewType.of("pptx"));
        // xlsx 也是 zip+xml，可渲染成 HTML 表格，归入 office
        assertEquals(FileViewType.OFFICE, FileViewType.of("xlsx"));
    }

    @Test
    @DisplayName("传输大小档位：视频与压缩包单独成档，其余用全局上限")
    void transferClass() {
        assertEquals("video", FileViewType.transferClass("mp4"));
        assertEquals("video", FileViewType.transferClass("MKV"));
        assertEquals("archive", FileViewType.transferClass("zip"));
        assertEquals("archive", FileViewType.transferClass("7z"));
        assertEquals("archive", FileViewType.transferClass("rar"));
        // 其余（图片/文档/文本…）没有单独档位 → 用全局 max-file-size-bytes
        assertNull(FileViewType.transferClass("png"));
        assertNull(FileViewType.transferClass("docx"));
        assertNull(FileViewType.transferClass(null));

        assertTrue(FileViewType.isArchive("zip"));
        assertFalse(FileViewType.isArchive("mp4"));
    }

    @Test
    @DisplayName("旧版 .ppt 与可执行文件、压缩包不支持在线阅览")
    void unsupported() {
        assertEquals(FileViewType.NONE, FileViewType.of("ppt"));
        assertEquals(FileViewType.NONE, FileViewType.of("xls"));
        assertEquals(FileViewType.NONE, FileViewType.of("zip"));
        assertEquals(FileViewType.NONE, FileViewType.of("exe"));
        assertEquals(FileViewType.NONE, FileViewType.of(null));
        assertEquals(FileViewType.NONE, FileViewType.of(""));
    }

    @Test
    @DisplayName("渲染通道：哪些走流式预览、哪些走文本接口")
    void channels() {
        assertTrue(FileViewType.streamable(FileViewType.IMAGE));
        // PDF 刻意不支持在线预览（需求）：它仍被识别为 pdf 类型（图标/分类要用），
        // 但既不走流式预览，也不可点开
        assertFalse(FileViewType.streamable(FileViewType.PDF));
        assertTrue(FileViewType.streamable(FileViewType.VIDEO));
        assertTrue(FileViewType.streamable(FileViewType.AUDIO));
        assertFalse(FileViewType.streamable(FileViewType.TEXT));
        assertFalse(FileViewType.streamable(FileViewType.OFFICE));

        assertTrue(FileViewType.extractable(FileViewType.TEXT));
        assertTrue(FileViewType.extractable(FileViewType.OFFICE));
        assertFalse(FileViewType.extractable(FileViewType.IMAGE));
    }

    @Test
    @DisplayName("PDF：仍被识别为 pdf 类型，但明确不可在线预览")
    void pdfIsIdentifiedButNotPreviewable() {
        // 类型判定保留：分类筛选、文件图标、Content-Type 映射都还要用
        assertEquals(FileViewType.PDF, FileViewType.of("pdf"));
        assertEquals("application/pdf", FileViewType.inlineContentType("pdf"));
        assertEquals("document", FileViewType.category("pdf"));
        // 但"能不能开"为否 —— 点击整行不弹窗、下拉框里也不出现"在线预览"
        assertFalse(FileViewType.viewable("pdf"));
        assertFalse(FileViewType.streamable(FileViewType.PDF));
        // 其它可预览类型不受影响
        assertTrue(FileViewType.viewable("jpg"));
        assertTrue(FileViewType.viewable("docx"));
        assertTrue(FileViewType.viewable("xlsx"));
    }

    @Test
    @DisplayName("viewable 与 previewable 口径一致（单一来源，不能各判一套）")
    void viewableMatchesNaming() {
        for (String suffix : new String[]{"pdf", "jpg", "docx", "xlsx", "mp4", "zip", "exe"}) {
            assertEquals(FileViewType.viewable(suffix), FileNaming.previewable(suffix),
                    "两个入口对 " + suffix + " 的判断必须一致");
        }
    }

    @Test
    @DisplayName("Range 支持：视频/音频/PDF 可拖动进度条")
    void rangeSupport() {
        assertTrue(FileViewType.rangeSupported(FileViewType.VIDEO));
        assertTrue(FileViewType.rangeSupported(FileViewType.AUDIO));
        assertTrue(FileViewType.rangeSupported(FileViewType.PDF));
        assertFalse(FileViewType.rangeSupported(FileViewType.IMAGE));
    }

    @Test
    @DisplayName("内联 Content-Type 由服务端映射，未知类型一律 octet-stream")
    void inlineContentType() {
        assertEquals("image/jpeg", FileViewType.inlineContentType("jpg"));
        assertEquals("image/jpeg", FileViewType.inlineContentType("jpeg"));
        assertEquals("image/png", FileViewType.inlineContentType("png"));
        assertEquals("application/pdf", FileViewType.inlineContentType("pdf"));
        assertEquals("video/mp4", FileViewType.inlineContentType("mp4"));
        assertEquals("audio/mpeg", FileViewType.inlineContentType("mp3"));
        // 关键：html/svg 之类绝不能映射成可执行类型
        assertEquals("application/octet-stream", FileViewType.inlineContentType("html"));
        assertEquals("application/octet-stream", FileViewType.inlineContentType("svg"));
        assertEquals("application/octet-stream", FileViewType.inlineContentType(null));
    }

    @Test
    @DisplayName("分类筛选集合与原 FileNaming 行为一致（单一来源）")
    void singleSourceOfTruth() {
        assertEquals(FileViewType.category("png"), FileNaming.category("png"));
        assertEquals(FileViewType.category("exe"), FileNaming.category("exe"));
        assertEquals(FileViewType.viewable("png"), FileNaming.previewable("png"));
        assertEquals(FileViewType.viewable("svg"), FileNaming.previewable("svg"));
        assertEquals(FileViewType.suffixesOfCategory("image"), FileNaming.suffixesOf("image"));
        assertEquals(FileViewType.allKnownSuffixes(), FileNaming.allKnownSuffixes());
    }

    @Test
    @DisplayName("「其他」分类的已知后缀并集包含各大类代表")
    void knownSuffixes() {
        var known = FileViewType.allKnownSuffixes();
        assertTrue(known.contains("png"));
        assertTrue(known.contains("docx"));
        assertTrue(known.contains("doc"));
        assertTrue(known.contains("mp4"));
        assertTrue(known.contains("mp3"));
        assertTrue(known.contains("pdf"));
        assertFalse(known.contains("exe"));
    }
}
