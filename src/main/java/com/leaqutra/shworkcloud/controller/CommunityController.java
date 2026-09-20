package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.CommunityDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.PostService;
import com.leaqutra.shworkcloud.vo.CommunityVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区（用户端）。
 * <p>
 * <b>没有"直接发布"的接口</b>：{@code POST /community/posts} 落库的 {@code status}
 * 恒为 0（待审核），通过的唯一路径是 {@code POST /admin/posts/review}。
 * 这样"需要审核"不是靠前端少给一个按钮，而是接口层面就没有那条路。
 * <p>
 * 权限：任何已登录用户都能发帖与浏览。审核相关接口在
 * {@link com.leaqutra.shworkcloud.controller.admin.AdminPostController}。
 */
@RestController
@RequestMapping("/community")
@RequiredArgsConstructor
public class CommunityController {

    private final PostService postService;
    private final ClientIpUtil clientIpUtil;

    /**
     * 时间线（只含已通过的帖子），按时间倒序，游标翻页。
     * <pre>
     *   # 首屏（默认按时间倒序，一页 20 条）
     *   GET /api/community/posts
     *   # 下一页：把上一页返回的 nextBeforeId 传回来
     *   GET /api/community/posts?beforeId=123
     *   # 某个作者的帖子（点作者头像进主页后的"TA 的帖子"）
     *   # ⚠️ 只返回【已通过】的，作者自己的待审/被拒内容只有 authorId=自己 时才看得到
     *   GET /api/community/posts?authorId=1002
     *   # 我的帖子（含待审与被拒，只有自己能看到；会忽略 authorId）
     *   GET /api/community/posts?mine=true
     * </pre>
     */
    @GetMapping("/posts")
    public R<CommunityVo.Feed> feed(CommunityDto.FeedQuery query) {
        return R.ok(postService.feed(query));
    }

    /** 我的帖子概览：待审 / 已通过 / 已拒绝各多少（页签角标） */
    @GetMapping("/posts/mine/summary")
    public R<CommunityVo.MySummary> mySummary() {
        return R.ok(postService.mySummary());
    }

    /** 单条详情。未通过的帖子只有作者与审核者能拿到，其他人一律 40096 */
    @GetMapping("/posts/{id}")
    public R<CommunityVo.Post> detail(@PathVariable Long id) {
        return R.ok(postService.detail(id));
    }

    /**
     * 发帖。
     * <p>返回的 {@code post.status} 恒为 0（待审核），`message` 是"已提交，等待管理员审核" ——
     * 前端应当据此提示用户，而不是把帖子直接插进时间线。
     */
    @PostMapping("/posts")
    public R<CommunityVo.ActionResult> create(@RequestBody CommunityDto.PostReq req,
                                              HttpServletRequest request) {
        return R.ok(postService.create(req, clientIpUtil.get(request)));
    }

    /**
     * 编辑自己的帖子。
     * <p>⚠️ <b>编辑后一定回到待审核</b>，包括已经通过的帖子 —— 否则作者可以先用
     * 正常内容过审、再换成任何东西。所以编辑成功后该帖子会从公开时间线暂时消失，
     * 前端要提示"修改已提交，需重新审核"。
     */
    @PutMapping("/posts/{id}")
    public R<CommunityVo.ActionResult> update(@PathVariable Long id,
                                              @RequestBody CommunityDto.PostReq req,
                                              HttpServletRequest request) {
        return R.ok(postService.update(id, req, clientIpUtil.get(request)));
    }

    /** 删除自己的帖子（待审核中的不能删：它正在被看，删掉会让审核员点开即报错） */
    @DeleteMapping("/posts/{id}")
    public R<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        postService.delete(id, clientIpUtil.get(request));
        return R.ok();
    }
}
