package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.service.LinkSegmenter.Result;
import com.leaqutra.shworkcloud.service.LinkSegmenter.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「链接特殊显示」的解析规则。
 * <p>
 * 这个类守的是**边界**，因为它的 bug 有两种截然不同的后果：
 * <ul>
 *   <li>把普通文字误判成链接 → 用户看到一段莫名其妙的蓝色下划线；</li>
 *   <li>把危险串误判成链接 → 可能给 {@code javascript:} 之类的东西一个 href，
 *       那就不只是难看，而是注入。<b>这一类是本测试的重点。</b></li>
 * </ul>
 */
class LinkSegmenterTest {

    private static String texts(List<Segment> segments) {
        return segments.stream().map(Segment::text).reduce("", String::concat);
    }

    private static List<Segment> links(Result result) {
        return result.segments().stream().filter(Segment::isLink).toList();
    }

    // ---------------------------------------------------------------- 基本切分

    @Test
    @DisplayName("纯文字：只有一个文字段，链接数为 0")
    void plainTextStaysOneSegment() {
        Result result = LinkSegmenter.split("今天作业好多啊");
        assertEquals(1, result.segments().size());
        assertEquals(LinkSegmenter.TYPE_TEXT, result.segments().get(0).type());
        assertEquals(0, result.linkCount());
        assertNull(result.segments().get(0).href());
    }

    @Test
    @DisplayName("中间夹一个链接：切成 文字 / 链接 / 文字 三段")
    void splitsAroundOneLink() {
        Result result = LinkSegmenter.split("参考资料 https://example.com/a 明天交");
        assertEquals(3, result.segments().size());
        assertEquals("参考资料 ", result.segments().get(0).text());
        assertEquals(LinkSegmenter.TYPE_LINK, result.segments().get(1).type());
        assertEquals("https://example.com/a", result.segments().get(1).href());
        assertEquals(" 明天交", result.segments().get(2).text());
        assertEquals(1, result.linkCount());
    }

    @Test
    @DisplayName("分段拼回去必须与原文完全一致（不能丢字或加字）")
    void segmentsRebuildOriginal() {
        String content = "开头 https://a.com/x?y=1 中间 http://b.cn 结尾";
        assertEquals(content, texts(LinkSegmenter.split(content).segments()));
    }

    @Test
    @DisplayName("多个链接都要认出来")
    void findsMultipleLinks() {
        Result result = LinkSegmenter.split("https://a.com 和 https://b.com/c");
        assertEquals(2, result.linkCount());
        assertEquals(List.of("https://a.com", "https://b.com/c"),
                links(result).stream().map(Segment::href).toList());
    }

    // ---------------------------------------------------------------- 危险协议

    @Test
    @DisplayName("javascript: 绝不是链接：不给 href，且文字原样保留")
    void rejectsJavascriptScheme() {
        String content = "点这个 javascript:alert(1) 会出事";
        Result result = LinkSegmenter.split(content);
        assertEquals(0, result.linkCount());
        // 关键：整串仍然是文字，没有被当成链接
        assertEquals(1, result.segments().size());
        assertEquals(LinkSegmenter.TYPE_TEXT, result.segments().get(0).type());
        assertEquals(content, result.segments().get(0).text());
    }

    @Test
    @DisplayName("data: / vbscript: / file: 同样不认")
    void rejectsOtherSchemes() {
        for (String bad : List.of(
                "data:text/html;base64,PHNjcmlwdD4=",
                "vbscript:msgbox(1)",
                "file:///etc/passwd")) {
            Result result = LinkSegmenter.split("看 " + bad + " 这里");
            assertEquals(0, result.linkCount(), bad + " 不该被识别成链接");
        }
    }

    @Test
    @DisplayName("只有协议头、没有主机名的不算链接")
    void rejectsSchemeOnly() {
        for (String bad : List.of("https://", "http://", "https:///path", "www.")) {
            assertEquals(0, LinkSegmenter.split("地址 " + bad + " 结束").linkCount(),
                    bad + " 不该被识别成链接");
        }
    }

    // ---------------------------------------------------------------- 末尾标点

    @Test
    @DisplayName("中文句号跟在网址后面时要剥掉（中文输入法下极常见）")
    void trimsChinesePunctuation() {
        Result result = LinkSegmenter.split("见 https://example.com/a。");
        assertEquals("https://example.com/a", links(result).get(0).href());
        // 句号必须留在正文里（它是句子的一部分），不能跟着 href 一起消失
        assertEquals("见 https://example.com/a。", texts(result.segments()),
                "分段拼回去必须与原文一字不差 —— 剥标点只影响 href，不影响显示的正文");
    }

