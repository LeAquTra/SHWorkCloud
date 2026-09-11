package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Range 解析。
 * <p>它决定断点续传下载是否正确：解析错了要么下不到完整文件，要么响应头与内容不一致。
 */
class HttpRangeTest {

    @Test
    @DisplayName("没有 Range 头时返回 null（按完整内容 200 返回）")
    void noRangeHeader() {
        assertNull(HttpRange.parse(null, 100));
        assertNull(HttpRange.parse("", 100));
        assertNull(HttpRange.parse("   ", 100));
    }

    @Test
    @DisplayName("bytes=0-9：闭区间")
    void explicitRange() {
        HttpRange.ByteRange range = HttpRange.parse("bytes=0-9", 100);
        assertEquals(0, range.start());
        assertEquals(9, range.end());
        assertEquals(10, range.length());
    }

    @Test
    @DisplayName("bytes=10-：到文件末尾")
    void openEndedRange() {
        HttpRange.ByteRange range = HttpRange.parse("bytes=10-", 100);
        assertEquals(10, range.start());
        assertEquals(99, range.end());
    }

    @Test
    @DisplayName("bytes=-10：最后 10 字节（下载管理器常用）")
    void suffixRange() {
        HttpRange.ByteRange range = HttpRange.parse("bytes=-10", 100);
        assertEquals(90, range.start());
        assertEquals(99, range.end());
        assertEquals(10, range.length());
    }

    @Test
    @DisplayName("结束位置越界要收敛到文件末尾")
    void endClamped() {
        HttpRange.ByteRange range = HttpRange.parse("bytes=95-200", 100);
        assertEquals(95, range.start());
        assertEquals(99, range.end());
    }

    @Test
    @DisplayName("后缀长度大于文件长度时返回整个文件")
    void suffixLargerThanFile() {
        HttpRange.ByteRange range = HttpRange.parse("bytes=-500", 100);
        assertEquals(0, range.start());
        assertEquals(99, range.end());
    }

    @Test
    @DisplayName("起点越界视为不可满足，退回完整内容而不是报错")
    void startBeyondSize() {
        assertNull(HttpRange.parse("bytes=100-", 100));
        assertNull(HttpRange.parse("bytes=999-1000", 100));
    }

    @Test
    @DisplayName("畸形输入一律退回完整内容")
    void malformed() {
        assertNull(HttpRange.parse("items=0-9", 100));   // 单位不是 bytes
        assertNull(HttpRange.parse("bytes=0-9,20-29", 100)); // 多段不支持
        assertNull(HttpRange.parse("bytes=abc", 100));
        assertNull(HttpRange.parse("bytes=5-3", 100));   // 结束早于开始
        assertNull(HttpRange.parse("bytes=-", 100));
        assertNull(HttpRange.parse("bytes=-0", 100));
        assertNull(HttpRange.parse("bytes=", 100));
        assertNull(HttpRange.parse("bytes=1-2", 0));     // 空对象
    }

    @Test
    @DisplayName("单位大小写不敏感")
    void caseInsensitiveUnit() {
        HttpRange.ByteRange range = HttpRange.parse("BYTES=0-9", 100);
        assertEquals(0, range.start());
        assertEquals(9, range.end());
    }
}
