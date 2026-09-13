package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文提取（在线阅览的核心）。
 * <p>docx/pptx 用"手工构造 zip"来测：这两种格式本质就是 zip + xml，
 * 自己拼一个最小文档比引入 POI 更适合离线环境，也能精确覆盖段落/runs/实体/页码排序等边界。
 * <p>.doc 反过来：容器用 POI 的 poifs <b>写出</b>一个真正的 OLE2 复合文档当夹具，
 * 再让被测代码按 FIB + piece table 读回来。这样容器层由 POI 保证，
 * 测试真正压的是我们自己写的那部分偏移解析 —— compressed / UTF-16LE 混排、
 * 域代码、图片占位、ccpText 截断、加密标记。
 */
class TextExtractServiceTest {

    private final TextExtractService service = new TextExtractService();

    // ------------------------------------------------------------ 工具

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] minimalDocx(String bodyParagraphs) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s</w:body>
                </w:document>
                """.formatted(bodyParagraphs);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>");
        entries.put("word/document.xml", xml);
        return zip(entries);
    }

    private static byte[] minimalPptx(Map<Integer, String> slides) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>");
        slides.forEach((number, text) -> entries.put("ppt/slides/slide" + number + ".xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                <p:cSld><p:spTree><p:sp><p:txBody>
                <a:p><a:r><a:t>%s</a:t></a:r></a:p>
                </p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>
                """.formatted(text)));
        return zip(entries);
    }

    // ------------------------------------------------------------ doc 夹具（真 OLE2）

    private static final int FIB_FC_MIN = 0x18;
    private static final int FIB_FC_MAC = 0x1C;
    private static final int FIB_CCP_TEXT = 0x4C;
    private static final int FIB_LCB_CLX = 0x01A6;
    /** 正文在 WordDocument 流里的起始偏移（FIB 之后留足空间） */
    private static final int TEXT_OFFSET = 0x800;

    /** piece table 里的一段正文；{@code chars} 是这段的字符数（不是字节数） */
    private record Piece(byte[] bytes, boolean compressed, int chars) {
    }

    private static void put16(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] b, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            b[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    /** 把流打成真正的 OLE2 复合文档（.doc 的容器格式） */
    private static byte[] ole2(byte[] wordDocument, String tableName, byte[] table) throws IOException {
        POIFSFileSystem fs = new POIFSFileSystem();
        try {
            fs.createDocument(new ByteArrayInputStream(wordDocument), "WordDocument");
            if (table != null) {
                fs.createDocument(new ByteArrayInputStream(table), tableName);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        } finally {
            fs.close();
        }
    }

    /** 老式 .doc：没有 piece table，正文就在 WordDocument 流的 fcMin..fcMac（nFib&lt;193 是 8 位） */
    private static byte[] docWithoutPieceTable(String text, Charset charset, int nFib) throws IOException {
        byte[] content = text.getBytes(charset);
        byte[] wd = new byte[TEXT_OFFSET + content.length];
        put16(wd, 0x00, 0xA5EC);
        put16(wd, 0x02, nFib);
        put32(wd, FIB_FC_MIN, TEXT_OFFSET);
        put32(wd, FIB_FC_MAC, TEXT_OFFSET + content.length);
        put32(wd, FIB_CCP_TEXT, text.length());
        System.arraycopy(content, 0, wd, TEXT_OFFSET, content.length);
        return ole2(wd, "0Table", null);
    }

    /** 带 piece table 的 .doc：每段自己声明编码与字节偏移，正交于"快速保存"后的真实形态 */
    private static byte[] docWithPieces(List<Piece> pieces, int ccpText) throws IOException {
        return docWithPieceTable(pieces, ccpText, 0, "0Table", 0);
    }

    /**
     * @param fibFlags   FIB 的 flags 字段（bit9 = fWhichTblStm 时 Clx 在 1Table 流里）
     * @param tableName  Clx 所在流的名字
     * @param prcPayload 在 Pcdt 前塞一个多大的 Prc 块（真实 Word 文件里常有）
     */
    private static byte[] docWithPieceTable(List<Piece> pieces, int ccpText, int fibFlags,
                                            String tableName, int prcPayload) throws IOException {
        int total = TEXT_OFFSET;
        for (Piece piece : pieces) {
            total += piece.bytes().length;
        }
        byte[] wd = new byte[total];
        put16(wd, 0x00, 0xA5EC);
        put16(wd, 0x02, 193);
        put16(wd, 0x0A, fibFlags);
        put32(wd, FIB_CCP_TEXT, ccpText);

        int n = pieces.size();
        int lcb = 4 * (n + 1) + 8 * n;      // (n+1) 个 CP + n 个 PCD
        int prcLen = prcPayload > 0 ? 3 + prcPayload : 0;
        byte[] table = new byte[prcLen + 5 + lcb];   // [Prc] + Pcdt(0x02 + lcb + PlcPcd)
        if (prcLen > 0) {
            table[0] = 0x01;
            put16(table, 1, prcPayload);
        }
        int pcdt = prcLen;
        table[pcdt] = 0x02;
        put32(table, pcdt + 1, lcb);
        int cps = pcdt + 5;
        int pcds = cps + (n + 1) * 4;

        int offset = TEXT_OFFSET;
        int cp = 0;
        for (int i = 0; i < n; i++) {
            Piece piece = pieces.get(i);
            put32(table, cps + i * 4, cp);
            cp += piece.chars();
            put32(table, cps + (i + 1) * 4, cp);
            // fCompressed 是 fc 的第 30 位；8 位正文的字节偏移要乘 2 存进去
            put32(table, pcds + i * 8 + 2, piece.compressed() ? (offset * 2) | 0x40000000 : offset);
            System.arraycopy(piece.bytes(), 0, wd, offset, piece.bytes().length);
            offset += piece.bytes().length;
        }
        put32(wd, FIB_LCB_CLX, table.length);
        return ole2(wd, tableName, table);
    }

    // ------------------------------------------------------------ 纯文本

    @Test
    @DisplayName("UTF-8 文本：内容与字符集正确")
    void utf8Text() {
        TextExtractService.Extracted result = service.extract("txt",
                "第一行\n第二行\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("第一行\n第二行\n", result.content());
        assertEquals("UTF-8", result.charset());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("GBK 文本：自动识别并正确解码（学生在中文 Windows 用记事本存的常见格式）")
    void gbkText() {
        byte[] gbk = "作业已提交".getBytes(Charset.forName("GBK"));
        TextExtractService.Extracted result = service.extract("txt", gbk);
        assertEquals("作业已提交", result.content());
        assertEquals("GBK", result.charset());
    }

    @Test
    @DisplayName("UTF-8 BOM 会被剥离，不会在正文开头留一个不可见字符")
    void utf8Bom() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);

        TextExtractService.Extracted result = service.extract("md", all);
        assertEquals("hello", result.content());
        assertTrue(result.charset().startsWith("UTF-8"));
    }

    @Test
    @DisplayName("超长文本被截断并标记 truncated")
    void truncatesLongText() {
        String long_ = "a".repeat(TextExtractService.MAX_CHARS + 500);
        TextExtractService.Extracted result = service.extract("log", long_.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.truncated());
        assertEquals(TextExtractService.MAX_CHARS, result.content().length());
    }

    @Test
    @DisplayName("超过大小上限的文本文件被拒绝（期望 40082）")
    void rejectsOversizeText() {
        byte[] huge = new byte[TextExtractService.MAX_TEXT_BYTES + 1];
        BizException e = assertThrows(BizException.class, () -> service.extract("txt", huge));
        assertEquals(ErrorCode.FILE_TOO_LARGE, e.getErrorCode());
    }

    // ------------------------------------------------------------ docx

    @Test
    @DisplayName("docx：按段落提取，段内多个 run 会拼接")
    void docxParagraphs() throws IOException {
        byte[] docx = minimalDocx("""
                <w:p><w:r><w:t>第一章 作业</w:t></w:r></w:p>
                <w:p><w:r><w:t>答：</w:t></w:r><w:r><w:t>已完成</w:t></w:r></w:p>
                """);
        TextExtractService.Extracted result = service.extract("docx", docx);
        assertTrue(result.content().contains("第一章 作业"), result.content());
        assertTrue(result.content().contains("答：已完成"), result.content());
        assertTrue(result.hint().contains("排版"), "应提示会丢失排版");
    }

    @Test
    @DisplayName("docx：XML 实体被还原（&amp; &lt; 等）")
    void docxEntities() throws IOException {
        byte[] docx = minimalDocx("<w:p><w:r><w:t>Tom &amp; Jerry &lt;tag&gt;</w:t></w:r></w:p>");
        TextExtractService.Extracted result = service.extract("docx", docx);
        assertTrue(result.content().contains("Tom & Jerry <tag>"), result.content());
    }

    @Test
    @DisplayName("docx：制表符与换行标签被保留为可读形式")
    void docxTabAndBreak() throws IOException {
        byte[] docx = minimalDocx("<w:p><w:r><w:t>姓名</w:t></w:r><w:tab/><w:r><w:t>张三</w:t></w:r></w:p>");
        TextExtractService.Extracted result = service.extract("docx", docx);
        assertTrue(result.content().contains("姓名\t张三"), result.content());
    }

    @Test
    @DisplayName("docx：纯图片文档给出可读提示而不是空内容")
    void docxWithoutText() throws IOException {
        byte[] docx = minimalDocx("<w:p><w:r><w:drawing/></w:r></w:p>");
        TextExtractService.Extracted result = service.extract("docx", docx);
        assertTrue(result.content().contains("没有可提取的文字"), result.content());
    }

    @Test
    @DisplayName("伪装成 docx 的非 zip 文件被拒绝")
    void fakeDocx() {
        BizException e = assertThrows(BizException.class,
                () -> service.extract("docx", "this is not a zip".getBytes()));
        assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
    }

    // ------------------------------------------------------------ pptx

    @Test
    @DisplayName("pptx：按页码数字排序（slide2 要在 slide10 之前）")
    void pptxSlideOrder() throws IOException {
        Map<Integer, String> slides = new LinkedHashMap<>();
        slides.put(1, "第一页内容");
        slides.put(2, "第二页内容");
        slides.put(10, "第十页内容");
        byte[] pptx = minimalPptx(slides);

        TextExtractService.Extracted result = service.extract("pptx", pptx);
        String content = result.content();
        int first = content.indexOf("第一页内容");
        int second = content.indexOf("第二页内容");
        int tenth = content.indexOf("第十页内容");
        assertTrue(first >= 0 && second > first && tenth > second,
                "顺序应为 1 -> 2 -> 10，实际：" + content);
        assertTrue(content.startsWith("--- 第 1 页 ---"), content);
    }

    // ------------------------------------------------------------ doc（Word 97-2003）

    @Test
    @DisplayName("doc（无 piece table）：Word 97+ 的 UTF-16LE 正文能提取，段落标记变换行")
    void docSimpleUnicode() throws IOException {
        byte[] doc = docWithoutPieceTable("第一章 作业\r已完成", StandardCharsets.UTF_16LE, 193);
        TextExtractService.Extracted result = service.extract("doc", doc);
        assertEquals("第一章 作业\n已完成", result.content());
        assertTrue(result.hint().contains("排版"), "应提示会丢失排版");
    }

    @Test
    @DisplayName("doc（无 piece table）：Word 6/95 的 8 位正文按 CP1252 解码")
    void docSimpleAnsi() throws IOException {
        byte[] doc = docWithoutPieceTable("Report card\rGrade A", StandardCharsets.ISO_8859_1, 104);
        assertEquals("Report card\nGrade A", service.extract("doc", doc).content());
    }

    @Test
    @DisplayName("doc：Word 97+ 的 fcMin/fcMac 不可用时宁可报错，也不把二进制当正文吐出去")
    void docUnusableOffsetsRejected() throws IOException {
        // nFib=193 却没有 piece table，且 fcMin/fcMac 全 0 —— 这正是真实 Word 文件里
        // 那两个字段"必须忽略"的形态，不能拿它们当正文边界
        byte[] wd = new byte[TEXT_OFFSET];
        put16(wd, 0x00, 0xA5EC);
        put16(wd, 0x02, 193);
        BizException e = assertThrows(BizException.class,
                () -> service.extract("doc", ole2(wd, "0Table", null)));
        assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
    }

    @Test
    @DisplayName("doc（piece table）：8 位段与 UTF-16LE 段混排，各段按自己的编码解码")
    void docPieceTableMixedEncoding() throws IOException {
        List<Piece> pieces = List.of(
                new Piece("Report: ".getBytes(StandardCharsets.ISO_8859_1), true, 8),
                new Piece("第一章 作业".getBytes(StandardCharsets.UTF_16LE), false, 6));
        byte[] doc = docWithPieces(pieces, 14);
        assertEquals("Report: 第一章 作业", service.extract("doc", doc).content());
    }

    @Test
    @DisplayName("doc：Clx 放在 1Table 流里（fWhichTblStm）、且 Pcdt 前带 Prc 块时同样能解析")
    void docAltTableStreamAndPrc() throws IOException {
        String text = "一Table 里的正文";
        List<Piece> pieces = List.of(
                new Piece(text.getBytes(StandardCharsets.UTF_16LE), false, text.length()));
        // 0x0200 = fWhichTblStm：Clx 在 1Table 而不是 0Table
        byte[] doc = docWithPieceTable(pieces, text.length(), 0x0200, "1Table", 16);
        assertEquals(text, service.extract("doc", doc).content());
    }

    @Test
    @DisplayName("doc：ccpText 之外的脚注/页眉不会被当成正文吐出来")
    void docCcpTextBoundsMainText() throws IOException {
        List<Piece> pieces = List.of(
                new Piece("正文脚注内容".getBytes(StandardCharsets.UTF_16LE), false, 6));
        byte[] doc = docWithPieces(pieces, 2);
        assertEquals("正文", service.extract("doc", doc).content());
    }

    @Test
    @DisplayName("doc：域代码丢弃、域结果保留，图片占位与控制字符清除")
    void docFieldCodesAndControlChars() throws IOException {
        String raw = "姓名：\u0013HYPERLINK \"http://example.com\"\u0014链接\u0015\r\u0001结束";
        List<Piece> pieces = List.of(
                new Piece(raw.getBytes(StandardCharsets.UTF_16LE), false, raw.length()));
        byte[] doc = docWithPieces(pieces, raw.length());

        String content = service.extract("doc", doc).content();
        assertEquals("姓名：链接\n结束", content);
        assertFalse(content.contains("HYPERLINK"), "域代码不应该出现在正文里");
    }

    @Test
    @DisplayName("doc：正文全是控制字符时给出可读提示，而不是空内容")
    void docWithoutText() throws IOException {
        List<Piece> pieces = List.of(
                new Piece("\r\u0001".getBytes(StandardCharsets.UTF_16LE), false, 2));
        byte[] doc = docWithPieces(pieces, 2);
        assertTrue(service.extract("doc", doc).content().contains("没有可提取的文字"));
    }

    @Test
    @DisplayName("doc：超长正文被截断并标记 truncated（提取阶段就停下，不会先撑爆内存）")
    void docTruncatesLongText() throws IOException {
        String long_ = "a".repeat(TextExtractService.MAX_CHARS + 500);
        byte[] doc = docWithoutPieceTable(long_, StandardCharsets.UTF_16LE, 193);
        TextExtractService.Extracted result = service.extract("doc", doc);
        assertTrue(result.truncated());
        assertEquals(TextExtractService.MAX_CHARS, result.content().length());
    }

    @Test
    @DisplayName("doc：加密文档给出「已设置密码」的明确提示，而不是笼统的解析失败")
    void docEncrypted() throws IOException {
        byte[] content = "secret".getBytes(StandardCharsets.UTF_16LE);
        byte[] wd = new byte[TEXT_OFFSET + content.length];
        put16(wd, 0x00, 0xA5EC);
        put16(wd, 0x02, 193);
        put16(wd, 0x0A, 0x0100);            // fEncrypted
        System.arraycopy(content, 0, wd, TEXT_OFFSET, content.length);

        BizException e = assertThrows(BizException.class,
                () -> service.extract("doc", ole2(wd, "0Table", null)));
        assertEquals(ErrorCode.PREVIEW_NOT_SUPPORTED, e.getErrorCode());
        assertTrue(e.getMessage().contains("密码"), e.getMessage());
    }

    @Test
    @DisplayName("doc：docx 改名成 .doc、以及非 OLE2 垃圾文件都被拒绝（期望 40000）")
    void docWrongFormat() throws IOException {
        byte[] docx = minimalDocx("<w:p><w:r><w:t>x</w:t></w:r></w:p>");
        BizException fromDocx = assertThrows(BizException.class, () -> service.extract("doc", docx));
        assertEquals(ErrorCode.BAD_PARAM, fromDocx.getErrorCode());
        assertTrue(fromDocx.getMessage().contains(".doc"), fromDocx.getMessage());

        BizException fromJunk = assertThrows(BizException.class,
                () -> service.extract("doc", "这不是 OLE2 文件".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.BAD_PARAM, fromJunk.getErrorCode());
    }

    @Test
    @DisplayName("doc：超过 20MB 上限的文件被拒绝（期望 40082）")
    void docTooLarge() {
        BizException e = assertThrows(BizException.class,
                () -> service.extract("doc", new byte[TextExtractService.MAX_OFFICE_BYTES + 1]));
        assertEquals(ErrorCode.FILE_TOO_LARGE, e.getErrorCode());
    }

    // ------------------------------------------------------------ 内嵌图片（docx / pptx）

    /** 造一个含二进制条目的 zip（图片是二进制，不能走上面那个 String 版） */
    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 8 字节假 PNG：提取只按扩展名识别、不嗅探内容，所以够用 */
    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    @DisplayName("docx 内嵌图片：word/media 下的位图被提取成 data URL")
    void docxEmbeddedImages() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        entries.put("word/media/image1.png", FAKE_PNG);

        TextExtractService.EmbeddedImages result =
                service.extractEmbeddedImages("docx", zipBytes(entries));
        assertEquals(1, result.images().size());
        assertEquals(0, result.skipped());

        TextExtractService.EmbeddedImage image = result.images().get(0);
        assertEquals("image1.png", image.name());
        assertEquals("image/png", image.contentType());
        assertEquals(FAKE_PNG.length, image.size());
        assertTrue(image.dataUrl().startsWith("data:image/png;base64,"), image.dataUrl());
    }

    @Test
    @DisplayName("pptx 内嵌图片：从 ppt/media 提取")
    void pptxEmbeddedImages() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("ppt/slides/slide1.xml", "<p:sld/>".getBytes(StandardCharsets.UTF_8));
        entries.put("ppt/media/image1.jpeg", FAKE_PNG);

        TextExtractService.EmbeddedImages result =
                service.extractEmbeddedImages("pptx", zipBytes(entries));
        assertEquals(1, result.images().size());
        assertEquals("image/jpeg", result.images().get(0).contentType());
    }

    @Test
    @DisplayName("内嵌图片：emf/wmf 矢量图与 svg 一律跳过（渲染不了 / 不安全），计入 skipped")
    void skipsUnrenderableAndUnsafeMedia() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/media/v1.emf", FAKE_PNG);
        entries.put("word/media/v2.wmf", FAKE_PNG);
        // svg 与 FileViewType.UNSAFE_IMAGE 口径一致：能渲染也不收
        entries.put("word/media/v3.svg", "<svg/>".getBytes(StandardCharsets.UTF_8));
        entries.put("word/media/ok.png", FAKE_PNG);

        TextExtractService.EmbeddedImages result =
                service.extractEmbeddedImages("docx", zipBytes(entries));
        assertEquals(1, result.images().size(), "只应留下 ok.png");
        assertEquals("ok.png", result.images().get(0).name());
        assertEquals(3, result.skipped());
    }

    @Test
    @DisplayName("内嵌图片：单张超过上限的被跳过，而不是把响应撑爆")
    void skipsOversizedImage() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/media/huge.png", new byte[TextExtractService.MAX_EMBEDDED_IMAGE_BYTES + 1]);
        entries.put("word/media/small.png", FAKE_PNG);

        TextExtractService.EmbeddedImages result =
                service.extractEmbeddedImages("docx", zipBytes(entries));
        assertEquals(1, result.images().size());
        assertEquals("small.png", result.images().get(0).name());
        assertEquals(1, result.skipped());
    }

    @Test
    @DisplayName("内嵌图片：非 Office 文件与无图文档都返回空列表，不报错")
    void embeddedImagesOnNonOfficeAndEmptyDocs() throws IOException {
        TextExtractService.EmbeddedImages onTxt =
                service.extractEmbeddedImages("txt", "hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(onTxt.images().isEmpty());
        assertEquals(0, onTxt.skipped());

        byte[] docxWithoutImages = minimalDocx("<w:p><w:r><w:t>只有文字</w:t></w:r></w:p>");
        TextExtractService.EmbeddedImages empty =
                service.extractEmbeddedImages("docx", docxWithoutImages);
        assertTrue(empty.images().isEmpty());
        assertEquals(0, empty.skipped());
    }

    // ------------------------------------------------------------ 不支持的类型

    @Test
    @DisplayName("不支持在线阅览的类型给出明确错误")
    void unsupportedType() {
        BizException e = assertThrows(BizException.class,
                () -> service.extract("exe", new byte[]{1, 2, 3}));
        assertEquals(ErrorCode.PREVIEW_NOT_SUPPORTED, e.getErrorCode());
        assertTrue(e.getMessage().contains("下载"), e.getMessage());
    }
}
