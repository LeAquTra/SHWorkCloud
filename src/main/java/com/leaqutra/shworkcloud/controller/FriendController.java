package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.FriendDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.FriendService;
import com.leaqutra.shworkcloud.vo.FriendVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 好友关系。
 * <p>
 * <b>路径约定</b>与项目其它 Controller 一致：这里写业务路径，{@code /api} 由
 * {@code server.servlet.context-path} 提供，不要再写一遍。
 * <p>
 * <b>权限</b>：任何已登录用户都能用自己的一份好友列表，没有角色门槛，
 * 因此不进 {@code SaTokenConfigure} 的任何角色规则 —— 但也不进
 * {@code PUBLIC_PATHS}，未登录一律 401。
 * <p>
 * <b>所有写操作的返回都是"刷新后的完整好友页数据"</b>（{@link FriendVo.Overview}）：
 * 加好友/同意/删除都会改变"名额还剩多少""待处理列表"这些派生数据，
 * 只回一个 {@code ok} 会让前端不得不立刻再打一次查询接口。一次给全更省事，
 * 也避免两次请求之间界面短暂显示旧数据。
 */
@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;
    private final ClientIpUtil clientIpUtil;

    /** 好友页汇总：好友 + 待处理申请（收/发）+ 名额（上限 / 已用 / 剩余） */
    @GetMapping
    public R<FriendVo.Overview> overview() {
        return R.ok(friendService.overview());
    }

    /** 只要好友列表（聊天页左侧栏用，不需要申请那两块） */
    @GetMapping("/list")
    public R<List<FriendVo.UserCard>> list() {
        return R.ok(friendService.friends());
    }

    /**
     * 按<b>用户 ID</b> 找人。
     * <pre>
     *   GET /api/friends/search?userId=1002
     * </pre>
     * <p>只接受 ID，没有"按姓名 / 昵称模糊搜"的入口 —— 理由见
     * {@code FriendRelationMapper.searchById} 的注释：模糊搜等于把"翻一遍全校人"
     * 变成一项功能，而 ID 是精确值，必须先从别处（名单、对方主页地址
     * {@code /user/1002}）拿到。
     * <p>搜到自己时返回自己的名片（{@code relation=SELF}），前端显示"这是你自己"。
     * ID 非法或不存在时返回空数组，不报错。
     */
    @GetMapping("/search")
    public R<List<FriendVo.UserCard>> search(@RequestParam(required = false) Long userId) {
        return R.ok(friendService.search(userId));
    }

    /** 发起好友申请；若对方此前已申请过我，则直接互相成为好友 */
    @PostMapping("/requests")
    public R<FriendVo.Overview> request(@RequestBody FriendDto.FriendRequestReq req,
                                        HttpServletRequest request) {
        return R.ok(friendService.request(req == null ? null : req.userId(),
                clientIpUtil.get(request)));
    }

    /**
     * 处理收到的申请：同意 / 拒绝。
     * <p>返回里的 {@code friends} 与 {@code incoming} 都已是最新状态。
     */
    @PostMapping("/requests/handle")
    public R<FriendVo.Overview> handle(@RequestBody FriendDto.HandleReq req,
                                       HttpServletRequest request) {
        return R.ok(friendService.handle(req, clientIpUtil.get(request)));
    }

    /**
     * 删除好友。
     * <p>路径参数用 {@code /{friendId}}，需要传的是<b>对方的用户 ID</b>。
     * 删的是双向边（两边列表同时消失），只删一边会留下"对方还能看到我"的撕裂状态。
     */
    @DeleteMapping("/{friendId}")
    public R<FriendVo.Overview> remove(@PathVariable Long friendId, HttpServletRequest request) {
        return R.ok(friendService.remove(friendId, clientIpUtil.get(request)));
    }
}
