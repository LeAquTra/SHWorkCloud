package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.dto.CommunityDto;
import com.leaqutra.shworkcloud.entity.Post;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.PostMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.RateLimiter;
import com.leaqutra.shworkcloud.vo.CommunityVo;
import com.leaqutra.shworkcloud.vo.FriendVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 社区：发帖、看帖、审核。
 * <p>
 * <b>三条不能松的规则：</b>
 * <ol>
 *   <li><b>发帖一律落为待审核</b>，用户永远拿不到"直接可见"的路径；</li>
 *   <li><b>已通过的帖子被作者编辑后回到待审核</b>。否则作者可以先用正常内容过审、
 *       再把正文换成任何东西 —— 审核就成了摆设。代价是编辑后帖子会从时间线
 *       暂时消失，这是刻意的：宁可让作者觉得"改完要等一会儿"，
 *       也不要让审核员以为自己批过的内容还在原样挂着；</li>
 *   <li><b>可见性按状态分流</b>：已通过 → 所有人；待审 / 已拒绝 → 只有作者与
 *       <b>有审核权限的人</b>（管理员及以上）。审核员必须能看到待审内容，
 *       否则没法审；而不该把别人的待审内容泄露给普通同学。</li>
 * </ol>
 * <p>
 * "有审核权限"的判定用 {@link #isReviewer()}，口径是 <b>role ∈ {1 管理员, 2 教师,
 * 9 超管}</b>。这里<b>绝不能用 {@code role >= 1} 这种数值比较</b>：本项目角色编号
 * 不是有序等级（0 学生 / 1 管理员 / 2 教师 / 9 超管），教师(2) 数值比管理员(1) 大
 * 却权限更小，数值比较必然错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostMapper postMapper;
    private final UserCardAssembler cardAssembler;
    private final LoginUser loginUser;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;

    // ---------------------------------------------------------------- 读取

    /**
     * 公开时间线：只含已通过的帖子。
     *
     * @param query 游标查询（见 {@link CommunityDto.FeedQuery}）
     */
    public CommunityVo.Feed feed(CommunityDto.FeedQuery query) {
        long me = loginUser.id();
        int size = PostRules.normalizeFeedSize(query == null ? null : query.size());
        boolean mine = query != null && Boolean.TRUE.equals(query.mine());
        long beforeId = query == null || query.beforeId() == null ? Long.MAX_VALUE : query.beforeId();

        List<PostMapper.PostRow> rows;
        if (mine) {
            rows = postMapper.selectMine(me, beforeId, size + 1);
        } else {
            Long authorId = query == null ? null : query.authorId();
            rows = postMapper.selectFeed(beforeId, authorId, size + 1);
        }

        // 多取一条用来判断"还有没有更早的"，返回前裁掉
        boolean hasMore = rows.size() > size;
        List<PostMapper.PostRow> page = hasMore ? rows.subList(0, size) : rows;

        List<CommunityVo.Post> posts = new ArrayList<>(page.size());
        for (PostMapper.PostRow row : page) {
            posts.add(toVo(row, me));
        }
        Long nextBeforeId = page.isEmpty() ? null : page.get(page.size() - 1).postId();
        return new CommunityVo.Feed(posts, hasMore ? nextBeforeId : null, hasMore);
    }

    /** 我的帖子概览：三个状态各多少（"我的"页签角标） */
    public CommunityVo.MySummary mySummary() {
        long me = loginUser.id();
        long approved = postMapper.countApprovedByAuthor(me);
        long pending = countMine(me, Post.STATUS_PENDING);
        long rejected = countMine(me, Post.STATUS_REJECTED);
        return new CommunityVo.MySummary(pending, approved, rejected);
    }

    /** 单条详情。可见性规则见类注释 */
    public CommunityVo.Post detail(Long id) {
        return toVo(mustVisible(id), loginUser.id());
    }

    // ---------------------------------------------------------------- 写

    /**
     * 发帖。**一律落为待审核**，返回值里 {@code status=0}，前端据此提示"已提交"。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVo.ActionResult create(CommunityDto.PostReq req, String clientIp) {
        long me = loginUser.id();
        String content = PostRules.normalizeContent(req == null ? null : req.content());
        // 限流放在内容校验之后：内容都不合法就没必要消耗用户的发帖额度
        rateLimiter.checkPostCreate(me);

        LinkSegmenter.Result parsed = LinkSegmenter.split(content);

        Post post = new Post();
        post.setAuthorId(me);
        post.setContent(content);
        post.setLinkCount(parsed.linkCount());
        post.setStatus(Post.STATUS_PENDING);
        postMapper.insert(post);

        auditService.log(me, "POST_CREATE", "POST", String.valueOf(post.getId()), clientIp, true,
                "chars=%d,links=%d".formatted(content.length(), parsed.linkCount()));
        return new CommunityVo.ActionResult(toVo(post, me), "已提交，等待管理员审核");
    }

    /**
     * 编辑自己的帖子。
     * <p><b>编辑后一定回到待审核</b>（见类注释第 2 条），所以：
     * <ul>
     *   <li>已通过 → 重新排队（会从时间线暂时消失）；</li>
     *   <li>已拒绝 → 回到待审核（给作者一次改过自新的机会）；</li>
     *   <li>待审核 → <b>不允许再改</b>：它已经在队列里了，
     *       允许改会让审核员读到一份与他即将批准的不同的内容。</li>
     * </ul>
     * 编辑时旧的审核结论（reviewed_by / review_time / reject_reason）一并清空 ——
     * 留着上一次的拒绝理由会让作者以为这次也被拒了。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVo.ActionResult update(Long id, CommunityDto.PostReq req, String clientIp) {
        long me = loginUser.id();
        Post post = mustExist(id);
        requireAuthor(post, me);
        if (post.isPending()) {
            throw new BizException(ErrorCode.POST_STATE_INVALID,
                    "帖子正在审核中，不能修改；请等待审核结果");
        }
        String content = PostRules.normalizeContent(req == null ? null : req.content());
        rateLimiter.checkPostCreate(me);

        LinkSegmenter.Result parsed = LinkSegmenter.split(content);
        post.setContent(content);
        post.setLinkCount(parsed.linkCount());
        post.setStatus(Post.STATUS_PENDING);
        post.setReviewedBy(null);
        post.setReviewTime(null);
        post.setRejectReason(null);
        postMapper.updateById(post);

        auditService.log(me, "POST_UPDATE", "POST", String.valueOf(id), clientIp, true,
                "chars=%d,links=%d".formatted(content.length(), parsed.linkCount()));
        return new CommunityVo.ActionResult(toVo(post, me), "已提交，等待管理员审核");
    }

    /**
     * 删除自己的帖子。
     * <p>物理删除（与公告同取舍：表长不大，"拒绝"已经是软状态，
     * 再叠一层 deleted 会让所有查询都要多带条件）。
     * <p>待审核的帖子不允许删除：它正在被看，删掉会让审核员点开时报"不存在"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String clientIp) {
        long me = loginUser.id();
        Post post = mustExist(id);
        requireAuthor(post, me);
        if (post.isPending()) {
            throw new BizException(ErrorCode.POST_STATE_INVALID,
                    "帖子正在审核中，不能删除；请等待审核结果");
        }
        postMapper.deleteById(id);
        auditService.log(me, "POST_DELETE", "POST", String.valueOf(id), clientIp, true, null);
    }

    // ---------------------------------------------------------------- 审核 / 管理

    /**
     * 后台帖子列表（审核队列 + 社区管理台共用同一份查询）。
     * <p>定位条件（单条 ID / 一批 ID / 作者 / 正文关键字）全部走同一个 Mapper 方法，
     * 列表与总数共用同一份条件 —— 否则会出现"总数说有 5 条，翻过去一条都没有"。
     * <p>两个页面读的是同一份数据，只是要的东西不同：审核页只要队列，
     * 管理台还要 {@code statusCounts}（全局分布）。所以这里返回的是管理台的形状，
     * 审核页忽略多余字段即可 —— 契约见 {@link CommunityVo.AdminPostPage} 的注释。
     */
    public CommunityVo.AdminPostPage reviewPage(CommunityDto.ReviewQuery query) {
        requireReviewer();
        Integer status = PostRules.normalizeStatus(query == null ? null : query.status());
        long page = query == null || query.page() == null || query.page() < 1 ? 1 : query.page();
        long size = PostRules.normalizeReviewSize(query == null ? null : query.size());
        long offset = (page - 1) * size;

        Long postId = query == null ? null : query.postId();
        Long authorId = query == null ? null : query.authorId();
        List<Long> ids = normalizeIdFilter(query == null ? null : query.ids());
        String keyword = PostRules.normalizeKeyword(query == null ? null : query.keyword());

        long total = postMapper.countForReview(status, postId, ids, authorId, keyword);
        List<PostMapper.PostRow> rows = total == 0
                ? List.of()
                : postMapper.selectForReview(status, postId, ids, authorId, keyword, offset, size);

        long me = loginUser.id();
        List<CommunityVo.Post> records = new ArrayList<>(rows.size());
        for (PostMapper.PostRow row : rows) {
            records.add(toVo(row, me));
        }
        return new CommunityVo.AdminPostPage(records, total, page, size,
                postMapper.countPending(), countsOfAllStatus());
    }

    /**
     * 社区内容的全局状态分布（社区管理台顶部的统计）。
     * <p>三个数字与列表当前的筛选条件<b>无关</b>：管理员要看的是"站上现在有多少条
     * 已通过的内容"，而不是"我这次筛出来多少条"（后者看 {@code total}）。
     */
    public CommunityVo.StatusCounts statusCounts() {
        requireReviewer();
        return countsOfAllStatus();
    }

    private CommunityVo.StatusCounts countsOfAllStatus() {
        return new CommunityVo.StatusCounts(
                postMapper.countByStatus(Post.STATUS_PENDING),
                postMapper.countByStatus(Post.STATUS_APPROVED),
                postMapper.countByStatus(Post.STATUS_REJECTED));
    }

    /**
     * 把前端传来的 ID 列表收窄成可以安全塞进 {@code IN (...)} 的列表。
     * <p>去重 + 丢弃非正数 + <b>截断到 {@link PostRules#MAX_BATCH_IDS}</b>：
     * 这个参数会直接展开成 SQL 的 {@code IN} 列表，不设上界的话
     * "粘贴一整页 ID"能把语句撑到几百 KB，既慢又容易撞 MySQL 的
     * {@code max_allowed_packet}。这里超出部分直接丢掉而不报错 ——
     * 筛选条件多几条少几条只影响"看到多少"；真正会改数据的批量操作
     * 另有一道严格校验（见 {@link #batch}，超限直接报错）。
     */
    private List<Long> normalizeIdFilter(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null && id > 0) {
                unique.add(id);
            }
            if (unique.size() >= PostRules.MAX_BATCH_IDS) {
                break;
            }
        }
        return unique.isEmpty() ? null : new ArrayList<>(unique);
    }

    /**
     * 批量操作（社区管理台：<b>管理员及以上</b>，教师不在此列）。
     * <p>
     * 三个动作的语义<b>刻意不同</b>，因为它们对应三种不同的诉求：
     * <ul>
     *   <li>{@code unpublish} —— <b>下架</b>：把内容退回待审核。广场上立刻看不到，
     *       但内容与审核结论都还在，重新审一遍就能恢复。这是"有问题但不确定要不要留"
     *       时的默认选择；</li>
     *   <li>{@code reject} —— <b>批量拒绝</b>：待审核的内容直接判不通过（可带理由，
     *       作者能看到）；</li>
     *   <li>{@code delete} —— <b>彻底删除</b>：物理删除，<b>不可恢复</b>。
     *       审计日志里会留下"谁在什么时候删了谁的哪条内容"，但帖子本身找不回来。</li>
     * </ul>
     * <p><b>为什么不做"整批要么全成要么全败"</b>：一次批量里混着"处理了 3 条、
     * 跳过了 2 条"比整批回滚更符合实际 —— 管理员勾选时未必逐条看清状态，
     * 而整批失败会让他连那 3 条能处理的也做不掉。所以逐条判定、只处理状态允许的，
     * 并把处理数与跳过数都返回（见 {@code BatchResult}），整个过程仍在一个事务里。
     * <p>⚠️ 拒绝动作<b>只对待审核的内容生效</b>：已通过的必须先 {@code unpublish}
     * 回到待审核再拒绝（或直接 delete）。否则"已通过 → 已拒绝"会绕过审核队列，
     * 让一条正在广场上展示的内容在没经过任何队列的情况下被改状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVo.BatchResult batch(CommunityDto.BatchReq req, String clientIp) {
        requireManager();
        String action = req == null || req.action() == null ? "" : req.action().trim().toLowerCase();
        List<Long> ids = req == null ? List.of() : req.normalizedIds();
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "请先选择要处理的帖子");
        }
        if (ids.size() > PostRules.MAX_BATCH_IDS) {
            // 这里必须报错而不是截断：会改数据的操作不能让管理员以为 200 条都处理了
            throw new BizException(ErrorCode.BAD_PARAM,
                    "一次最多处理 " + PostRules.MAX_BATCH_IDS + " 条，请分批操作");
        }
        if (!PostRules.BATCH_ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "不支持的操作：" + action + "（可选 " + PostRules.BATCH_ACTIONS + "）");
        }

        long me = loginUser.id();
        int affected = 0;
        int skipped = 0;
        List<Long> deleted = new ArrayList<>();

        for (Long id : ids) {
            Post post = postMapper.selectById(id);
            if (post == null) {
                // 可能刚被作者删掉，或重复点击时已被上一批删掉 —— 跳过而不是报错
                skipped++;
                continue;
            }
            switch (action) {
                case PostRules.ACTION_UNPUBLISH -> {
                    if (post.isPending()) {
                        skipped++;   // 已经在待审核队列里了
                        continue;
                    }
                    post.setStatus(Post.STATUS_PENDING);
                    post.setReviewedBy(null);
                    post.setReviewTime(null);
                    post.setRejectReason(null);
                    postMapper.updateById(post);
                    affected++;
                }
                case PostRules.ACTION_REJECT -> {
                    if (!post.isPending()) {
                        skipped++;
                        continue;
                    }
                    post.setStatus(Post.STATUS_REJECTED);
                    post.setReviewedBy(me);
                    post.setReviewTime(LocalDateTime.now());
                    post.setRejectReason(PostRules.normalizeRejectReason(req.reason()));
                    postMapper.updateById(post);
                    affected++;
                }
                case PostRules.ACTION_DELETE -> {
                    deleted.add(id);
                    affected++;
                }
                default -> throw new BizException(ErrorCode.BAD_PARAM, "不支持的操作：" + action);
            }
            // 逐条留痕：批量操作的审计价值恰恰在于"到底动了哪几条" ——
            // 只记一条 count=N 无法回答"我的帖子怎么没了"
            auditService.log(me, "POST_" + action.toUpperCase(), "POST", String.valueOf(id),
                    clientIp, true, describeForAudit(post, action));
        }

        if (!deleted.isEmpty()) {
            postMapper.deleteByIds(deleted);
        }

        StringBuilder message = new StringBuilder(switch (action) {
            case PostRules.ACTION_UNPUBLISH -> "已下架 %d 条，已回到待审核队列".formatted(affected);
            case PostRules.ACTION_REJECT -> "已拒绝 %d 条".formatted(affected);
            default -> "已彻底删除 %d 条".formatted(affected);
        });
        if (skipped > 0) {
            message.append("；%d 条因当前状态不允许被跳过".formatted(skipped));
        }
        return new CommunityVo.BatchResult(affected, skipped, message.toString());
    }

    /** 审计备注：只记作者、长度与状态变化，<b>不记正文</b>（与聊天日志同一条纪律） */
    private String describeForAudit(Post post, String action) {
        long chars = post.getContent() == null ? 0 : post.getContent().length();
        if (PostRules.ACTION_DELETE.equals(action)) {
            return "author=%d,chars=%d,status=%d".formatted(post.getAuthorId(), chars,
                    post.getStatus());
        }
        int to = PostRules.ACTION_UNPUBLISH.equals(action)
                ? Post.STATUS_PENDING : Post.STATUS_REJECTED;
        return "author=%d,chars=%d,from=%d,to=%d".formatted(post.getAuthorId(), chars,
                post.getStatus(), to);
    }

    /**
     * 通过 / 拒绝。
     * <p>只允许处理<b>待审核</b>的帖子：重复审核（或两个审核员同时点）会报状态错误，
     * 而不是静默覆盖前一个人的结论 —— 后者会让"谁批的"变得不可追溯。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVo.ActionResult review(CommunityDto.ReviewReq req, String clientIp) {
        requireReviewer();
        long me = loginUser.id();
        Long postId = req == null ? null : req.postId();
        if (postId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定要审核的帖子");
        }
        Post post = mustExist(postId);
        if (!post.isPending()) {
            throw new BizException(ErrorCode.POST_STATE_INVALID,
                    "该帖子已被处理过（当前状态：" + statusLabel(post.getStatus()) + "）");
        }

        boolean approve = req.approve();
        post.setStatus(approve ? Post.STATUS_APPROVED : Post.STATUS_REJECTED);
        post.setReviewedBy(me);
        post.setReviewTime(LocalDateTime.now());
        // 通过时清空理由：否则"上一条的拒绝理由"会残留在通过的帖子上
        post.setRejectReason(approve ? null : PostRules.normalizeRejectReason(req.rejectReason()));
        postMapper.updateById(post);

        auditService.log(me, approve ? "POST_APPROVE" : "POST_REJECT", "POST",
                String.valueOf(postId), clientIp, true,
                approve ? null : "reason=" + post.getRejectReason());
        return new CommunityVo.ActionResult(toVo(post, me), approve ? "已通过" : "已拒绝");
    }

    /** 待审核数量（后台导航角标） */
    public long pendingCount() {
        requireReviewer();
        return postMapper.countPending();
    }

    // ---------------------------------------------------------------- 权限与装配

    /**
     * 当前用户是否有审核权限：<b>管理员及以上</b>（role 1 / 2 / 9）。
     * <p>需求原文是"管理员以上"，这里把<b>教师(2)</b> 也算进来 —— 教师是机房管理员，
     * 课堂上需要能处理学生发的内容。超级管理员(9) 自然包含在内。
     * <p>⚠️ 用枚举式判断而不是 {@code role >= 1}：角色编号不是有序等级，
     * 见类注释。
     */
    public boolean isReviewer() {
        int role = loginUser.role();
        return role == SysUser.ROLE_ADMIN || role == SysUser.ROLE_TEACHER
                || role == SysUser.ROLE_SUPER_ADMIN;
    }

    private void requireReviewer() {
        if (!isReviewer()) {
            throw new BizException(ErrorCode.POST_REVIEW_FORBIDDEN);
        }
    }

    /**
     * 当前用户是否是社区<b>管理者</b>：管理员(1) 或 超管(9)，<b>不含教师(2)</b>。
     * <p>与 {@link #isReviewer()} 分开是有意的：教师能审（课堂上处理学生发的内容），
     * 但"下架正在展示的内容 / 彻底删除别人的帖子"是破坏性操作，只给管理员及以上。
     * 这条口径与后端 {@code @SaCheckRole} 的取值集合、以及前端路由的
     * {@code ADMIN_ROLES} 三处必须一致 —— 任何一处放宽都会让界面与鉴权对不上。
     */
    public boolean isManager() {
        int role = loginUser.role();
        return role == SysUser.ROLE_ADMIN || role == SysUser.ROLE_SUPER_ADMIN;
    }

    private void requireManager() {
        if (!isManager()) {
            throw new BizException(ErrorCode.POST_MANAGE_FORBIDDEN);
        }
    }

    private void requireAuthor(Post post, long me) {
        if (post.getAuthorId() == null || post.getAuthorId() != me) {
            // 与"帖子不存在"统一报同一个错：不区分"不是你的"与"没有这条"，
            // 避免用别人的帖子 id 探测出"这条帖子存在"
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
    }

    private Post mustExist(Long id) {
        Post post = id == null ? null : postMapper.selectById(id);
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /**
     * 取一条<b>当前用户有权看到</b>的帖子行（带作者信息）。
     * <p>可见性：已通过 → 所有人；否则仅作者与审核者。
     * <p>直接查 {@code selectForReview} 再过滤会带出全表，所以这里用
     * {@code selectById} + 手工补作者；详情页是单条查询，代价可以忽略。
     */
    private PostMapper.PostRow mustVisible(Long id) {
        Post post = mustExist(id);
        long me = loginUser.id();
        boolean visible = post.isApproved()
                || (post.getAuthorId() != null && post.getAuthorId() == me)
                || isReviewer();
        if (!visible) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        return postMapper.selectFeedRowById(id);
    }

    private long countMine(long authorId, int status) {
        return postMapper.countByAuthorAndStatus(authorId, status);
    }

    /**
     * 装配成对外视图。
     * <p>作者名片走 {@link UserCardAssembler}，与好友、聊天<b>共用同一套字段白名单</b> ——
     * 这样社区不会因为"另开一条查询"而把邮箱之类的字段漏出去
     * （{@code FriendContractTest} 守着那个白名单）。
     */
    private CommunityVo.Post toVo(Post post, long me) {
        return toVo(null, post, me);
    }

    private CommunityVo.Post toVo(PostMapper.PostRow row, long me) {
        return toVo(row, null, me);
    }

    private CommunityVo.Post toVo(PostMapper.PostRow row, Post entity, long me) {
        Long id = row != null ? row.postId() : entity.getId();
        Long authorId = row != null ? row.authorId() : entity.getAuthorId();
        String content = row != null ? row.content() : entity.getContent();
        Integer linkCount = row != null ? row.linkCount() : entity.getLinkCount();
        Integer status = row != null ? row.status() : entity.getStatus();
        Long reviewedBy = row != null ? row.reviewedBy() : entity.getReviewedBy();
        LocalDateTime reviewTime = row != null ? row.reviewTime() : entity.getReviewTime();
        String rejectReason = row != null ? row.rejectReason() : entity.getRejectReason();
        LocalDateTime createTime = row != null ? row.createTime() : entity.getCreateTime();
        LocalDateTime updateTime = row != null ? row.updateTime() : entity.getUpdateTime();

        FriendVo.UserCard author = row != null
                ? cardAssembler.toCard(rowToUser(row), cardAssembler.relationOf(me, authorId), null)
                : authorCardOf(authorId, me);

        // 链接分段：这是"链接特殊显示"的唯一实现处。正文原样入库，
        // 每次读取时解析 —— 解析是纯函数、无副作用，也不随内容变化而需要回填。
        List<CommunityVo.Segment> segments = LinkSegmenter.split(content).segments().stream()
                .map(segment -> new CommunityVo.Segment(
                        segment.type(), segment.text(), segment.href()))
                .toList();

        boolean isAuthor = authorId != null && authorId == me;
        boolean pending = status != null && status == Post.STATUS_PENDING;
        return new CommunityVo.Post(id, author, content, segments, linkCount, status,
                reviewedBy, reviewTime, rejectReason, createTime, updateTime,
                // 待审核时不能改也不能删（它已在队列里，改/删会让审核员读到不一致的内容）
                isAuthor && !pending,
                isAuthor && !pending);
    }

    private FriendVo.UserCard authorCardOf(Long authorId, long me) {
        SysUser user = cardAssembler.loadUser(authorId);
        if (user == null) {
            // 作者已被删除：给一张"已注销"占位名片，而不是让整个列表 500
            return cardAssembler.deletedUserCard(authorId);
        }
        return cardAssembler.toCard(user, cardAssembler.relationOf(me, authorId), null);
    }

    private SysUser rowToUser(PostMapper.PostRow row) {
        SysUser user = new SysUser();
        user.setId(row.authorId());
        user.setUsername(row.authorUsername());
        user.setNickname(row.authorNickname());
        user.setRealName(row.authorRealName());
        user.setClassName(row.authorClassName());
        user.setAvatarKey(row.authorAvatarKey());
        user.setRole(row.authorRole());
        return user;
    }

    private String statusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Post.STATUS_PENDING -> "待审核";
            case Post.STATUS_APPROVED -> "已通过";
            case Post.STATUS_REJECTED -> "已拒绝";
            default -> "未知";
        };
    }
}
