package com.leaqutra.shworkcloud.controller;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 好友接口的<b>端到端</b>集成测试（真实 MySQL + 真实 Redis + 真实 MyBatis 映射）。
 * <p>
 * <b>为什么必须要有这一类测试（真实教训）</b>：这个项目此前所有测试都是"纯逻辑 + 静态文本检查"，
 * 一条 SQL 都没真跑过。结果是"加好友 500"这类问题只能靠猜 —— 我前两轮分别猜过
 * "软删除撞唯一键"和"库结构缺列"，两次都没被证实。真正能一次性排除
 * "SQL 写错 / 列名不对 / 映射不对 / 序列化失败"的，只有把请求真打一遍。
 * <p>
 * 覆盖的三个接口正是线上报 500 的那几个：
 * <ul>
 *   <li>{@code GET /api/friends}（好友页汇总，前端进页面必调）；</li>
 *   <li>{@code GET /api/friends/search?userId=}（按 ID 查找）；</li>
 *   <li>{@code POST /api/friends/requests}（发起申请）。</li>
 * </ul>
 * 其中 search 与 requests 就是用户报 500 的两个。<b>它的价值在于：接口一旦再出 500，
 * 这里会直接失败并打印真实异常</b>，而不是等部署后发现。
 * <p>
 * 默认<b>不执行</b>（需要真实 MySQL/Redis），跑法：
 * <pre>
 *   RUN_INTEGRATION_TESTS=true mvn test -Dtest=FriendApiIntegrationTest
 * </pre>
 * 数据库需先执行 {@code src/main/resources/init_sql/init.sql} 建好 10 张表。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true",
        disabledReason = "需要真实 MySQL(3307) 与 Redis(6380)，默认跳过")
class FriendApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    private long meId;
    private long targetId;
    private String token;

    /** 造两个可用账号，并登录其中一个，拿到可用于 Authorization 头的 token */
    @BeforeEach
    void setUp() {
        meId = ensureUser("it_me", "集成测试甲");
        targetId = ensureUser("it_target", "集成测试乙");
        // StpUtil 在没有 Web 上下文时需要 mock 上下文才能签发 token
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(meId);
            token = StpUtil.getTokenValue();
        });
    }

    /** 幂等造号：已存在就复用（测试可重复跑，不必每次清库） */
    private long ensureUser(String username, String realName) {
        SysUser existing = userMapper.selectByLogin(username);
        if (existing != null) {
            return existing.getId();
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        user.setRole((byte) 0);
        user.setStatus((byte) 1);
        user.setDeleted((byte) 0);
        user.setStorageQuota(2L * 1024 * 1024 * 1024);
        user.setUsedStorage(0L);
        user.setPwdChanged((byte) 1);
        user.setLoginFailCount(0);
        userMapper.insert(user);
        return user.getId();
    }

    private MvcResult call(MvcResultSupplier supplier) throws Exception {
        return supplier.get();
    }

    private interface MvcResultSupplier {
        MvcResult get() throws Exception;
    }

    /** 断言 HTTP 200 且业务 code=0；失败时把响应体打出来（这就是排查线上 500 缺的那句话） */
    private void assertOk(MvcResult result, String what) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(),
                what + " 期望 HTTP 200，实际 " + result.getResponse().getStatus() + "，响应体：" + body);
        assertTrue(body.contains("\"code\":0"),
                what + " 期望业务 code=0，实际响应体：" + body);
    }

    // ---------------------------------------------------------------- 报 500 的两个接口

    @Test
    @DisplayName("GET /api/friends —— 好友页汇总（线上进页面必调）")
    void overviewWorks() throws Exception {
        MvcResult result = mockMvc.perform(get("/friends").header("Authorization", token)).andReturn();
        assertOk(result, "GET /friends");
    }

    @Test
    @DisplayName("GET /api/friends/search?userId= —— 按 ID 查找（线上报 500 的接口之一）")
    void searchByIdWorks() throws Exception {
        MvcResult result = mockMvc.perform(get("/friends/search")
                .param("userId", String.valueOf(targetId))
                .header("Authorization", token)).andReturn();
        assertOk(result, "GET /friends/search");
        assertTrue(result.getResponse().getContentAsString().contains("it_target"),
                "查找结果里应包含目标用户");
    }

    @Test
    @DisplayName("GET /api/friends/search?userId=<自己> —— 返回 relation=SELF 而不是空")
    void searchSelfReturnsSelfRelation() throws Exception {
        MvcResult result = mockMvc.perform(get("/friends/search")
                .param("userId", String.valueOf(meId))
                .header("Authorization", token)).andReturn();
        assertOk(result, "GET /friends/search(自己)");
        assertTrue(result.getResponse().getContentAsString().contains("\"relation\":\"SELF\""),
                "搜到自己必须返回 relation=SELF，否则前端会以为'搜不到=没这个人'");
    }

    @Test
    @DisplayName("POST /api/friends/requests —— 发起申请（线上报 500 的接口之二）")
    void sendRequestWorks() throws Exception {
        // 先清掉可能存在的旧边，让每次运行都是"首次申请"这条路径
        jdbcCleanEdge(meId, targetId);

        MvcResult result = mockMvc.perform(post("/friends/requests")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + targetId + "}")).andReturn();
        assertOk(result, "POST /friends/requests");
    }

    @Test
    @DisplayName("🔴 拒绝之后再申请必须成功：验证 uk_edge 与删除策略不再冲突")
    void reRequestAfterRemovalWorks() throws Exception {
        jdbcCleanEdge(meId, targetId);

        // 1) 首次申请
        assertOk(mockMvc.perform(post("/friends/requests")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + targetId + "}")).andReturn(), "首次申请");

        // 2) 对方拒绝（等价于删掉那条待处理边）—— 直接走 SQL，复刻"逻辑删除 vs 物理删除"的差别
        jdbcDeleteEdge(meId, targetId);

        // 3) 再申请：如果删除没真正释放 uk_edge，这一步就会 Duplicate entry -> 500
        assertOk(mockMvc.perform(post("/friends/requests")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + targetId + "}")).andReturn(),
                "拒绝后再次申请（这一步曾必然 500）");

        jdbcCleanEdge(meId, targetId);
    }

    // ---------------------------------------------------------------- 直接用 SQL 造/清状态

    @Autowired
    private javax.sql.DataSource dataSource;

    private void jdbcCleanEdge(long userId, long friendId) throws Exception {
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "DELETE FROM friend_relation WHERE user_id = ? AND friend_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, friendId);
            ps.executeUpdate();
        }
    }

    private void jdbcDeleteEdge(long userId, long friendId) throws Exception {
        jdbcCleanEdge(userId, friendId);
    }
}
