package com.leaqutra.shworkcloud.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper 结果集列数 ↔ record 构造器参数数量的契约守卫。
 * <p>
 * <b>背景（线上真实故障）</b>：加好友与按 ID 找人两个接口都返回 500，
 * 响应体是：
 * <pre>
 *   Constructor auto-mapping of 'FriendUserRow(...11 args...)' failed.
 *   The constructor takes '11' arguments, but there are only '10' columns in the result set.
 * </pre>
 * 根因是 MyBatis 把查询结果映射到 {@code record} 时<b>按位置对齐</b>：
 * 查询里的第 N 列必须是 record 的第 N 个组件。而
 * {@code FriendRelationMapper.FriendUserRow} 当时有 11 个组件
 * （多一个只在申请场景用得上的 {@code requestedAt} 与 {@code friendSince}），
 * 好友查询只给了 9 列、搜索查询给了 9 列 —— <b>列数与组件数不一致，
 * 而且这在编译期完全看不出来</b>，只有真跑一次 SQL 才会炸。
 * <p>
 * <b>为什么要有这个静态守卫</b>：真正能发现它的当然是集成测试
 * （{@code FriendApiIntegrationTest}），但集成测试需要 MySQL/Redis，
 * 默认不跑。这个测试纯文本分析 Mapper 源码，<b>在没有数据库的机器上也能拦住同一个错</b>，
 * 是本项目"提交前就能发现"的那一道防线。
 * <p>
 * 它检查两件事：
 * <ol>
 *   <li>每个返回自定义 record 的查询，SELECT 的列数 == record 的组件数；</li>
 *   <li>SELECT 里不出现"上一个列名后面漏了逗号"这种拼接错误
 *       （{@code AS requested_at} 紧跟 {@code NULL AS x} 而没有逗号 —— 这正是
 *       我修 bug 时用脚本批量插入列所犯的错）。</li>
 * </ol>
 */
class MapperResultSetContractTest {

    private static final Path MAPPER_DIR =
            Path.of("src", "main", "java", "com", "leaqutra", "shworkcloud", "mapper");

    /** 一个查询块：@Select 文本块 + 紧随其后的方法签名 */
    private record QueryBlock(String mapper, String sql, String method, String returnType) {
    }

    /** 解析一个 Mapper 文件里所有"@Select 文本块 + 返回类型为自定义 record 的方法" */
    private static List<QueryBlock> parse(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String mapper = file.getFileName().toString();

        // 先收齐本文件里声明的 record 组件数
        List<QueryBlock> blocks = new ArrayList<>();
        Matcher m = Pattern.compile(
                "(?s)@Select\\(\"\"\"(.*?)\"\"\"\\)(.*?);", Pattern.MULTILINE).matcher(source);
        while (m.find()) {
            String sql = m.group(1);
            String after = m.group(2);
            Matcher sig = Pattern.compile("List<(\\w+)>\\s+(\\w+)\\(").matcher(after);
            if (sig.find()) {
                blocks.add(new QueryBlock(mapper, sql, sig.group(2), sig.group(1)));
            }
        }
        return blocks;
    }

