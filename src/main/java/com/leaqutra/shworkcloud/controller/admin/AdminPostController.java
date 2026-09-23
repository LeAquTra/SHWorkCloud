package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.CommunityDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.PostRules;
import com.leaqutra.shworkcloud.service.PostService;
import com.leaqutra.shworkcloud.vo.CommunityVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 社区：审核（教师及以上）与内容管理（管理员及以上）。
 * <p>
 * <b>两类权限刻意分开</b>：
 * <ul>
 *   <li><b>审核</b> —— {@code admin} / {@code teacher} / {@code super_admin}：
 *       教师就是机房管理员，课堂上需要能处理学生发的内容，所以把教师纳入；</li>
 *   <li><b>管理</b> —— 只给 {@code admin} / {@code super_admin}：
 *       下架正在展示的内容、彻底删除别人的帖子都是破坏性操作，不给教师。</li>
 * </ul>
 * Service 层的 {@code PostService.requireReviewer()} / {@code requireManager()}
 * 会独立再校验一次 —— 注解只对 Controller 生效，将来若有人从别处调 Service，
 * 那道防线仍然在。
 * <p>
 * ⚠️ <b>绝不写成 {@code @SaCheckRole("admin")} 之外的数值比较</b>：
 * 本项目的角色编号不是有序等级（0 学生 / 1 管理员 / 2 教师 / 9 超管），
 * 教师(2) 的数值比管理员(1) 大但权限更小。Sa-Token 的角色字符串是派生值
 * （见 {@code StpInterfaceImpl}）：9 → super_admin+admin+teacher，
 * 1 → admin+teacher，2 → teacher —— 所以"管理员及以上"必须显式列出角色名。
 * <p>
 * 🔴 <b>{@code mode = SaMode.OR} 不能省（真实故障）</b>：Sa-Token 的
 * {@code @SaCheckRole} 默认是 <b>AND</b> 语义 —— 要求同时具备列出的<b>全部</b>角色。
 * 而本项目的角色是派生的，只有超管同时拥有 super_admin + admin + teacher；
 * 管理员(1) 只有 admin + teacher，教师(2) 只有 teacher。于是不写 OR 时：
 * <pre>
 *   超管   → admin ✔ teacher ✔ super_admin ✔ → 通过
 *   管理员 → admin ✔ teacher ✔ super_admin ✘ → <b>403</b>（用户报的就是这个）
 *   教师   → admin ✘                          → 403
 * </pre>
 * 表现就是"社区审核只有超级管理员能进"。本项目其它 Controller 的多角色注解
 * <b>全都写了 OR</b>（AdminUserController / AdminCaptchaController /
 * AdminImportController），本文件当初是唯一漏掉的。
 * 回归守卫：{@code AdminPostAuthorizationTest}、{@code CommunityContractTest}。
 */
