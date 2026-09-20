package com.leaqutra.shworkcloud.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把帖子正文切成「文字段 / 链接段」。
 * <p>
 * <b>为什么由服务端切，而不是让前端用正则切：</b>
 * <ol>
 *   <li>链接的边界规则（末尾的句号算不算网址的一部分、括号怎么处理、
 *       {@code www.} 要不要补协议）是<b>业务规则</b>，写在服务端才能单测，
 *       也才能与"链接数"这个审核信号共用同一份判定；</li>
 *   <li>前端只负责把 {@code type=link} 的段渲染成 {@code <a>}，不再解析任何东西 ——
 *       渲染层越薄，注入面越小。这与 {@code OfficeHtmlService} 的服务端渲染是同一取向。</li>
 * </ol>
 * <p>
 * <b>只认 http / https / www.</b> 不认 {@code javascript:}、{@code data:}、
 * {@code vbscript:} 这类协议 —— 它们不是"链接"，而是注入载荷。这与
 * {@code FileViewType} 拒绝内联 html/svg 的理由一致。
 * <p>
 * <b>纯函数、不碰数据库、不依赖 Spring</b>，便于单测（见 {@code LinkSegmenterTest}）。
 */
public final class LinkSegmenter {

    /** 文字段 */
    public static final String TYPE_TEXT = "text";

    /** 链接段 */
    public static final String TYPE_LINK = "link";

