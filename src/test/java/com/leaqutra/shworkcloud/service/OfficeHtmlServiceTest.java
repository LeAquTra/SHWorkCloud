package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OOXML → HTML 渲染器。
 *
 * <p>两件事必须守住：
 * <ol>
 *   <li><b>结构化还原</b>：段落/表格/加粗/对齐/内嵌图片要真的出现在 HTML 里
 *       （否则"原格式显示"就是空话）；</li>
 *   <li><b>不产生 XSS</b>：文档里的一切文本都必须被转义 ——
 *       一个精心构造的 docx 不能把 &lt;script&gt; 送进前端页面。
 *       这是本功能最危险的地方，所以单独有用例。</li>
 * </ol>
 */
class OfficeHtmlServiceTest {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
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

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 用给定 body 造一个最小 docx */
    private static byte[] docx(String bodyXml) throws IOException {
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="%s" xmlns:r="%s" xmlns:a="%s">
                <w:body>%s</w:body>
                </w:document>
                """.formatted(W_NS, R_NS, A_NS, bodyXml);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", utf8(document));
        return zip(entries);
    }

    // ------------------------------------------------------------ docx

    @Test
    @DisplayName("docx：段落、标题、加粗/斜体/下划线都被还原成对应标签")
    void docxParagraphAndRuns() throws IOException {
        String body = """
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>加粗</w:t></w:r>"""
                + """
                <w:r><w:rPr><w:i/></w:rPr><w:t>斜体</w:t></w:r>"""
                + """
                <w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t>下划线</w:t></w:r></w:p>
                """;
        String html = OfficeHtmlService.docxToHtml(docx(body));

        assertTrue(html.contains("<h2>第一章</h2>"), html);
        assertTrue(html.contains("<strong>加粗</strong>"), html);
        assertTrue(html.contains("<em>斜体</em>"), html);
        assertTrue(html.contains("<u>下划线</u>"), html);
    }

    @Test
    @DisplayName("docx：对齐、字号、颜色被映射成 style，非法颜色被丢弃")
    void docxAlignmentAndStyle() throws IOException {
        String body = """
                <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>居中</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:sz w:val="28"/><w:color w:val="FF0000"/></w:rPr>
                <w:t>红字14磅</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:color w:val="not-a-color"/></w:rPr><w:t>坏颜色</w:t></w:r></w:p>
                """;
        String html = OfficeHtmlService.docxToHtml(docx(body));

        assertTrue(html.contains("text-align:center"), html);
        assertTrue(html.contains("font-size:14pt"), html);
        assertTrue(html.contains("color:#ff0000"), html);
        // 非法颜色不能原样进 style
        assertFalse(html.contains("not-a-color"), html);
    }

    @Test
    @DisplayName("docx：表格被还原为 table/tr/td，横向合并变成 colspan")
    void docxTable() throws IOException {
        String body = """
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>表头</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                """;
        String html = OfficeHtmlService.docxToHtml(docx(body));

        assertTrue(html.contains("<table"), html);
        assertTrue(html.contains("colspan=\"2\""), html);
        // 单元格内容按段落渲染，所以是 <td><p>A</p></td>
        assertTrue(html.contains("<p>表头</p>"), html);
        assertTrue(html.contains("<p>A</p>"), html);
        assertTrue(html.contains("<p>B</p>"), html);
        // 两句一行两列：第二行必须真的是两个 td
        assertTrue(html.contains("<tr><td><p>A</p></td><td><p>B</p></td></tr>"), html);
    }

    @Test
    @DisplayName("docx：内嵌图片按原位置插入为 data URL")
    void docxInlineImage() throws IOException {
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="%s" xmlns:r="%s" xmlns:a="%s">
                <w:body><w:p><w:r><w:t>看图：</w:t></w:r>
                <w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p></w:body>
                </w:document>
                """.formatted(W_NS, R_NS, A_NS);
        String rels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                 Target="media/image1.png"/>
                </Relationships>
                """;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", utf8(document));
        entries.put("word/_rels/document.xml.rels", utf8(rels));
        // 1x1 GIF 的 PNG 头足够：渲染只认扩展名
        entries.put("word/media/image1.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        String html = OfficeHtmlService.docxToHtml(zip(entries));
        assertTrue(html.contains("看图："), html);
        assertTrue(html.contains("src=\"data:image/png;base64,"), html);
    }

    @Test
    @DisplayName("docx：文档里的 HTML/脚本被转义 —— 绝不能把 <script> 送进页面")
    void docxEscapesDangerousText() throws IOException {
        String body = """
                <w:p><w:r><w:t>&lt;script&gt;alert(1)&lt;/script&gt;</w:t></w:r></w:p>
                <w:p><w:r><w:t>&lt;img src=x onerror=alert(2)&gt;</w:t></w:r></w:p>
                <w:p><w:r><w:t>a &amp;&amp; b "引号" '单引号'</w:t></w:r></w:p>
                """;
        String html = OfficeHtmlService.docxToHtml(docx(body));

        assertFalse(html.contains("<script>"), "脚本标签必须被转义：" + html);
        assertFalse(html.contains("<img src=x"), "事件属性必须被转义：" + html);
        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(html.contains("&amp;&amp;"), html);
        assertTrue(html.contains("&quot;引号&quot;"), html);
        assertTrue(html.contains("&#39;单引号&#39;"), html);
    }

    @Test
    @DisplayName("docx：DOCTYPE/外部实体被拒绝（防 XXE 读服务器文件）")
    void docxRejectsDoctype() throws IOException {
        String body = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <w:document xmlns:w="%s"><w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>
                """.formatted(W_NS);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", utf8(body));

        // 带 DOCTYPE 直接被解析器拒绝 → 抛业务异常，由调用方回退为纯文本
        assertThrows(Exception.class, () -> OfficeHtmlService.docxToHtml(zip(entries)));
    }