    /**
     * 统计 SELECT 列清单里的表达式个数。
     * <p>
     * ⚠️ <b>找 FROM 必须排除下划线</b>，不能只写 {@code \bFROM\b}：
     * 下划线在正则里算"单词字符"，所以 {@code \bFROM\b} <b>仍然会匹配
     * {@code r.friend_id} 里的子串 "from"</b>（{@code d} 后是 {@code _} 也算边界）。
     * <p>这个守卫连续两版都栽在同一处：
     * <ol>
     *   <li>第一版用 {@code indexOf("FROM")} —— 直接被 {@code r.friend_id} 截断，少数一列；</li>
     *   <li>第二版改成 {@code \bFROM\b} —— 仍被 {@code d_} 之间的边界匹配，还是少数一列。</li>
     * </ol>
     * 两次都是<b>守卫自己的 bug 伪装成被测代码的 bug</b>，差点让我去改没问题的 Mapper。
     * 现在用 {@code (?<![_\w])FROM(?![_\w])} 显式排除下划线。
     */
    private static int countSelectedColumns(String sql) {
        Matcher select = Pattern.compile(
                "(?is)SELECT\\s+(.*?)\\s+(?<![_\\w])FROM(?![_\\w])").matcher(sql);
        assertTrue(select.find(),
                "解析不出 SELECT ... FROM 结构，本测试的假设失效了：\n" + sql);
        String columnsPart = select.group(1);

        int depth = 0;
        int columns = 1;          // 至少一列
        for (char c : columnsPart.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                columns++;
            }
        }
        return columns;
    }

    /** 取同文件里某个 record 的组件数 */
    private static int countRecordComponents(Path file, String recordName) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
                "(?s)record\\s+" + recordName + "\\s*\\((.*?)\\)\\s*\\{").matcher(source);
        assertTrue(m.find(), file.getFileName() + " 里找不到 record " + recordName);
        String params = m.group(1);
        int depth = 0;
        int count = 0;
        boolean hasContent = false;
        for (char c : params.toCharArray()) {
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            } else if (!Character.isWhitespace(c)) {
                hasContent = true;
            }
        }
        return hasContent ? count + 1 : 0;
    }

    private static List<Path> mapperFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(MAPPER_DIR)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        assertTrue(!files.isEmpty(), "没有找到任何 Mapper 文件，测试自身的路径可能写错了");
        return files;
    }

    // ---------------------------------------------------------------- 列数 == 组件数

    @Test
    @DisplayName("🔴 每个返回自定义 record 的 @Select，列数必须等于 record 的组件数")
    void selectColumnCountMatchesRecordArity() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Path file : mapperFiles()) {
            for (QueryBlock block : parse(file)) {
                // 只检查返回自定义 record 的查询（返回实体/基本类型的不适用）
                int arity;
                try {
                    arity = countRecordComponents(file, block.returnType());
                } catch (AssertionError notARecord) {
                    continue;   // 返回的是实体类，跳过
                }
                int columns = countSelectedColumns(block.sql());
                checked++;
                if (columns != arity) {
                    problems.add("%s#%s 返回 %s（%d 个组件）但 SELECT 了 %d 列 —— "
                            .formatted(block.mapper(), block.method(), block.returnType(),
                                    arity, columns)
                            + "MyBatis 按位置映射，不一致会在运行期抛 "
                            + "\"constructor takes N arguments, but there are only M columns\"");
                }
            }
        }

        assertTrue(checked >= 4,
                "只检查了 " + checked + " 个查询，预期至少 4 个 —— 解析逻辑可能失效了");
        assertTrue(problems.isEmpty(),
                "列数与 record 组件数不一致（这正是导致接口 500 的那类错误）：\n  "
                        + String.join("\n  ", problems));
    }

    // ---------------------------------------------------------------- 逗号漏写

    @Test
    @DisplayName("列清单里不能出现「上一个表达式后漏逗号」（AS x 紧跟 NULL AS y）")
    void noMissingCommaBetweenColumns() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path file : mapperFiles()) {
            for (QueryBlock block : parse(file)) {
                // 先把换行压成单空格再判断：否则"列末尾的换行 + 下一列的缩进"
                // 会被误判成漏逗号（这个测试第一版就踩了这个坑，7 处全是误报）
                String flat = block.sql().replaceAll("\\s+", " ");
                // AS <别名> 之后必须紧跟逗号或 FROM，否则就是漏了逗号
                Matcher m = Pattern.compile("(?i)AS\\s+\\w+\\s+(?![,]|FROM\\b)")
                        .matcher(flat);
                while (m.find()) {
                    String tail = flat.substring(m.end(),
                            Math.min(flat.length(), m.end() + 24));
                    problems.add(block.mapper() + "#" + block.method()
                            + " 在 '" + m.group().strip() + " ... " + tail.strip()
                            + "' 处疑似漏了逗号");
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "SELECT 列之间漏了逗号，SQL 会在运行期直接语法错误：\n  "
                        + String.join("\n  ", problems));
    }

    // ---------------------------------------------------------------- SQL 里不应有非 ASCII

    @Test
    @DisplayName("Mapper 的 SQL 里不出现非 ASCII（中文注释混进 SQL 会导致语法错误）")
    void sqlIsAsciiOnly() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path file : mapperFiles()) {
            for (QueryBlock block : parse(file)) {
                for (char c : block.sql().toCharArray()) {
                    if (c > 127) {
                        problems.add(block.mapper() + "#" + block.method()
                                + " 的 SQL 里出现了非 ASCII 字符 '" + c + "'");
                        break;
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n  ", problems));
    }

    @Test
    @DisplayName("自检：解析器真的能数对（否则上面的断言会空转通过）")
    void parserSelfCheck() throws Exception {
        // 最关键的一条：r.friend_id 里含 "from"，解析必须不被它截断
        assertEquals(9, countSelectedColumns(
                        "SELECT r.friend_id AS user_id,\n"
                                + "       u.username    AS username,\n"
                                + "       u.nickname    AS nickname,\n"
                                + "       u.real_name   AS real_name,\n"
                                + "       u.avatar_key  AS avatar_key,\n"
                                + "       u.role        AS role,\n"
                                + "       u.signature   AS signature,\n"
                                + "       u.class_name  AS class_name,\n"
                                + "       u.status      AS status\n"
                                + "FROM friend_relation r"),
                "列数被 friend_id 里的 'from' 截断了");

        assertEquals(3, countSelectedColumns("SELECT a AS x, b AS y, c AS z FROM t"));
        assertEquals(1, countSelectedColumns("SELECT a AS x FROM t"));
        assertEquals(2, countSelectedColumns("SELECT CONCAT(a, b) AS ab, c AS y FROM t"));

        Path mapper = MAPPER_DIR.resolve("FriendRelationMapper.java");
        assertEquals(9, countRecordComponents(mapper, "FriendUserRow"),
                "FriendUserRow 应为 9 个组件（userId/username/nickname/realName/avatarKey/"
                        + "role/signature/className/status）");
        assertEquals(10, countRecordComponents(mapper, "FriendRequestRow"),
                "FriendRequestRow 应为 10 个组件（比 FriendUserRow 多 requestedAt）");
    }
}
