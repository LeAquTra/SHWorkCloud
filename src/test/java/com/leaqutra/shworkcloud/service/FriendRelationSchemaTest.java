package com.leaqutra.shworkcloud.service;

import com.baomidou.mybatisplus.annotation.TableLogic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 好友关系表的「唯一键 ↔ 删除方式」契约回归守卫。
 * <p>
 * <b>背景（用户报的真实故障）</b>：点"加好友"提示"发送申请失败"（HTTP 500）。
 * <p>
 * 根因是<b>逻辑删除与唯一索引天生冲突</b>：{@code friend_relation} 上有
 * {@code uk_edge(user_id, friend_id)}，而"拒绝申请 / 删除好友"如果只是
 * {@code UPDATE deleted = 1}，那一行<b>仍然占着唯一键</b>。于是：
 * <pre>
 *   A 申请 B      → INSERT (A,B) 成功
 *   B 拒绝        → deleted = 1（行还在表里）
 *   A 再申请 B    → INSERT (A,B) → Duplicate entry '1-2' for key 'uk_edge' → 500
 * </pre>
 * MySQL 没有条件唯一键（partial index），所以"软删除 + 唯一键"这个组合在 MySQL 上
 * 必然踩坑。本项目在 {@code file_entry} 上早已用<b>生成列 active_name</b> 解决过
 * 同一类问题（见 init.sql 的注释）；好友边则用更直接的办法：<b>物理删除</b>。
 * <p>
 * <b>为什么不在这里跑真 SQL 验证：</b>最理想的做法是把 DDL 拉到内存库里真插一遍，
 * 但本机离线、本地仓库没有 H2（也没有任何嵌入式数据库），引不进来。
 * 所以这里退一步守<b>造成故障的两个可静态检查的前置条件</b>：
 * <ol>
 *   <li>DDL 里 {@code friend_relation} 不再有 {@code deleted} 列；</li>
 *   <li>{@link com.leaqutra.shworkcloud.entity.FriendRelation} 不再有
 *       {@code @TableLogic}，Mapper 也不再过滤该列。</li>
 * </ol>
 * 这两条一旦被破坏，运行时就会重新出现"拒绝后再申请撞唯一键"。
 * 也就是说：<b>本测试证明的是"故障前提不成立"，而不是"跑通了 SQL"。</b>
 * 这个区别很重要，所以写在这里而不是含糊带过。
 */
class FriendRelationSchemaTest {

    private static final String TABLE = "friend_relation";

    private static String readSql(String file) throws IOException {
        Path path = Path.of("src", "main", "resources", "init_sql", file);
        assertTrue(Files.exists(path), "建表脚本不存在: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String readSource(String relative) throws IOException {
        Path path = Path.of("src", "main", "java", "com", "leaqutra", "shworkcloud")
                .resolve(relative);
        assertTrue(Files.exists(path), "源文件不存在: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 抠出 {@code friend_relation} 的<b>建表体</b>（不含后续语句）。
     * <p>用"下一个 {@code CREATE TABLE} / 文件结束"作为右边界，
     * 避免把后面别的表的列也算进来。
     */
    private static String createTableBody(String sql) {
        Matcher start = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS `" + TABLE + "` \\(").matcher(sql);
        if (!start.find()) {
            return null;
        }
        int from = start.end();
        Matcher next = Pattern.compile("CREATE TABLE IF NOT EXISTS").matcher(sql);
        int to = next.find(from) ? next.start() : sql.length();
        return sql.substring(from, to);
    }

    // ---------------------------------------------------------------- 前置条件一：没有 deleted 列

    @Test
    @DisplayName("migration_v2.3.sql 的 friend_relation 不再有 deleted 列（软删除会占住 uk_edge）")
    void migrationHasNoDeletedColumn() throws Exception {
        String body = createTableBody(readSql("migration_v2.3.sql"));
        assertTrue(body != null, "migration_v2.3.sql 里找不到 friend_relation 的建表语句");
        assertTrue(!body.contains("`deleted`"),
                "friend_relation 仍有 deleted 列。它与 uk_edge 的组合会让"
                        + "「拒绝后再申请 / 删好友后再加」必然撞 Duplicate entry 报 500。"
                        + "好友边一律物理删除，不需要 deleted");
    }

    @Test
    @DisplayName("init.sql 的 friend_relation 同样不再有 deleted 列（两处必须一致）")
    void initSqlHasNoDeletedColumn() throws Exception {
        String body = createTableBody(readSql("init.sql"));
        assertTrue(body != null, "init.sql 里找不到 friend_relation 的建表语句");
        assertTrue(!body.contains("`deleted`"),
                "init.sql 与 migration 的 friend_relation 结构必须一致，否则"
                        + "「全新部署」和「增量升级」会得到两张不同的表");
    }

    @Test
    @DisplayName("uk_edge 唯一键仍在（防重复申请的根据，不能顺手删掉）")
    void uniqueKeyStillPresent() throws Exception {
        for (String file : List.of("migration_v2.3.sql", "init.sql")) {
            String body = createTableBody(readSql(file));
            assertTrue(body != null && body.contains("UNIQUE KEY `uk_edge` (`user_id`, `friend_id`)"),
                    file + " 里的 uk_edge 不见了。它是「一个人不能对同一个人存在两条边」的"
                            + "唯一保证，也是「接受申请」那条 UPDATE 幂等的根据");
        }
    }

    // ---------------------------------------------------------------- 前置条件二：实体与 Mapper 不带逻辑删除

    @Test
    @DisplayName("FriendRelation 不能有 @TableLogic：否则 deleteById 退化成软删除，故障复现")
    void entityMustNotUseLogicalDelete() {
        List<String> offending = new ArrayList<>();
        for (Field field : com.leaqutra.shworkcloud.entity.FriendRelation.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(TableLogic.class)) {
                offending.add(field.getName());
            }
        }
        assertTrue(offending.isEmpty(),
                "FriendRelation 上出现了 @TableLogic（字段：" + offending + "）。"
                        + "friend_relation 有唯一键 uk_edge，软删除的行会继续占着它 —— "
                        + "这正是「加好友失败」的根因。删除好友请用物理删除");
    }

    @Test
    @DisplayName("Mapper 里不应再过滤 deleted（该列已从表里移除，过滤会报 Unknown column）")
    void mapperHasNoDeletedCondition() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String line : readSource("mapper/FriendRelationMapper.java").split("\n")) {
            String trimmed = line.strip();
            // 只看代码行：注释里必然出现 deleted（解释为什么不用它）
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            // 只针对 friend_relation 自身的别名/裸列；u.deleted 是 sys_user 的，合法
            if (trimmed.matches(".*\\b(deleted|r\\.deleted)\\s*=\\s*0.*")
                    && !trimmed.contains("u.deleted")) {
                offenders.add(trimmed);
            }
        }
        assertTrue(offenders.isEmpty(),
                "FriendRelationMapper 仍在过滤 friend_relation.deleted，而该列已被移除，"
                        + "执行会报 Unknown column 'deleted'：" + offenders);
    }

    @Test
    @DisplayName("联表查询必须保留 u.deleted，且 r.deleted 不能再出现")
    void joinQueriesFilterOnlyUserTable() throws Exception {
        String mapper = readSource("mapper/FriendRelationMapper.java");
        assertTrue(mapper.contains("u.deleted = 0"),
                "联表查询丢了 u.deleted = 0：已注销用户会重新出现在好友列表里");
        assertTrue(!mapper.contains("r.deleted"),
                "联表查询仍在用 r.deleted（friend_relation 已无该列）");
    }
}
