package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNamingTest {

    @Test
    @DisplayName("清洗非法字符与控制字符")
    void sanitizeRemovesIllegalChars() {
        assertEquals("ab.txt", FileNaming.sanitize("a<b>.txt"));
        assertEquals("ab.txt", FileNaming.sanitize("a|b.txt"));
        assertEquals("ab.txt", FileNaming.sanitize("a\u0000b.txt"));
        assertEquals("作业.docx", FileNaming.sanitize("  作业.docx  "));
    }

    @Test
    @DisplayName("路径穿越：只保留最后一段")
    void sanitizeStripsPath() {
        assertEquals("passwd", FileNaming.sanitize("../../etc/passwd"));
        assertEquals("x.txt", FileNaming.sanitize("..\\..\\windows\\x.txt"));
    }

    @Test
    @DisplayName("去掉结尾的点（Windows 语义）")
    void sanitizeTrimsTrailingDots() {
        assertEquals("name", FileNaming.sanitize("name..."));
    }

    @Test
    @DisplayName("拦截 Windows 保留设备名")
    void sanitizeRejectsReservedNames() {
        assertThrows(BizException.class, () -> FileNaming.sanitize("CON"));
        assertThrows(BizException.class, () -> FileNaming.sanitize("com1.txt"));
        assertThrows(BizException.class, () -> FileNaming.sanitize("LPT9"));
    }

    @Test
    @DisplayName("空名或纯非法字符报错")
    void sanitizeRejectsEmpty() {
        assertThrows(BizException.class, () -> FileNaming.sanitize(null));
        assertThrows(BizException.class, () -> FileNaming.sanitize("   "));
        assertThrows(BizException.class, () -> FileNaming.sanitize("///"));
    }

    @Test
    @DisplayName("超长名称截断且保留扩展名")
    void sanitizeTruncatesKeepingExtension() {
        String longName = "a".repeat(300) + ".docx";
        String result = FileNaming.sanitize(longName);
        assertTrue(result.length() <= 255);
        assertTrue(result.endsWith(".docx"));
    }

    @Test
    @DisplayName("重名候选：作业.docx -> 作业(1).docx")
    void appendIndex() {
        assertEquals("作业(1).docx", FileNaming.appendIndex("作业.docx", 1));
        assertEquals("作业(12).docx", FileNaming.appendIndex("作业.docx", 12));
        assertEquals("noext(1)", FileNaming.appendIndex("noext", 1));
        // 只替换最后一个扩展名
        assertEquals("a.b(1).c", FileNaming.appendIndex("a.b.c", 1));
    }

    @Test
    @DisplayName("扩展名解析")
    void extension() {
        assertEquals("pdf", FileNaming.extension("作业.PDF"));
        assertEquals("", FileNaming.extension("noext"));
        assertEquals("", FileNaming.extension(".hidden"));
        assertEquals("", FileNaming.extension("trailing."));
        assertEquals("", FileNaming.extension(null));
    }

    @Test
    @DisplayName("在线阅览：位图/PDF/文本/Office 可阅览；可执行文件与未知类型不可")
    void previewable() {
        assertTrue(FileNaming.previewable("jpg"));
        assertTrue(FileNaming.previewable("PNG"));
        assertTrue(FileNaming.previewable("pdf"));
        // 文本与 Office 走 /files/{id}/text 正文通道（返回 JSON 文本，绝不内联）
        assertTrue(FileNaming.previewable("txt"));
        assertTrue(FileNaming.previewable("docx"));
        // 旧版 .doc 也是 Office 正文通道（服务端按 FIB + piece table 提取）
        assertTrue(FileNaming.previewable("doc"));
        assertFalse(FileNaming.previewable("ppt"));
        // SVG 是图片但不参与预览：内部可带脚本，内联到本站源等于开了 XSS
        assertFalse(FileNaming.previewable("svg"));
        assertFalse(FileNaming.previewable("exe"));
        assertFalse(FileNaming.previewable("zip"));
        assertFalse(FileNaming.previewable(null));
        // ⚠️ 注意区分：「可在线阅览」≠「可内联渲染」。
        // html/js 属于文本，可通过 /text 返回，但绝不能内联 ——
        // 内联白名单由 FileViewType.streamable / inlineContentType 把关（见 FileViewTypeTest）。
    }

    @Test
    @DisplayName("分类映射")
    void category() {
        assertEquals("image", FileNaming.category("png"));
        assertEquals("document", FileNaming.category("docx"));
        assertEquals("video", FileNaming.category("mp4"));
        assertEquals("audio", FileNaming.category("mp3"));
        assertEquals("other", FileNaming.category("zip"));
        assertEquals("other", FileNaming.category(null));
    }

    @Test
    @DisplayName("已知分类后缀并集包含四大类的代表后缀")
    void allKnownSuffixes() {
        var all = FileNaming.allKnownSuffixes();
        assertTrue(all.contains("png"));
        assertTrue(all.contains("docx"));
        assertTrue(all.contains("mp4"));
        assertTrue(all.contains("mp3"));
        assertFalse(all.contains("zip"));
    }
}
