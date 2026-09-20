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

    // ---------------------------------------------------------------- 审核

    /** 审核队列（管理员及以上） */
    public CommunityVo.ReviewPage reviewPage(CommunityDto.ReviewQuery query) {
        requireReviewer();
        Integer status = PostRules.normalizeStatus(query == null ? null : query.status());
        long page = query == null || query.page() == null || query.page() < 1 ? 1 : query.page();
        long size = PostRules.normalizeReviewSize(query == null ? null : query.size());
        long offset = (page - 1) * size;

        long total = postMapper.countForReview(status);
        List<PostMapper.PostRow> rows = total == 0
                ? List.of()
                : postMapper.selectForReview(status, offset, size);

        long me = loginUser.id();
        List<CommunityVo.Post> records = new ArrayList<>(rows.size());
        for (PostMapper.PostRow row : rows) {
            records.add(toVo(row, me));
        }
        return new CommunityVo.ReviewPage(records, total, page, size, postMapper.countPending());
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
