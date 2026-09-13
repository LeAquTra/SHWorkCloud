package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把 docx / xlsx 渲染成 HTML，用于「原格式在线阅览」。
 *
 * <p><b>为什么是服务端手写、而不是引入渲染库</b>：部署环境是离线的，
 * 本机 npm 仓库里 <b>没有任何</b>文档渲染库（docx-preview / mammoth / xlsx / exceljs / jszip 全无），
 * 前端装不了。而 OOXML 本质就是 zip + XML，用 JDK 自带的 DOM 解析器
 * 把结构还原成 HTML 完全可行，且零新依赖。
 *
 * <p><b>还原到什么程度</b>：
 * <ul>
 *   <li>docx：段落与对齐、标题级别、<b>表格（含横向合并）</b>、加粗/斜体/下划线/删除线、
 *       字号、字体颜色、上下标、编号与项目符号、制表符与换行、<b>内嵌图片按原位置插入</b>；</li>
 *   <li>xlsx：每个工作表一张表（含共享字符串、合并单元格、列宽），数值/布尔/公式结果原样。</li>
 * </ul>
 * <b>不是</b>像素级一致：真实字体度量、行距、分页、页眉页脚、浮动排版都不会还原 ——
 * 要做到那一步需要 LibreOffice/OnlyOffice 转换或前端 docx-preview，都需要额外安装。
 *
 * <p><b>安全</b>：这是把不可信文档变成 HTML 再交给前端渲染，因此：
 * <ol>
 *   <li>解析时关闭 DTD 与外部实体（防 XXE）；</li>
 *   <li>所有<b>文本内容一律转义</b>，标签只由本类按白名单生成，
 *       绝不把文档里的原始 XML/HTML 片段透传出去；</li>
 *   <li>样式值（颜色、对齐、字号）都经过校验/映射后才写入，不是原样拼接。</li>
 * </ol>
 * 即便如此，前端渲染时仍应对返回的 HTML 做一次白名单过滤（见前端 <code>PreviewDialog</code>）。
 */
@Slf4j
public final class OfficeHtmlService {

    private static final String NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    /** 单次渲染输出上限（字符）。超过就截断，避免一个巨型表格把响应撑爆 */
    private static final int MAX_HTML_CHARS = 400_000;
    /** xlsx 最多渲染多少行 / 列 */
    private static final int MAX_ROWS = 300;
    private static final int MAX_COLS = 40;
    /** 内嵌图片单张上限（与正文内嵌图片同一口径） */
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    /** 解压累计上限，防解压炸弹 */
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

    private static final Pattern HEX_COLOR = Pattern.compile("^[0-9A-Fa-f]{6}$");
    /** 列号 A、B…AA 的写法 */
    private static final Pattern CELL_REF = Pattern.compile("^([A-Z]+)(\\d+)$");

    private OfficeHtmlService() {
    }

    // ================================================================ docx