@RestController
@RequestMapping("/admin/posts")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;
    private final ClientIpUtil clientIpUtil;

    /**
     * 后台帖子列表（审核队列 / 社区管理台共用）。所有筛选条件都可选：
     * <pre>
     *   GET /api/admin/posts?status=0                  # 只看待审核（按 id 倒序）
     *   GET /api/admin/posts?status=1&amp;page=1           # 已通过
     *   GET /api/admin/posts?status=2                  # 已拒绝
     *   GET /api/admin/posts                           # 全部
     *   GET /api/admin/posts?postId=12                 # 精确查一条（举报/工单里的 ID）
     *   GET /api/admin/posts?ids=12,15,18              # 按 ID 批量取（核对举报清单）
     *   GET /api/admin/posts?authorId=1002             # 某个学生发过的全部内容
     *   GET /api/admin/posts?keyword=广告              # 正文关键字
     * </pre>
     * 返回里带 {@code pendingTotal}（全局待审数，侧栏红点用）与 {@code statusCounts}
     * （三个状态的全局分布，管理台统计卡片用）—— 两者都<b>不受筛选条件影响</b>。
     * <p>响应形状见 {@code CommunityVo.AdminPostPage}：它是
     * {@code ReviewPage}（审核页在用的契约，字段不要动）加一个统计字段。
     * 审核页只读 {@code pendingTotal}，忽略多余字段即可。
     */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @GetMapping
    public R<CommunityVo.AdminPostPage> queue(CommunityDto.ReviewQuery query) {
        return R.ok(postService.reviewPage(query));
    }

    /** 待审核数量（后台导航角标，轮询用，响应体最小） */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/pending-count")
    public R<Long> pendingCount() {
        return R.ok(postService.pendingCount());
    }

    /**
     * 社区内容的状态分布（管理台顶部的统计卡片）。
     * <p>与列表接口分开是刻意的：三个数字<b>不受筛选条件影响</b>，
     * 管理员翻页、改筛选时它们不该跟着变，也就没必要每次重算。
     */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/summary")
    public R<CommunityVo.StatusCounts> summary() {
        return R.ok(postService.statusCounts());
    }

    /**
     * 通过 / 拒绝。
     * <pre>
     *   POST /api/admin/posts/review   {"postId": 12, "approve": true}
     *   POST /api/admin/posts/review   {"postId": 12, "approve": false, "rejectReason": "含不良信息"}
     * </pre>
     * 拒绝理由作者可见（建议填但不强制，理由见 {@code PostRules.normalizeRejectReason}）。
     * 只能处理待审核的帖子：重复处理会返回 40097，而不是静默覆盖前一个人的结论。
     */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @PostMapping("/review")
    public R<CommunityVo.ActionResult> review(@RequestBody CommunityDto.ReviewReq req,
                                              HttpServletRequest request) {
        return R.ok(postService.review(req, clientIpUtil.get(request)));
    }

    /**
     * 批量操作（社区管理台：<b>管理员或超管</b>，教师不在此列）。
     * <pre>
     *   POST /api/admin/posts/batch  {"ids":[12,13],"action":"unpublish"}
     *   POST /api/admin/posts/batch  {"ids":[12,13],"action":"reject","reason":"含广告"}
     *   POST /api/admin/posts/batch  {"ids":[12,13],"action":"delete"}
     * </pre>
     * 返回 {@code affected} / {@code skipped}，而不是只回一个 200：批量操作最容易出的
     * 事是"看起来成功了，其实一条都没匹配上"（ID 粘错、状态已经变了），
     * 必须把真实处理条数带回去让界面说清楚。
     * <p>⚠️ {@code delete} 是<b>物理删除且不可恢复</b>，前端必须二次确认。
     * <p>一次最多 {@code PostRules.MAX_BATCH_IDS} 条，超出直接报错而不是静默截断。
     */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @PostMapping("/batch")
    public R<CommunityVo.BatchResult> batch(@RequestBody CommunityDto.BatchReq req,
                                            HttpServletRequest request) {
        return R.ok(postService.batch(req, clientIpUtil.get(request)));
    }

    /**
     * 彻底删除单条内容（管理台行内操作）。
     * <p>与用户端 {@code DELETE /community/posts/{id}} 的区别有两点：
     * ① 不看作者是谁（管理员可以删任何人的）；② <b>不限制状态</b> ——
     * 用户端不让删待审核的帖子（怕审核员点开时报不存在），
     * 而管理员删它恰恰就是为了把它从队列里拿掉。
     */
    @SaCheckRole(value = {"admin", "super_admin"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public R<CommunityVo.BatchResult> delete(@PathVariable Long id,
                                             HttpServletRequest request) {
        CommunityDto.BatchReq req = new CommunityDto.BatchReq(
                List.of(id), PostRules.ACTION_DELETE, null);
        return R.ok(postService.batch(req, clientIpUtil.get(request)));
    }

    /**
     * 单条详情（审核/管理时点开看完整内容）。
     * <p>与用户端 {@code GET /community/posts/{id}} 的区别是<b>不受可见性限制</b>：
     * 待审内容审核员必须能看到，否则没法审。
     */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public R<CommunityVo.Post> detail(@PathVariable Long id) {
        return R.ok(postService.detail(id));
    }
}