    @Test
    @DisplayName("全角右括号同样要剥（中文输入法下括号几乎都是全角的）")
    void trimsFullWidthParen() {
        Result result = LinkSegmenter.split("（见 https://a.com/x）");
        assertEquals("https://a.com/x", links(result).get(0).href());
        assertEquals("（见 https://a.com/x）", texts(result.segments()));
    }

    @Test
    @DisplayName("全角书名号/方括号里的链接也要剥干净")
    void trimsOtherFullWidthPairs() {
        for (String template : List.of("【%s】", "《%s》", "“%s”")) {
            Result result = LinkSegmenter.split(template.formatted("https://a.com/x"));
            assertEquals("https://a.com/x", links(result).get(0).href(),
                    template + " 里的链接应剥掉包裹符号");
        }
    }

    @Test
    @DisplayName("英文句号/逗号同样剥掉")
    void trimsAsciiPunctuation() {
        assertEquals("https://a.com", links(LinkSegmenter.split("see https://a.com.")).get(0).href());
        assertEquals("https://a.com", links(LinkSegmenter.split("see https://a.com,")).get(0).href());
        assertEquals("https://a.com", links(LinkSegmenter.split("see https://a.com!")).get(0).href());
    }

    @Test
    @DisplayName("括号里的网址：剥掉句子的右括号")
    void trimsUnbalancedClosingParen() {
        Result result = LinkSegmenter.split("（见 https://a.com/x）");
        assertEquals("https://a.com/x", links(result).get(0).href());
    }

    @Test
    @DisplayName("网址自带成对括号时不能剥（维基百科式链接）")
    void keepsBalancedParens() {
        Result result = LinkSegmenter.split("见 https://a.com/wiki_(abc) 这里");
        assertEquals("https://a.com/wiki_(abc)", links(result).get(0).href());
    }

    // ---------------------------------------------------------------- www 补协议

    @Test
    @DisplayName("www. 开头自动补 https，但【显示文本】仍是用户写的原文")
    void wwwGetsHttpsHrefButShowsOriginal() {
        Result result = LinkSegmenter.split("看 www.example.com 这个站");
        Segment link = links(result).get(0);
        assertEquals("https://www.example.com", link.href(), "href 要补全协议");
        assertEquals("www.example.com", link.text(), "显示文本必须是原文，不能替用户改写");
    }

    // ---------------------------------------------------------------- 边界

    @Test
    @DisplayName("空内容 / null 返回空分段，不抛异常")
    void handlesEmptyInput() {
        assertEquals(0, LinkSegmenter.split(null).segments().size());
        assertEquals(0, LinkSegmenter.split("").segments().size());
        assertEquals(0, LinkSegmenter.split("   ").linkCount());
    }

    @Test
    @DisplayName("整条就是链接 / 链接在开头 / 链接在结尾，都不能多出空文字段")
    void handlesEdgePositions() {
        Result only = LinkSegmenter.split("https://a.com");
        assertEquals(1, only.segments().size());
        assertTrue(only.segments().get(0).isLink());

        Result atStart = LinkSegmenter.split("https://a.com 后面有字");
        assertEquals(2, atStart.segments().size());
        assertTrue(atStart.segments().get(0).isLink());

        Result atEnd = LinkSegmenter.split("前面有字 https://a.com");
        assertEquals(2, atEnd.segments().size());
        assertTrue(atEnd.segments().get(1).isLink());

        // 任何分段都不该是空串（空段会让前端多渲染一个空 <span>）
        for (Result result : List.of(only, atStart, atEnd)) {
            assertTrue(result.segments().stream().noneMatch(s -> s.text().isEmpty()));
        }
    }

    @Test
    @DisplayName("countLinks 与 split 的链接数一致（审核信号与渲染必须同源）")
    void countMatchesSplit() {
        String content = "a https://a.com b www.b.com c javascript:x d https://c.com";
        assertEquals(LinkSegmenter.split(content).linkCount(), LinkSegmenter.countLinks(content));
        assertEquals(3, LinkSegmenter.countLinks(content));
    }

    @Test
    @DisplayName("HTML 标签不会被当成链接，也不会被解析掉（正文永远是纯文本）")
    void htmlIsJustText() {
        String content = "<a href=\"https://evil.com\">点我</a>";
        Result result = LinkSegmenter.split(content);
        // 里面的 https://evil.com 会被识别成链接（它确实是链接），
        // 但标签本身作为文字原样保留 —— 前端用文本插值渲染，它不会变成可点击的标签
        assertEquals(content, texts(result.segments()));
        assertTrue(result.segments().stream().anyMatch(s -> s.text().contains("<a href=")));
    }
}
