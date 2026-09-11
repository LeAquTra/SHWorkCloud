package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content-Disposition 构造。
 * <p>中文文件名是"下载回本地"最容易出错的一环：响应头只能是 ISO-8859-1，
 * 不按 RFC 5987 编码就会出现乱码文件名甚至丢扩展名。
 */
class ContentDispositionTest {

    @Test
    @DisplayName("纯 ASCII 文件名：两段一致，可直接使用")
    void asciiName() {
        assertEquals("attachment; filename=\"homework.docx\"; filename*=UTF-8''homework.docx",
                ContentDisposition.attachment("homework.docx"));
    }

    @Test
    @DisplayName("中文文件名：ASCII 回退名换成 download + 原扩展名，UTF-8 名百分号编码")
    void chineseName() {
        String value = ContentDisposition.attachment("作业.docx");
        // 老客户端拿到的是可用的 download.docx，而不是 "__.docx"
        assertTrue(value.contains("filename=\"download.docx\""), value);
        // 现代浏览器用 filename*，百分号编码为 UTF-8 字节
        assertTrue(value.contains("filename*=UTF-8''%E4%BD%9C%E4%B8%9A.docx"), value);
    }

    @Test
    @DisplayName("中英混合文件名：ASCII 部分保留，中文部分编码")
    void mixedName() {
        // "第一章" 是 3 个字符，回退成全角下划线 x3
        String value = ContentDisposition.attachment("第一章 homework.pdf");
        assertTrue(value.contains("filename=\"___ homework.pdf\""), value);
        assertTrue(value.startsWith("attachment; "), value);
        // 中文部分必须出现在 filename* 里，浏览器才能拿到正确名字
        assertTrue(value.contains("%E7%AC%AC%E4%B8%80%E7%AB%A0%20homework.pdf"), value);
    }

    @Test
    @DisplayName("占用字符与引号被替换，避免头注入与歧义")
    void sanitizeDangerousChars() {
        String value = ContentDisposition.attachment("a\"b\\c/d;e.txt");
        assertFalse(value.contains("\"b"), value);
        assertTrue(value.contains("a_b_c_d_e.txt"), value);
    }

    @Test
    @DisplayName("控制字符被剔除")
    void stripsControlChars() {
        String value = ContentDisposition.attachment("a\r\nX-Injected: 1.txt");
        assertFalse(value.contains("\r"), value);
        assertFalse(value.contains("\n"), value);
        assertTrue(value.contains("aX-Injected: 1.txt"), value);
    }

    @Test
    @DisplayName("空文件名回退为 download")
    void emptyName() {
        assertEquals("attachment; filename=\"download\"; filename*=UTF-8''download",
                ContentDisposition.attachment(null));
        assertEquals("attachment; filename=\"download\"; filename*=UTF-8''download",
                ContentDisposition.attachment("   "));
    }

    @Test
    @DisplayName("inline 形式用于预览")
    void inlineType() {
        assertTrue(ContentDisposition.inline("cover.png").startsWith("inline; "));
    }

    @Test
    @DisplayName("RFC 5987 只保留 unreserved 字符，其余百分号编码")
    void encodingKeepsUnreservedOnly() {
        // 空格必须编码为 %20（不能留空格，否则 filename* 解析会截断）
        String value = ContentDisposition.attachment("a b~c_d-e.f");
        assertTrue(value.contains("filename*=UTF-8''a%20b~c_d-e.f"), value);
    }
}