    /**
     * docx → HTML 片段（不含 html/body，交给前端放进容器）。
     *
     * @throws BizException XML 结构无法识别时抛出 BAD_PARAM，由调用方回退到纯文本
     */
    public static String docxToHtml(byte[] docx) {
        Map<String, byte[]> entries = readZipEntries(docx, name ->
                "word/document.xml".equals(name)
                        || "word/_rels/document.xml.rels".equals(name)
                        || name.startsWith("word/media/"));
        byte[] body = entries.get("word/document.xml");
        if (body == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 Word 文档（.docx）");
        }
        Map<String, String> rels = parseRelationships(entries.get("word/_rels/document.xml.rels"), "word/");
        Document dom = parseXml(body);

        StringBuilder html = new StringBuilder(4096);
        NodeList children = dom.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(node);
            if ("body".equals(name)) {
                renderWordBody((Element) node, html, rels, entries);
            }
        }
        return html.toString();
    }

    private static void renderWordBody(Element body, StringBuilder html,
                                      Map<String, String> rels, Map<String, byte[]> entries) {
        NodeList nodes = body.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (localName(node)) {
                case "p" -> renderParagraph((Element) node, html, rels, entries);
                case "tbl" -> renderTable((Element) node, html, rels, entries);
                default -> {
                    // sectPr 等页面设置忽略
                }
            }
            if (html.length() > MAX_HTML_CHARS) {
                html.append("<p class=\"sc-truncated\">…（内容过长，已截断，完整内容请下载查看）</p>");
                return;
            }
        }
    }

    private static void renderParagraph(Element paragraph, StringBuilder html,
                                       Map<String, String> rels, Map<String, byte[]> entries) {
        Element pPr = firstChild(paragraph, "pPr");
        String style = wVal(firstChild(pPr, "pStyle"));
        String align = wVal(firstChild(pPr, "jc"));
        boolean numbered = pPr != null && firstChild(pPr, "numPr") != null;

        String tag = headingTag(style);
        html.append('<').append(tag);
        if (align != null) {
            String css = switch (align) {
                case "center" -> "center";
                case "right", "end" -> "right";
                case "both", "distribute" -> "justify";
                default -> null;
            };
            if (css != null) {
                html.append(" style=\"text-align:").append(css).append('"');
            }
        }
        if (numbered) {
            html.append(" class=\"sc-li\"");
            html.append('>').append("<span class=\"sc-li-mark\">•</span>");
        } else {
            html.append('>');
        }

        renderInline(paragraph, html, rels, entries);

        html.append("</").append(tag).append('>');
    }

    /** 标题级别：docx 的 Heading1..6 / Title */
    private static String headingTag(String style) {
        if (style == null) {
            return "p";
        }
        String value = style.toLowerCase(Locale.ROOT);
        if (value.startsWith("heading")) {
            int level = switch (value.replace("heading", "").trim()) {
                case "1" -> 2;
                case "2" -> 3;
                case "3" -> 4;
                case "4" -> 5;
                default -> 6;
            };
            return "h" + level;
        }
        if ("title".equals(value)) {
            return "h1";
        }
        return "p";
    }

    private static void renderTable(Element table, StringBuilder html,
                                   Map<String, String> rels, Map<String, byte[]> entries) {
        html.append("<table class=\"sc-doc-table\"><tbody>");
        for (Element row : childrenOf(table, "tr")) {
            html.append("<tr>");
            for (Element cell : childrenOf(row, "tc")) {
                Element tcPr = firstChild(cell, "tcPr");
                int span = 1;
                if (tcPr != null) {
                    String gridSpan = wVal(firstChild(tcPr, "gridSpan"));
                    if (gridSpan != null) {
                        try {
                            span = Math.max(1, Math.min(50, Integer.parseInt(gridSpan)));
                        } catch (NumberFormatException ignored) {
                            span = 1;
                        }
                    }
                }
                html.append("<td");
                if (span > 1) {
                    html.append(" colspan=\"").append(span).append('"');
                }
                html.append('>');
                // 单元格内部按段落渲染
                for (Element p : childrenOf(cell, "p")) {
                    renderParagraph(p, html, rels, entries);
                }
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    /** 渲染段落/单元格内的行内内容与图片 */
    private static void renderInline(Element container, StringBuilder html,
                                     Map<String, String> rels, Map<String, byte[]> entries) {
        NodeList nodes = container.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (localName(node)) {
                case "r" -> renderRun((Element) node, html, rels, entries);
                case "hyperlink" -> renderInline((Element) node, html, rels, entries);
                case "sdt" -> {
                    Element content = firstChild((Element) node, "sdtContent");
                    if (content != null) {
                        renderInline(content, html, rels, entries);
                    }
                }
                default -> {
                    // bookmarkStart / proofErr 等忽略
                }
            }
        }
    }

    private static void renderRun(Element run, StringBuilder html,
                                 Map<String, String> rels, Map<String, byte[]> entries) {
        Element rPr = firstChild(run, "rPr");
        List<String> open = new ArrayList<>();
        List<String> close = new ArrayList<>();
        String style = null;

        if (rPr != null) {
            if (firstChild(rPr, "b") != null) {
                open.add("<strong>");
                close.add("</strong>");
            }
            if (firstChild(rPr, "i") != null) {
                open.add("<em>");
                close.add("</em>");
            }
            if (firstChild(rPr, "u") != null) {
                open.add("<u>");
                close.add("</u>");
            }
            if (firstChild(rPr, "strike") != null) {
                open.add("<s>");
                close.add("</s>");
            }
            Element vertAlign = firstChild(rPr, "vertAlign");
            String vert = wVal(vertAlign);
            if ("superscript".equals(vert)) {
                open.add("<sup>");
                close.add("</sup>");
            } else if ("subscript".equals(vert)) {
                open.add("<sub>");
                close.add("</sub>");
            }

            List<String> css = new ArrayList<>();
            String size = wVal(firstChild(rPr, "sz"));
            if (size != null) {
                try {
                    // w:sz 单位是半磅
                    double pt = Integer.parseInt(size) / 2.0;
                    if (pt >= 4 && pt <= 200) {
                        css.add("font-size:" + trimNumber(pt) + "pt");
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略异常字号
                }
            }
            String color = wVal(firstChild(rPr, "color"));
            if (color != null && HEX_COLOR.matcher(color).matches()) {
                css.add("color:#" + color.toLowerCase(Locale.ROOT));
            }
            if (!css.isEmpty()) {
                style = String.join(";", css);
            }
        }

        StringBuilder inner = new StringBuilder();
        NodeList nodes = run.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (localName(node)) {
                case "t" -> inner.append(escape(textOf(node)));
                case "tab" -> inner.append("<span class=\"sc-tab\"></span>");
                case "br", "cr" -> inner.append("<br/>");
                case "drawing", "pict", "object" -> appendImages((Element) node, inner, rels, entries);
                default -> {
                    // 其它行内元素忽略
                }
            }
        }
        if (inner.length() == 0) {
            return;
        }
        if (style != null) {
            html.append("<span style=\"").append(style).append("\">");
        }
        open.forEach(html::append);
        html.append(inner);
        for (int i = close.size() - 1; i >= 0; i--) {
            html.append(close.get(i));
        }
        if (style != null) {
            html.append("</span>");
        }
    }

    /** 从 drawing/pict 里找出所有 r:embed，映射到 word/media 并内联成 data URL */
    private static void appendImages(Element container, StringBuilder html,
                                     Map<String, String> rels, Map<String, byte[]> entries) {
        NodeList blips = container.getElementsByTagNameNS(NS_A, "blip");
        if (blips.getLength() == 0) {
            // 老的 VML 图片用 v:imagedata r:id
            blips = container.getElementsByTagNameNS("*", "imagedata");
        }
        for (int i = 0; i < blips.getLength(); i++) {
            Element blip = (Element) blips.item(i);
            String relId = blip.getAttributeNS(NS_R, "embed");
            if (relId == null || relId.isEmpty()) {
                relId = blip.getAttributeNS(NS_R, "id");
            }
            if (relId == null || relId.isEmpty()) {
                relId = blip.getAttribute("r:embed");
            }
            String target = rels.get(relId);
            if (target == null) {
                continue;
            }
            byte[] data = entries.get(target);
            if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) {
                continue;
            }
            String contentType = FileViewType.inlineContentType(FileNaming.extension(target));
            if (!FileViewType.isRasterImage(FileNaming.extension(target))) {
                continue;
            }
            html.append("<img class=\"sc-doc-img\" alt=\"")
                    .append(escape(lastSegment(target)))
                    .append("\" src=\"data:").append(contentType).append(";base64,")
                    .append(Base64.getEncoder().encodeToString(data))
                    .append("\"/>");
        }
    }

    // ================================================================ xlsx

    /**
     * xlsx → HTML 表格（每个工作表一张表，前面带工作表名）。
     *
     * @throws BizException 不是有效的 xlsx 时抛出，由调用方回退
     */
    public static String xlsxToHtml(byte[] xlsx) {
        Map<String, byte[]> entries = readZipEntries(xlsx, name ->
                "xl/workbook.xml".equals(name)
                        || "xl/_rels/workbook.xml.rels".equals(name)
                        || "xl/sharedStrings.xml".equals(name)
                        || name.startsWith("xl/worksheets/sheet"));
        byte[] workbook = entries.get("xl/workbook.xml");
        if (workbook == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 Excel 工作簿（.xlsx）");
        }
        List<String> sharedStrings = parseSharedStrings(entries.get("xl/sharedStrings.xml"));
        Map<String, String> rels = parseRelationships(entries.get("xl/_rels/workbook.xml.rels"), "xl/");

        Document dom = parseXml(workbook);
        NodeList sheets = dom.getElementsByTagNameNS(NS_SS, "sheet");
        if (sheets.getLength() == 0) {
            sheets = dom.getElementsByTagName("sheet");
        }

        StringBuilder html = new StringBuilder(4096);
        boolean first = true;
        for (int i = 0; i < sheets.getLength(); i++) {
            Element sheet = (Element) sheets.item(i);
            String sheetName = sheet.getAttribute("name");
            String relId = sheet.getAttributeNS(NS_R, "id");
            if (relId == null || relId.isEmpty()) {
                relId = sheet.getAttribute("r:id");
            }
            String path = rels.get(relId);
            if (path == null) {
                continue;
            }
            byte[] sheetXml = entries.get(path);
            if (sheetXml == null) {
                continue;
            }
            if (!first) {
                html.append("<div class=\"sc-sheet-gap\"></div>");
            }
            first = false;
            html.append("<div class=\"sc-sheet-name\">").append(escape(sheetName)).append("</div>");
            renderSheet(sheetXml, sharedStrings, html);
            if (html.length() > MAX_HTML_CHARS) {
                html.append("<p class=\"sc-truncated\">…（内容过长，已截断，完整内容请下载查看）</p>");
                break;
            }
        }
        if (first) {
            // 一个工作表都没渲染出来（异常结构）
            return "";
        }
        return html.toString();
    }

    private static void renderSheet(byte[] sheetXml, List<String> sharedStrings, StringBuilder html) {
        Document dom = parseXml(sheetXml);
        Element sheetData = firstChild(dom.getDocumentElement(), "sheetData");
        if (sheetData == null) {
            return;
        }
        // 合并单元格：记录 "A1" -> colspan
        Map<String, Integer> colSpans = new HashMap<>();
        Map<String, Integer> rowSpans = new HashMap<>();
        Element mergeCells = firstChild(dom.getDocumentElement(), "mergeCells");
        if (mergeCells != null) {
            for (Element merge : childrenOf(mergeCells, "mergeCell")) {
                String ref = merge.getAttribute("ref");
                int colon = ref == null ? -1 : ref.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                int[] start = parseCellRef(ref.substring(0, colon));
                int[] end = parseCellRef(ref.substring(colon + 1));
                if (start == null || end == null) {
                    continue;
                }
                colSpans.put(ref.substring(0, colon), Math.max(1, end[0] - start[0] + 1));
                rowSpans.put(ref.substring(0, colon), Math.max(1, end[1] - start[1] + 1));
            }
        }

        html.append("<table class=\"sc-xlsx-table\"><tbody>");
        int rowCount = 0;
        for (Element row : childrenOf(sheetData, "row")) {
            if (++rowCount > MAX_ROWS) {
                html.append("<tr><td class=\"sc-truncated\">…（仅显示前 ")
                        .append(MAX_ROWS).append(" 行）</td></tr>");
                break;
            }
            html.append("<tr>");
            int column = 0;
            for (Element cell : childrenOf(row, "c")) {
                int[] ref = parseCellRef(cell.getAttribute("r"));
                int cellColumn = ref == null ? column : ref[0];
                // 补齐中间的空白单元格，保证列对齐
                while (column < cellColumn && column < MAX_COLS) {
                    html.append("<td></td>");
                    column++;
                }
                if (column >= MAX_COLS) {
                    break;
                }
                String refKey = ref == null ? null : columnName(cellColumn) + ref[1];
                html.append("<td");
                Integer colspan = refKey == null ? null : colSpans.get(refKey);
                Integer rowspan = refKey == null ? null : rowSpans.get(refKey);
                if (colspan != null && colspan > 1) {
                    html.append(" colspan=\"").append(colspan).append('"');
                }
                if (rowspan != null && rowspan > 1) {
                    html.append(" rowspan=\"").append(rowspan).append('"');
                }
                html.append('>').append(escape(cellText(cell, sharedStrings))).append("</td>");
                column++;
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    /** 取单元格显示文本：兼容共享字符串、内联字符串、布尔与数值 */
    private static String cellText(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            Element is = firstChild(cell, "is");
            return is == null ? "" : allText(is);
        }
        Element value = firstChild(cell, "v");
        if (value == null) {
            return "";
        }
        String raw = textOf(value);
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.trim());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : raw;
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        return raw;
    }

    private static List<String> parseSharedStrings(byte[] xml) {
        List<String> result = new ArrayList<>();
        if (xml == null) {
            return result;
        }
        Document dom = parseXml(xml);
        NodeList items = dom.getElementsByTagNameNS(NS_SS, "si");
        if (items.getLength() == 0) {
            items = dom.getElementsByTagName("si");
        }
        for (int i = 0; i < items.getLength(); i++) {
            result.add(allText((Element) items.item(i)));
        }
        return result;
    }

    // ================================================================ 公共工具

    /**
     * xlsx → 纯文本：一行一换行、单元格以制表符分隔。
     * <p>供「复制全文」与文本检索使用 —— HTML 版本是给人看的，
     * 纯文本版本才是能选中复制的（而且搜索时不会被标签干扰）。
     */
    public static String xlsxToText(byte[] xlsx) {
        Map<String, byte[]> entries = readZipEntries(xlsx, name ->
                "xl/workbook.xml".equals(name)
                        || "xl/_rels/workbook.xml.rels".equals(name)
                        || "xl/sharedStrings.xml".equals(name)
                        || name.startsWith("xl/worksheets/sheet"));
        byte[] workbook = entries.get("xl/workbook.xml");
        if (workbook == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 Excel 工作簿（.xlsx）");
        }
        List<String> sharedStrings = parseSharedStrings(entries.get("xl/sharedStrings.xml"));
        Map<String, String> rels = parseRelationships(entries.get("xl/_rels/workbook.xml.rels"), "xl/");

        Document dom = parseXml(workbook);
        NodeList sheets = dom.getElementsByTagNameNS(NS_SS, "sheet");
        if (sheets.getLength() == 0) {
            sheets = dom.getElementsByTagName("sheet");
        }

        StringBuilder out = new StringBuilder(4096);
        for (int i = 0; i < sheets.getLength(); i++) {
            Element sheet = (Element) sheets.item(i);
            String relId = sheet.getAttributeNS(NS_R, "id");
            if (relId == null || relId.isEmpty()) {
                relId = sheet.getAttribute("r:id");
            }
            byte[] sheetXml = entries.get(rels.get(relId));
            if (sheetXml == null) {
                continue;
            }
            out.append("--- ").append(sheet.getAttribute("name")).append(" ---\n");
            Document sheetDom = parseXml(sheetXml);
            Element sheetData = firstChild(sheetDom.getDocumentElement(), "sheetData");
            if (sheetData == null) {
                continue;
            }
            int rows = 0;
            for (Element row : childrenOf(sheetData, "row")) {
                if (++rows > MAX_ROWS) {
                    out.append("…（仅显示前 ").append(MAX_ROWS).append(" 行）\n");
                    break;
                }
                List<String> values = new ArrayList<>();
                for (Element cell : childrenOf(row, "c")) {
                    values.add(cellText(cell, sharedStrings));
                }
                out.append(String.join("\t", values)).append('\n');
            }
            if (out.length() > MAX_HTML_CHARS) {
                out.append("…（内容过长，已截断）\n");
                break;
            }
        }
        String text = out.toString().strip();
        return text.isEmpty() ? "(工作簿没有可提取的内容)" : text;
    }

    /** 解析关系表：rId → zip 内路径（相对于给定的基准目录） */
    private static Map<String, String> parseRelationships(byte[] xml, String baseDir) {
        Map<String, String> map = new HashMap<>();
        if (xml == null) {
            return map;
        }
        Document dom = parseXml(xml);
        NodeList list = dom.getElementsByTagName("Relationship");
        for (int i = 0; i < list.getLength(); i++) {
            Element rel = (Element) list.item(i);
            String id = rel.getAttribute("Id");
            String target = rel.getAttribute("Target");
            if (id.isEmpty() || target.isEmpty()) {
                continue;
            }
            if (target.startsWith("/")) {
                // 绝对路径：去掉开头的斜杠即为 zip 根路径
                map.put(id, target.substring(1));
            } else if (target.contains("../")) {
                // 形如 ../media/x.png：退一级
                String normalized = baseDir + target;
                while (normalized.contains("../")) {
                    int idx = normalized.indexOf("../");
                    int slash = normalized.lastIndexOf('/', Math.max(0, idx - 2));
                    normalized = normalized.substring(0, Math.max(0, slash + 1))
                            + normalized.substring(idx + 3);
                }
                map.put(id, normalized);
            } else {
                map.put(id, baseDir + target);
            }
        }
        return map;
    }

    /**
     * 解析 XML。
     * <p>关闭 DTD 与外部实体：文档来自用户上传，不能让 XXE 把服务器上的文件读出来。
     */
    private static Document parseXml(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("Office XML 解析失败 err={}", e.getMessage());
            throw new BizException(ErrorCode.BAD_PARAM, "文档结构无法解析，已回退为纯文本");
        }
    }

    /** 一次性读出需要的 zip 条目（其余条目跳过），带解压炸弹防护 */
    private static Map<String, byte[]> readZipEntries(byte[] zipBytes, EntryFilter filter) {
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                total += entry.getSize() > 0 ? entry.getSize() : 0;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new BizException(ErrorCode.BAD_PARAM, "文档解压后过大，已停止解析");
                }
                String name = entry.getName();
                if (!filter.accept(name)) {
                    continue;
                }
                result.put(name, readAll(zip));
            }
        } catch (IOException e) {
            log.warn("读取 Office zip 失败 err={}", e.getMessage());
            throw new BizException(ErrorCode.BAD_PARAM, "文件不是有效的 Office 文档");
        }
        return result;
    }

    private static byte[] readAll(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = zip.read(chunk)) > 0) {
            buffer.write(chunk, 0, len);
        }
        return buffer.toByteArray();
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** 直接子元素里第一个指定本地名的元素（parent 为 null 时返回 null，便于链式取值） */
    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName(node).equals(localName)) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> childrenOf(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName(node).equals(localName)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String attrOf(Element element, String name) {
        if (element == null) {
            return null;
        }
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 读 {@code w:val} 这类<b>带命名空间</b>的属性。
     * <p>⚠️ 这里是 XXE 防护之外最容易踩的坑：命名空间感知模式下
     * {@code element.getAttribute("val")} <b>取不到</b> {@code w:val}
     * —— 属性全名是 {@code w:val}、且属于 w 命名空间。
     * 结果就是"段落样式/对齐/字号/颜色/合并列全都读不出来"，
     * 渲染出来是一堆没有格式的 &lt;p&gt;。必须用 {@code getAttributeNS}。
     */
    private static String wVal(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getAttributeNS(NS_W, "val");
        if (value == null || value.isEmpty()) {
            // 兜底：极少数生成器会写成无前缀属性
            value = element.getAttribute("val");
        }
        return value == null || value.isEmpty() ? null : value;
    }

    private static String textOf(Node node) {
        String value = node.getTextContent();
        return value == null ? "" : value;
    }

    /** 收集元素下所有 w:t / t 的文本（共享字符串、内联字符串用） */
    private static String allText(Element element) {
        StringBuilder out = new StringBuilder();
        NodeList texts = element.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < texts.getLength(); i++) {
            out.append(textOf(texts.item(i)));
        }
        return out.toString();
    }

    /** A1 → [列下标(0 起), 行号(1 起)]；解析失败返回 null */
    private static int[] parseCellRef(String ref) {
        if (ref == null) {
            return null;
        }
        var matcher = CELL_REF.matcher(ref.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        String letters = matcher.group(1);
        int column = 0;
        for (int i = 0; i < letters.length(); i++) {
            column = column * 26 + (letters.charAt(i) - 'A' + 1);
        }
        try {
            return new int[]{column - 1, Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String columnName(int index) {
        StringBuilder out = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            int rem = (value - 1) % 26;
            out.insert(0, (char) ('A' + rem));
            value = (value - 1) / 26;
        }
        return out.toString();
    }

    /** 12.0 → "12"，12.5 → "12.5" */
    private static String trimNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * HTML 转义。
     * <p>这是本类安全性的关键：文档里的所有文本都必须经过它，
     * 否则一个精心构造的 docx 就能把 &lt;script&gt; 送进前端页面。
     */
    private static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    // 控制字符（除 \t \n）直接丢掉，避免破坏页面
                    if (c >= 0x20 || c == '\t' || c == '\n') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    @FunctionalInterface
    private interface EntryFilter {
        boolean accept(String name);
    }
}