    // ------------------------------------------------------------ xlsx

    private static byte[] xlsx(String sheetXml, String sharedStringsXml) throws IOException {
        String workbook = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="%s">
                  <sheets><sheet name="成绩表" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """.formatted(R_NS);
        String rels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1"
                 Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                 Target="worksheets/sheet1.xml"/>
                </Relationships>
                """;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("xl/workbook.xml", utf8(workbook));
        entries.put("xl/_rels/workbook.xml.rels", utf8(rels));
        entries.put("xl/worksheets/sheet1.xml", utf8(sheetXml));
        if (sharedStringsXml != null) {
            entries.put("xl/sharedStrings.xml", utf8(sharedStringsXml));
        }
        return zip(entries);
    }

    @Test
    @DisplayName("xlsx：共享字符串、数值、行与列按表格还原")
    void xlsxTable() throws IOException {
        String sheet = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                  <row r="1">
                    <c r="A1" t="s"><v>0</v></c>
                    <c r="B1" t="s"><v>1</v></c>
                  </row>
                  <row r="2">
                    <c r="A2" t="s"><v>2</v></c>
                    <c r="B2"><v>95</v></c>
                  </row>
                </sheetData></worksheet>
                """;
        String shared = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>姓名</t></si><si><t>分数</t></si><si><t>张三</t></si>
                </sst>
                """;
        String html = OfficeHtmlService.xlsxToHtml(xlsx(sheet, shared));

        assertTrue(html.contains("成绩表"), html);
        assertTrue(html.contains("<table"), html);
        assertTrue(html.contains("<td>姓名</td>"), html);
        assertTrue(html.contains("<td>分数</td>"), html);
        assertTrue(html.contains("<td>张三</td>"), html);
        assertTrue(html.contains("<td>95</td>"), html);
    }

    @Test
    @DisplayName("xlsx：合并单元格还原成 colspan/rowspan，中间空列会补齐")
    void xlsxMergedCellsAndGaps() throws IOException {
        String sheet = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>合并标题</t></is></c></row>
                  <row r="2"><c r="C2"><v>1</v></c></row>
                </sheetData>
                <mergeCells count="1"><mergeCell ref="A1:B1"/></mergeCells>
                </worksheet>
                """;
        String html = OfficeHtmlService.xlsxToHtml(xlsx(sheet, null));

        assertTrue(html.contains("colspan=\"2\""), html);
        // A2、B2 是空的，必须补齐两个空 td，否则 C2 会错位到第一列
        assertTrue(html.contains("<td></td><td></td><td>1</td>"), html);
    }

    @Test
    @DisplayName("xlsx：单元格文本同样被转义")
    void xlsxEscapesText() throws IOException {
        String sheet = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData><row r="1"><c r="A1" t="inlineStr">
                <is><t>&lt;script&gt;alert(1)&lt;/script&gt;</t></is></c></row></sheetData>
                </worksheet>
                """;
        String html = OfficeHtmlService.xlsxToHtml(xlsx(sheet, null));
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    @DisplayName("xlsx：纯文本版本用制表符分隔，便于复制与检索")
    void xlsxPlainText() throws IOException {
        String sheet = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>姓名</t></is></c>
                              <c r="B1" t="inlineStr"><is><t>分数</t></is></c></row>
                  <row r="2"><c r="A2" t="inlineStr"><is><t>张三</t></is></c><c r="B2"><v>95</v></c></row>
                </sheetData></worksheet>
                """;
        String text = OfficeHtmlService.xlsxToText(xlsx(sheet, null));

        assertTrue(text.contains("--- 成绩表 ---"), text);
        assertTrue(text.contains("姓名\t分数"), text);
        assertTrue(text.contains("张三\t95"), text);
    }

    @Test
    @DisplayName("结构不对时抛业务异常（调用方据此回退到纯文本）")
    void invalidZipThrows() {
        assertThrows(Exception.class,
                () -> OfficeHtmlService.docxToHtml(utf8("this is not a zip")));
        assertThrows(Exception.class,
                () -> OfficeHtmlService.xlsxToHtml(utf8("this is not a zip")));
    }
}
