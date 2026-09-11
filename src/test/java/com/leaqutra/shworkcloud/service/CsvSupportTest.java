package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 解析与编码兼容测试。
 * <p>机房场景下这份 CSV 由老师用 Excel 导出，BOM/GBK/引号逗号三种坑都会遇到。
 */
class CsvSupportTest {

    @Test
    @DisplayName("表头+数据行，CRLF 与 LF 都能解析")
    void parseBasic() {
        List<List<String>> rows = CsvSupport.parse("学号,姓名\n20260001,张三\n20260002,李四");
        assertEquals(3, rows.size());
        assertEquals(List.of("学号", "姓名"), rows.get(0));
        assertEquals(List.of("20260001", "张三"), rows.get(1));
        assertEquals(List.of("20260002", "李四"), rows.get(2));

        List<List<String>> crlf = CsvSupport.parse("学号,姓名\r\n20260001,张三\r\n");
        assertEquals(2, crlf.size());
        assertEquals(List.of("20260001", "张三"), crlf.get(1));
    }

    @Test
    @DisplayName("引号包裹：字段内含逗号不被拆列")
    void parseQuotedComma() {
        List<List<String>> rows = CsvSupport.parse("学号,姓名\n20260001,\"张,三\"\n");
        assertEquals(2, rows.size());
        assertEquals("张,三", rows.get(1).get(1));
    }

    @Test
    @DisplayName("双写引号表示一个字面引号")
    void parseEscapedQuote() {
        List<List<String>> rows = CsvSupport.parse("a,b\n1,\"他说\"\"你好\"\"\"\n");
        assertEquals("他说\"你好\"", rows.get(1).get(1));
    }

    @Test
    @DisplayName("忽略完全空行")
    void parseSkipsBlankLines() {
        List<List<String>> rows = CsvSupport.parse("a,b\n\n1,2\n\n");
        assertEquals(2, rows.size());
    }

    @Test
    @DisplayName("空内容返回空列表")
    void parseEmpty() {
        assertTrue(CsvSupport.parse("").isEmpty());
        assertTrue(CsvSupport.parse(null).isEmpty());
    }

    @Test
    @DisplayName("UTF-8 BOM 被剥离，中文不乱码")
    void decodeUtf8Bom() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "学号,姓名".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);

        String decoded = CsvSupport.decode(all);
        assertEquals("学号,姓名", decoded);
        // 没有 BOM 残留
        assertTrue(!decoded.startsWith("\uFEFF"));
    }

    @Test
    @DisplayName("GBK 文件回退解码，中文姓名不乱码")
    void decodeGbk() {
        // 用 GBK 编码"学号,姓名"；严格 UTF-8 校验会失败，触发回退
        Charset gbk = Charset.forName("GBK");
        byte[] bytes = "学号,姓名".getBytes(gbk);
        assertEquals("学号,姓名", CsvSupport.decode(bytes));
    }

    @Test
    @DisplayName("纯 ASCII 内容按 UTF-8 解读")
    void decodeAscii() {
        assertEquals("id,name", CsvSupport.decode("id,name".getBytes(StandardCharsets.UTF_8)));
    }
}