    /**
     * 匹配候选链接。
     * <p>刻意写得宽松（先把可能的都圈出来），再在 {@link #classify} 里逐个收紧 ——
     * 一个正则同时兼顾"找边界"和"校验合法性"会把两边都写糊。
     */
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?:https?://|www\\.)[^\\s<>\"'`\\\\]+",
            Pattern.CASE_INSENSITIVE);

    /**
     * 需要从网址末尾剥掉的字符。
     * <p>典型场景：{@code 看这个 https://example.com/a。} —— 句号属于句子，
     * 不属于网址；{@code (https://example.com/a)} 里的右括号同理。
     * <p><b>但要小心</b>：{@code https://example.com/a_(b)} 这种网址本身以
     * 右括号结尾是合法的（维基百科链接常见）。所以规则是"成对才算数"，
     * 见 {@link #trimTrailing}。
     * <p>
     * ⚠️ <b>这里刻意写成 {@code \\uXXXX} 转义而不是直接写中文标点。</b>
     * 原因是本机 JVM 默认编码是 GBK（{@code file.encoding=GBK}），而
     * maven-compiler-plugin 在当前配置下会沿用平台编码读源码，导致
     * <b>源码里的非 ASCII 字符串字面量在编译期就被解码坏</b>：
     * <pre>
     *   源码（UTF-8）： "、。，；：！？…"
     *   编译后常量：    "����������"        ← 判定全部失效
     * </pre>
     * 既有代码里的中文（"下载"、"作业.docx"）都在 GBK 码位范围内，所以一直没暴露；
     * 只有 GBK 覆盖不全的字符才会现形。用转义写死就与"用什么编码读源码"彻底无关，
     * 这是最稳的做法（{@code pom.xml} 里也补了 encoding 配置作为第二道防线）。
     * <p>回归守卫：{@link com.leaqutra.shworkcloud.service.LinkSegmenterTest}。
     */
    private static final String TRAILING_ALWAYS =
            ".,;:!?"            // ASCII 标点
                    + "\u3001"  // 、 顿号
                    + "\u3002"  // 。 句号
                    + "\uFF0C"  // ， 全角逗号
                    + "\uFF1B"  // ； 全角分号
                    + "\uFF1A"  // ： 全角冒号
                    + "\uFF01"  // ！ 全角叹号
                    + "\uFF1F"  // ？ 全角问号
                    + "\u2026"; // …  省略号

    /**
     * 全角成对符号。中文输入法下的括号几乎都是全角的，
     * 只处理 ASCII 的 {@code ()} 会让 {@code （见 https://a.com）} 把全角右括号
     * 留在网址里 —— 这正是本类最早的一个真实 bug（由 {@code LinkSegmenterTest} 抓到）。
     * <p>注意全角左/右括号不是相邻码位（{@code U+FF08}/{@code U+FF09} 是相邻的，
     * 但 {@code U+3010}/{@code U+3011} 也是），所以逐对列出而不是加减 1。
     */
    private static final char[][] TRAILING_PAIRS = {
            {'(', ')'},                 // ASCII 圆括号
            {'[', ']'},                 // ASCII 方括号
            {'{', '}'},                 // ASCII 花括号
            {'\uFF08', '\uFF09'},       // （）
            {'\u3010', '\u3011'},       // 【】
            {'\u300A', '\u300B'},       // 《》
            {'\u201C', '\u201D'},       // “”
    };

    private LinkSegmenter() {
    }

    /**
     * 解析结果：分段 + 链接数。
     * <p>{@code linkCount} 单独给出来，是因为它要落库（{@code post.link_count}）
     * 供审核队列筛选；让调用方自己数一遍分段会多一次遍历，也容易两处口径不一致。
     */
    public record Result(List<Segment> segments, int linkCount) {
    }

    /**
     * 一个分段。
     *
     * @param type 见 {@link #TYPE_TEXT} / {@link #TYPE_LINK}
     * @param text 要显示的文本（链接段显示的是原文，不是改写后的 URL）
     * @param href 仅链接段有值，且一定是 {@code http(s)://} 开头；
     *             前端把它直接放进 {@code <a href>}，不再做任何拼接
     */
    public record Segment(String type, String text, String href) {

        static Segment text(String value) {
            return new Segment(TYPE_TEXT, value, null);
        }

        static Segment link(String value, String href) {
            return new Segment(TYPE_LINK, value, href);
        }

        public boolean isLink() {
            return TYPE_LINK.equals(type);
        }
    }

    /**
     * 切成「文字 / 链接」交替的分段序列，相邻的文字段会被合并。
     * <p>
     * 解析不出来的候选串（例如 {@code javascript:...}）<b>直接留在文字里</b>：
     * 光标只跳过真正被识别成链接的部分，所以它们会作为普通文字被带出去，
     * 既不会丢字，也拿不到 {@code href}。
     */
    public static Result split(String content) {
        List<Segment> segments = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return new Result(segments, 0);
        }

        Matcher matcher = CANDIDATE.matcher(content);
        StringBuilder pending = new StringBuilder();
        int links = 0;
        // cursor 只表示"已经被切走的位置"，识别失败的候选不会推进它
        int cursor = 0;

        while (matcher.find()) {
            String href = classify(matcher.group());
            if (href == null) {
                continue;
            }
            pending.append(content, cursor, matcher.start());
            if (pending.length() > 0) {
                segments.add(Segment.text(pending.toString()));
                pending.setLength(0);
            }
            segments.add(Segment.link(matcher.group(), href));
            links++;
            cursor = matcher.end();
        }

        pending.append(content, cursor, content.length());
        if (pending.length() > 0) {
            segments.add(Segment.text(pending.toString()));
        }

        return new Result(segments, links);
    }

    /** 只要链接数（审核筛选用），不做分段 */
    public static int countLinks(String content) {
        return split(content).linkCount();
    }

    /**
     * 判断一个候选串是不是合法链接，并返回规范化的 {@code href}。
     *
     * @return {@code http(s)://} 开头的地址；不是链接则返回 {@code null}
     */
    private static String classify(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = trimTrailing(raw);
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);

        String href;
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            href = trimmed;
        } else if (lower.startsWith("www.")) {
            // 用户写的 "www.example.com" 补成 https —— 这是展示层的便利，
            // 不影响"原文照旧显示"（Segment.text 仍是用户写的那串）
            href = "https://" + trimmed;
        } else {
            // 其它协议（javascript: / data: / vbscript: / file: …）一律不认
            return null;
        }

        // 光有协议头不算链接："https://" 后面必须有主机名
        String rest = href.substring(href.indexOf("//") + 2);
        if (rest.isEmpty() || rest.startsWith("/") || !hasHost(rest)) {
            return null;
        }
        return href;
    }

    /** 主机名至少要有字母或数字，且不能全是标点 */
    private static boolean hasHost(String rest) {
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        if (host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉网址末尾不属于它的标点。
     * <p>
     * 分两类处理：
     * <ul>
     *   <li>句号/逗号/顿号等：<b>一律剥掉</b>（中文输入法下 "https://a.com。" 极常见）；</li>
     *   <li>右括号：<b>只有不成对时</b>才剥掉。{@code (https://a.com)} 里那个是句子的括号，
     *       而 {@code https://a.com/a_(b)} 里的右括号属于网址本身。</li>
     * </ul>
     */
    private static String trimTrailing(String raw) {
        String value = raw;
        boolean changed = true;
        while (changed && !value.isEmpty()) {
            changed = false;

            char last = value.charAt(value.length() - 1);
            if (TRAILING_ALWAYS.indexOf(last) >= 0) {
                value = value.substring(0, value.length() - 1);
                changed = true;
                continue;
            }

            for (char[] pair : TRAILING_PAIRS) {
                if (last == pair[1]) {
                    int open = countOf(value, pair[0]);
                    int close = countOf(value, pair[1]);
                    if (close > open) {
                        value = value.substring(0, value.length() - 1);
                        changed = true;
                    }
                    break;
                }
            }
        }
        return value;
    }

    private static int countOf(String value, char c) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
