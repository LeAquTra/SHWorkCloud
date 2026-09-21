package com.leaqutra.shworkcloud.controller;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.leaqutra.shworkcloud.entity.Post;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.PostMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 社区审核队列的端到端集成测试（真实 MySQL + Redis）。
 * <p>
 * <b>背景（用户报的真实故障）</b>：后台「社区审核」点刷新提示"加载失败"。
 * <p>
 * 覆盖 {@code GET /api/admin/posts} 的三种筛选（待审 / 已通过 / 全部），
 * 因为 <b>不同 status 走的是不同的 SQL 分支</b>（{@code selectForReview} 用了
 * XML {@code <script>} 动态拼 {@code <if>}）—— 只测一种筛选会漏掉另一种。
 * <p>
 * 同时也守 {@code /admin/posts/pending-count}：导航红点、以及新增的
 * "有人发帖就刷新提示"都依赖它。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true",
        disabledReason = "需要真实 MySQL(3307) 与 Redis(6380)，默认跳过")
class AdminPostApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PostMapper postMapper;

    private long authorId;
    private String studentToken;
    /** 管理员（role=1）—— 需求里的"管理员以上"，注意它不等于超管 */
    private long adminId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        authorId = ensureUser("it_author", "发帖同学", (byte) 0);
        adminId = ensureUser("it_admin", "审核管理员", (byte) 1);

        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(authorId);
            studentToken = StpUtil.getTokenValue();
        });
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(adminId);
            adminToken = StpUtil.getTokenValue();
        });
    }

    /**
     * 幂等造号，可重复运行。
     * <p>
     * ⚠️ 必须考虑<b>逻辑删除</b>：{@code selectByLogin} 带 {@code deleted = 0}，
     * 所以被"脏数据"用例注销过的账号它查不到；此时若直接 insert，
     * 会撞 {@code uk_username} 唯一键（这个唯一键<b>包含已注销的行</b>）。
     * 测试因此必须能把已注销的账号"复活"，否则第二次跑就红。
     */
    private long ensureUser(String username, String realName, byte role) {
        SysUser existing = userMapper.selectByLogin(username);
        if (existing != null) {
            userMapper.updateRole(existing.getId(), role);
            return existing.getId();
        }
        Long resurrectedId = findIdIncludingDeleted(username);
        if (resurrectedId != null) {
            resurrect(resurrectedId, role);
            return resurrectedId;
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        user.setRole(role);
        user.setStatus((byte) 1);
        user.setDeleted((byte) 0);
        user.setStorageQuota(2L * 1024 * 1024 * 1024);
        user.setUsedStorage(0L);
        user.setPwdChanged((byte) 1);
        user.setLoginFailCount(0);
        userMapper.insert(user);
        return user.getId();
    }

    /** 绕过 @TableLogic 找已注销的账号（唯一键把已删行也算在内） */
    private Long findIdIncludingDeleted(String username) {
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement("SELECT id FROM sys_user WHERE username = ?")) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询账号失败: " + username, e);
        }
    }

    private void resurrect(long userId, byte role) {
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "UPDATE sys_user SET deleted = 0, status = 1, role = ? WHERE id = ?")) {
            ps.setByte(1, role);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("复活账号失败: " + userId, e);
        }
    }

    private void assertOk(MvcResult result, String what) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(),
                what + " 期望 HTTP 200，实际 " + result.getResponse().getStatus()
                        + "，响应体：" + body);
        assertTrue(body.contains("\"code\":0"), what + " 期望业务 code=0，实际：" + body);
    }

    // ---------------------------------------------------------------- 报"加载失败"的接口

    @Test
    @DisplayName("🔴 GET /admin/posts?status=0 —— 待审核队列（刷新按钮默认打的这个）")
    void reviewQueuePendingWorks() throws Exception {
        ensurePendingPost();
        MvcResult result = mockMvc.perform(get("/admin/posts")
                .param("status", "0").param("page", "1").param("size", "20")
                .header("Authorization", adminToken)).andReturn();
        assertOk(result, "GET /admin/posts?status=0");
    }

    @Test
    @DisplayName("🔴 不传 status（前端「全部」页签）—— 这条 SQL 分支只在此时才走到")
    void reviewQueueWithoutStatusWorks() throws Exception {
        // 前端"全部"页签传的是 undefined，服务端 status=null 时不拼 <if> 条件。
        // 这是与 status=0 完全不同的一条 SQL，必须单独覆盖。
        MvcResult result = mockMvc.perform(get("/admin/posts")
                .param("page", "1").param("size", "20")
                .header("Authorization", adminToken)).andReturn();
        assertOk(result, "GET /admin/posts（不传 status）");
    }

    @Test
    @DisplayName("GET /admin/posts?status=1 / status=2 —— 已通过 / 已拒绝两条分支")
    void reviewQueueOtherStatusesWork() throws Exception {
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "1")
                .header("Authorization", adminToken)).andReturn(), "status=1");
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "2")
                .header("Authorization", adminToken)).andReturn(), "status=2");
    }

    @Test
    @DisplayName("GET /admin/posts/pending-count —— 导航红点与新帖提示都依赖它")
    void pendingCountWorks() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/posts/pending-count")
                .header("Authorization", adminToken)).andReturn();
        assertOk(result, "GET /admin/posts/pending-count");
    }

    @Test
    @DisplayName("教师(role=2) 也必须能进审核队列 —— 需求是「管理员以上」含教师")
    void teacherCanOpenReviewQueue() throws Exception {
        long teacherId = ensureUser("it_teacher", "审核教师", (byte) 2);
        String[] tokenHolder = new String[1];
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(teacherId);
            tokenHolder[0] = StpUtil.getTokenValue();
        });
        MvcResult result = mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", tokenHolder[0])).andReturn();
        assertOk(result, "教师 GET /admin/posts");
    }

    // ---------------------------------------------------------------- 发帖 → 出现在待审队列

    @Test
    @DisplayName("学生发帖后，该帖出现在审核队列里（待审数 +1）")
    void newPostShowsUpInQueue() throws Exception {
        long before = postMapper.countPending();

        MvcResult created = mockMvc.perform(post("/community/posts")
                .header("Authorization", studentToken)
                .contentType("application/json")
                .content("{\"content\":\"集成测试帖子 https://example.com/a\"}")).andReturn();
        assertOk(created, "POST /community/posts");

        assertEquals(before + 1, postMapper.countPending(),
                "发帖之后待审核数应当 +1 —— 导航红点就靠这个数字");

        MvcResult queue = mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", adminToken)).andReturn();
        assertOk(queue, "发帖后的审核队列");
        assertTrue(queue.getResponse().getContentAsString().contains("集成测试帖子"),
                "刚提交的帖子应当出现在待审核队列里");
    }

    // ---------------------------------------------------------------- 审核全流程

    @Test
    @DisplayName("审核全流程：待审 → 通过 → 拒绝，每一步之后队列都能刷新")
    void fullReviewLifecycleKeepsQueueUsable() throws Exception {
        long postId = insertPost(Post.STATUS_PENDING, authorId, "流程测试帖");

        // 待审队列
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", adminToken)).andReturn(), "通过前的待审队列");

        // 通过
        assertOk(mockMvc.perform(post("/admin/posts/review")
                .header("Authorization", adminToken)
                .contentType("application/json")
                .content("{\"postId\":" + postId + ",\"approve\":true}")).andReturn(), "通过");

        // 通过之后：队列里应当能看到它（status=1），且 rejectReason 为 null
        MvcResult approved = mockMvc.perform(get("/admin/posts").param("status", "1")
                .header("Authorization", adminToken)).andReturn();
        assertOk(approved, "通过后的审核队列");
        assertTrue(approved.getResponse().getContentAsString().contains("流程测试帖"));

        // 再建一条用来拒绝，并带上理由
        long rejectId = insertPost(Post.STATUS_PENDING, authorId, "待拒绝帖");
        assertOk(mockMvc.perform(post("/admin/posts/review")
                .header("Authorization", adminToken)
                .contentType("application/json")
                .content("{\"postId\":" + rejectId
                        + ",\"approve\":false,\"rejectReason\":\"不合适\"}")).andReturn(), "拒绝");

        MvcResult rejected = mockMvc.perform(get("/admin/posts").param("status", "2")
                .header("Authorization", adminToken)).andReturn();
        assertOk(rejected, "拒绝后的审核队列");
        assertTrue(rejected.getResponse().getContentAsString().contains("不合适"),
                "拒绝理由应当出现在队列里");
    }

    @Test
    @DisplayName("响应结构必须能被前端解构：records / total / pendingTotal 三个键都在")
    void queueResponseShapeMatchesFrontend() throws Exception {
        insertPost(Post.STATUS_PENDING, authorId, "结构检查帖");
        MvcResult result = mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", adminToken)).andReturn();
        assertOk(result, "队列结构检查");

        String body = result.getResponse().getContentAsString();
        // 前端读的是 data.records / data.total / data.pendingTotal（见 PostReviewView.reload）
        for (String key : new String[]{"\"records\":", "\"total\":", "\"pendingTotal\":",
                "\"page\":", "\"size\":"}) {
            assertTrue(body.contains(key),
                    "响应里缺少 " + key + " —— 前端解构会拿到 undefined。实际响应：" + body);
        }
    }

    @Test
    @DisplayName("帖子对象带 canEdit/canDelete/linkCount/segments，前端按钮与链接渲染都依赖它们")
    void postShapeCarriesFrontendFields() throws Exception {
        insertPost(Post.STATUS_PENDING, authorId, "字段检查 https://example.com/x");
        MvcResult result = mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", adminToken)).andReturn();
        assertOk(result, "帖子字段检查");

        String body = result.getResponse().getContentAsString();
        for (String key : new String[]{"\"canEdit\":", "\"canDelete\":", "\"linkCount\":",
                "\"segments\":", "\"author\":", "\"status\":"}) {
            assertTrue(body.contains(key),
                    "帖子对象缺少 " + key + "。实际响应：" + body);
        }
    }

    @Test
    @DisplayName("脏数据也要能刷新出来：作者已注销 / 审核人为空 / 超长正文 / 大量链接")
    void queueSurvivesDirtyData() throws Exception {
        // ① 作者被逻辑删除（帖子还在）——帖子会因 JOIN 条件被过滤掉，但不能让整个查询报错
        long doomed = ensureUser("it_doomed", "将被注销", (byte) 0);
        insertPost(Post.STATUS_PENDING, doomed, "作者已注销的帖子");
        userMapper.deleteById(doomed);

        // ② 审核人字段为空（历史数据 / 手工插的数据）
        long nullReviewer = insertPost(Post.STATUS_PENDING, authorId, "审核人为空的帖子");
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "UPDATE post SET status = 1, reviewed_by = NULL, review_time = NULL "
                             + "WHERE id = ?")) {
            ps.setLong(1, nullReviewer);
            ps.executeUpdate();
        }

        // ③ 超长正文（接近上限 2000 字）
        insertPost(Post.STATUS_PENDING, authorId, "长".repeat(1999));

        // ④ 正文里塞满链接
        insertPost(Post.STATUS_PENDING, authorId,
                "链 https://a.com https://b.com https://c.com https://d.com 接");

        assertOk(mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", adminToken)).andReturn(), "脏数据(status=0)");
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "1")
                .header("Authorization", adminToken)).andReturn(), "脏数据(status=1)");
        assertOk(mockMvc.perform(get("/admin/posts")
                .header("Authorization", adminToken)).andReturn(), "脏数据(全部)");
        assertOk(mockMvc.perform(get("/admin/posts/pending-count")
                .header("Authorization", adminToken)).andReturn(), "脏数据(待审数)");
    }

    @Test
    @DisplayName("分页边界：page=0 / page 超出范围 / size 超大 都不能报错")
    void queueSurvivesPagingEdgeCases() throws Exception {
        insertPost(Post.STATUS_PENDING, authorId, "分页边界帖");

        // 前端不会传这些，但接口被人手敲、或将来前端出 bug 时不能变成 500
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "0").param("page", "0")
                .header("Authorization", adminToken)).andReturn(), "page=0");
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "0").param("page", "99999")
                .header("Authorization", adminToken)).andReturn(), "page 超出范围");
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "0").param("size", "999999")
                .header("Authorization", adminToken)).andReturn(), "size 超大");
    }

    @Test
    @DisplayName("学生访问审核队列必须被挡（403 或 40301），不能是 500")
    void studentCannotOpenReviewQueue() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", studentToken)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertTrue(status == 403 || body.contains("40301") || body.contains("40300"),
                "学生访问审核队列应当是 403 或 40300/40301，实际 status=" + status
                        + " body=" + body);
    }

    @Test
    @DisplayName("超管(9) 也必须能进审核队列（三种角色都要覆盖）")
    void superAdminCanOpenReviewQueue() throws Exception {
        long superId = ensureUser("it_super", "审核超管", (byte) 9);
        String[] holder = new String[1];
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(superId);
            holder[0] = StpUtil.getTokenValue();
        });
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "0")
                .header("Authorization", holder[0])).andReturn(), "超管 GET /admin/posts?status=0");
        // 三个页签 + 红点接口，超管都要能用
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "1")
                .header("Authorization", holder[0])).andReturn(), "超管 status=1");
        assertOk(mockMvc.perform(get("/admin/posts").param("status", "2")
                .header("Authorization", holder[0])).andReturn(), "超管 status=2");
        assertOk(mockMvc.perform(get("/admin/posts")
                .header("Authorization", holder[0])).andReturn(), "超管 全部");
        assertOk(mockMvc.perform(get("/admin/posts/pending-count")
                .header("Authorization", holder[0])).andReturn(), "超管 pending-count");
    }

    // ---------------------------------------------------------------- 辅助

    @Autowired
    private javax.sql.DataSource dataSource;

    private long insertPost(int status, long author, String content) {
        Post post = new Post();
        post.setAuthorId(author);
        post.setContent(content);
        post.setLinkCount(0);
        post.setStatus(status);
        postMapper.insert(post);
        return post.getId();
    }

    private void ensurePendingPost() {
        insertPost(Post.STATUS_PENDING, authorId, "待审核集成测试帖");
    }
}
