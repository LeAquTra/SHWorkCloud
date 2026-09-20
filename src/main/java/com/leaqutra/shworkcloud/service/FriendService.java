package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.dto.FriendDto;
import com.leaqutra.shworkcloud.entity.FriendRelation;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.FriendRelationMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.RateLimiter;
import com.leaqutra.shworkcloud.vo.FriendVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 好友关系。
 * <p>
 * <b>两条不能破的规则：</b>
 * <ol>
 *   <li><b>好友上限 50</b>（{@link FriendRules#MAX_FRIENDS}）。
 *       发申请与接受申请<b>两处都要校验</b>，而且接受那一步必须在事务里
 *       <b>先锁住自己的用户行</b>再数数量 —— 否则同一秒内收到多个申请、
 *       全部点"同意"就会一起通过计数检查，把上限撑破。</li>
 *   <li><b>好友关系存双向两行</b>。任何"成为好友"的操作都必须让两条边
 *       同时是 {@code status = 1}；只改一条就会出现"我这边看到是好友、
 *       对方那边显示加好友"的撕裂状态。</li>
 * </ol>
 * <p>
 * 申请 → 接受 / 拒绝的完整语义：
 * <ul>
 *   <li>A 申请 B：写 A→B 的 {@code status = 0}；</li>
 *   <li>B 同意：把 A→B 置 1，并补一条 B→A 置 1（两条边都在了）；</li>
 *   <li>B 拒绝：<b>删掉</b> A→B 那一行（不留"已拒绝"状态，所以 A 之后可以再申请）；</li>
 *   <li>A 申请 B 时若 B 已经申请过 A：直接<b>互相成为好友</b>（双方都有意愿，不必再点一次）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendRelationMapper friendRelationMapper;
    private final UserMapper userMapper;
    private final UserCardAssembler cardAssembler;
    private final LoginUser loginUser;
    private final AuditService auditService;
    /**
     * 限流放在 Service 里而不是 Controller：申请好友是"写库 + 消耗对方注意力"的动作，
     * 只要调用到这个入口就必须受限，做成 Controller 的装饰很容易在新增入口时漏掉。
     */
    private final RateLimiter rateLimiter;

    // ---------------------------------------------------------------- 查询

    /**
     * 好友页所需的全部数据：好友 / 待处理申请（收+发）/ 名额。
     * <p>一次给全，避免前端进页面要打三个接口。
     */
    public FriendVo.Overview overview() {
        long me = loginUser.id();
        List<FriendVo.UserCard> friends =
                cardAssembler.toCards(friendRelationMapper.selectFriends(me));
        List<FriendVo.UserCard> incoming =
                cardAssembler.toRequestCards(friendRelationMapper.selectIncoming(me));
        List<FriendVo.UserCard> outgoing =
                cardAssembler.toRequestCards(friendRelationMapper.selectOutgoing(me));

        int slot = slotsUsed(me);
        return new FriendVo.Overview(friends, incoming, outgoing,
                FriendRules.MAX_FRIENDS, slot, Math.max(0, FriendRules.MAX_FRIENDS - slot));
    }

    /** 好友列表（只要好友，不要申请） */
    public List<FriendVo.UserCard> friends() {
        return cardAssembler.toCards(friendRelationMapper.selectFriends(loginUser.id()));
    }

    /**
     * 按<b>用户 ID 精确</b>找人。
     * <p>只允许按 ID 搜的理由见 {@code FriendRelationMapper.searchById} 的注释：
     * 核心是"不给枚举面" —— 按姓名前缀搜等于开放"把全校人翻一遍"的能力。
     * <p>搜到自己时返回自己的名片（{@code relation=SELF}），前端显示"这是你自己"；
     * 无效 ID（非正数）返回空列表而不是报错 —— 用户在输入框里敲了几个数字又删掉
     * 是很常见的操作。
     */
    public List<FriendVo.UserCard> search(Long targetId) {
        if (targetId == null || targetId <= 0) {
            return List.of();
        }
        List<FriendRelationMapper.FriendUserRow> rows =
                friendRelationMapper.searchById(targetId);
        if (rows.isEmpty()) {
            return List.of();
        }
        return cardAssembler.toCards(rows);
    }

    // ---------------------------------------------------------------- 写操作

    /**
     * 发起好友申请。
     * <p>若对方此前已经申请过我，则不再走"申请"，直接互相成为好友。
     *
     * @return 变更后的数据（前端一次刷新整个好友页）
     */
    @Transactional(rollbackFor = Exception.class)
    public FriendVo.Overview request(Long targetId, String clientIp) {
        long me = loginUser.id();
        if (targetId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定要添加的用户");
        }
        if (targetId == me) {
            throw new BizException(ErrorCode.FRIEND_SELF);
        }
        // 先限流再看库：被限流时连"对方是否存在"都不该被探测出来
        rateLimiter.checkFriendRequest(me);
        SysUser target = requireActiveUser(targetId);

        FriendRelation mine = friendRelationMapper.selectEdge(me, targetId);
        if (mine != null && mine.getStatus() != null
                && mine.getStatus() == FriendRelation.STATUS_FRIEND) {
            throw new BizException(ErrorCode.FRIEND_ALREADY);
        }
        if (mine != null) {
            // 边存在但还不是好友 → 就是我发出的、还没被处理的申请
            throw new BizException(ErrorCode.FRIEND_REQUEST_EXISTED);
        }

        // 对方已经申请过我：双方都有意愿，直接成为好友
        if (friendRelationMapper.countPendingEdge(targetId, me) > 0) {
            acceptInternal(targetId);
            auditService.log(me, "FRIEND_ACCEPT", "USER", String.valueOf(targetId),
                    clientIp, true, "via=mutual_request");
            return overview();
        }

        // 名额校验：已确认好友 + 待处理申请一起算，否则"49 个好友 + 十个申请全被接受"会超限
        FriendRules.ensureRoomForOneMore(slotsUsed(me));

        FriendRelation edge = new FriendRelation();
        edge.setUserId(me);
        edge.setFriendId(targetId);
        edge.setStatus(FriendRelation.STATUS_PENDING);
        friendRelationMapper.insert(edge);

        auditService.log(me, "FRIEND_REQUEST", "USER", String.valueOf(targetId), clientIp, true,
                "target=" + target.getUsername());
        return overview();
    }

    /**
     * 处理收到的申请。
     *
     * @param accept true 同意（成为好友）/ false 拒绝（删掉申请）
     */
    @Transactional(rollbackFor = Exception.class)
    public FriendVo.Overview handle(FriendDto.HandleReq req, String clientIp) {
        long me = loginUser.id();
        Long fromId = req == null ? null : req.userId();
        if (fromId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定要处理的申请人");
        }
        if (!req.accept()) {
            return decline(fromId, clientIp);
        }
        return accept(fromId, clientIp);
    }

    /**
     * 同意申请：把 A→B 置为好友，并补上 B→A。
     * <p><b>名额校验必须在锁内做</b>，见 {@link #acceptInternal}。
     */
    private FriendVo.Overview accept(Long fromId, String clientIp) {
        acceptInternal(fromId);
        auditService.log(loginUser.id(), "FRIEND_ACCEPT", "USER", String.valueOf(fromId),
                clientIp, true, null);
        return overview();
    }

    /** 拒绝申请：删掉那条待处理行（保留"之后还能再申请"的可能） */
    private FriendVo.Overview decline(Long fromId, String clientIp) {
        long me = loginUser.id();
        FriendRelation incoming = friendRelationMapper.selectEdge(fromId, me);
        if (incoming == null || incoming.getStatus() == null
                || incoming.getStatus() != FriendRelation.STATUS_PENDING) {
            throw new BizException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }
        friendRelationMapper.deleteById(incoming.getId());
        auditService.log(me, "FRIEND_DECLINE", "USER", String.valueOf(fromId), clientIp, true, null);
        return overview();
    }

    /**
     * 成为好友的公共路径（"同意申请"与"互相申请"都走它）。
     * <p>
     * <b>并发安全的关键在这里。</b> 顺序是：
     * <ol>
     *   <li>先 {@code SELECT ... FOR UPDATE} 锁住<b>自己</b>的用户行。
     *       这样"同一个人同时点同意多个申请"会被串行化，重数名额时看到的是
     *       前一次提交后的真实值；</li>
     *   <li>锁内重新数名额并校验（不能在锁外数，那是典型的 check-then-act）；</li>
     *   <li>再把两条边置为好友。</li>
     * </ol>
     * 锁的是 {@code sys_user} 的一行而不是 {@code friend_relation}：后者在
     * "还没有这条边"的情况下没有行可锁，锁不住任何东西。
     * <p>
     * 仍存在一个理论上限敞口：A 的两个不同好友<b>同时</b>点"同意"，会各自锁住
     * <b>自己</b>的行，因此可能让 A 达到 51 人。要彻底关掉需要按 id 升序把双方
     * 的行都锁住（避免互相等待死锁）。以本项目的规模，这个敞口不值得引入
     * 双行加锁的复杂度，但把它写在这里，避免后人误以为已经严丝合缝。
     */
    private void acceptInternal(long fromId) {
        long me = loginUser.id();

        // 1) 锁自己：把"同一用户并发接受多个申请"串行化
        userMapper.selectByIdForUpdate(me);

        // 2) 锁内重新校验（这里是唯一可信的检查点）
        FriendRelation incoming = friendRelationMapper.selectEdge(fromId, me);
        if (incoming == null || incoming.getStatus() == null
                || incoming.getStatus() != FriendRelation.STATUS_PENDING) {
            throw new BizException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }

        FriendRelation reverse = friendRelationMapper.selectEdge(me, fromId);
        if (reverse != null && reverse.getStatus() != null
                && reverse.getStatus() == FriendRelation.STATUS_FRIEND) {
            // 已经是好友了（重复点击、或双方同时点了同意）：幂等返回，不报错
            return;
        }

        // 名额：这次接受会让 fromId 也 +1，所以两边都要有余量
        FriendRules.ensureRoomForOneMore(slotsUsed(me));
        FriendRules.ensureRoomForOneMore(slotsUsed(fromId));

        friendRelationMapper.markFriend(fromId, me);

        if (reverse == null) {
            FriendRelation edge = new FriendRelation();
            edge.setUserId(me);
            edge.setFriendId(fromId);
            edge.setStatus(FriendRelation.STATUS_FRIEND);
            friendRelationMapper.insert(edge);
        } else {
            // 我此前也申请过对方（双向往来），那条待处理边一并升级
            friendRelationMapper.markFriend(me, fromId);
        }
    }

    /**
     * 删除好友：两条边都删。
     * <p>只删我这一条会出现"对方列表里还有我"的撕裂状态，
     * 所以必须成对删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public FriendVo.Overview remove(Long friendId, String clientIp) {
        long me = loginUser.id();
        if (friendId == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "请指定要删除的好友");
        }

        FriendRelation mine = friendRelationMapper.selectEdge(me, friendId);
        FriendRelation reverse = friendRelationMapper.selectEdge(friendId, me);
        if (mine == null && reverse == null) {
            // 已经没关系了：幂等返回，不报错（用户连点两次删除是常态）
            return overview();
        }

        if (mine != null) {
            friendRelationMapper.deleteById(mine.getId());
        }
        if (reverse != null) {
            friendRelationMapper.deleteById(reverse.getId());
        }

        auditService.log(me, "FRIEND_REMOVE", "USER", String.valueOf(friendId), clientIp, true, null);
        return overview();
    }

    // ---------------------------------------------------------------- 供其它服务使用

    /** 是否为好友（聊天发送前的必查项，见 {@code ChatService.send}） */
    public boolean isFriend(long me, long otherId) {
        return cardAssembler.isFriend(me, otherId);
    }

    // ---------------------------------------------------------------- 内部工具

    /** 已占用名额 = 已确认好友 + 待处理申请（收发的都算） */
    private int slotsUsed(long userId) {
        long friends = friendRelationMapper.countFriends(userId);
        long pending = friendRelationMapper.countPending(userId);
        long total = friends + pending;
        // 理论上不会溢出 int（上限 50 + 少量申请），但仍做一次钳制，
        // 避免脏数据把"还可添加"算成负数展示成"可添加 -2 人"
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /** 目标用户必须存在、未删除、未禁用 */
    private SysUser requireActiveUser(long targetId) {
        SysUser user = userMapper.selectById(targetId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.FRIEND_TARGET_NOT_FOUND);
        }
        return user;
    }
}
