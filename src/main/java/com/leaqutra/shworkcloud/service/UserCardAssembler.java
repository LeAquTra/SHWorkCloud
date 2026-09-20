package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.entity.FriendRelation;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.FriendRelationMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.FriendVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 用户名片装配器。
 * <p>
 * 存在的唯一理由是<b>把"用户行 + 我与 TA 的关系"翻译成对外的
 * {@link FriendVo.UserCard}</b>，并且这份翻译只写一遍。好友列表、搜索结果、
 * 他人主页、聊天窗顶部四处都要用到名片，如果各写一份，
 * 迟早会出现"某处的接口多带出了邮箱"这种不一致 —— 而字段越权就是这么漏出去的。
 * <p>
 * 它<b>不注入</b> {@link FriendService} 也不被 FriendService 反向注入，
 * 因此不会产生循环依赖；{@link UserService} 也复用它来出他人主页。
 */
@Component
@RequiredArgsConstructor
public class UserCardAssembler {

    private final FriendRelationMapper friendRelationMapper;
    private final AvatarService avatarService;
    private final LoginUser loginUser;
    /**
     * 单条取用户：给"只有 authorId、手头没有用户行"的场景用
     * （社区帖子作者的兜底路径）。列表场景一律用联表行，不要在这里循环调用。
     */
    private final com.leaqutra.shworkcloud.mapper.UserMapper userMapper;

    /** 按 id 取用户；不存在（或已逻辑删除）返回 {@code null} */
    public SysUser loadUser(Long userId) {
        return userId == null ? null : userMapper.selectById(userId);
    }

    /** 当前登录者的名片（用于"我自己"的场景） */
    public FriendVo.UserCard selfCard(SysUser user) {
        return toCard(user, FriendVo.UserCard.RELATION_SELF, null);
    }

    /**
     * 把一张名片装配出来，并把 {@code displayName} 算好。
     * <p>{@code displayName} 在服务端算而不是让前端拼：好友列表、聊天标题、
     * 搜索结果、社区作者名任何一处漏了判空，就会出现"某处显示空名字"的 bug。
     */
    public FriendVo.UserCard toCard(SysUser user, String relation, LocalDateTime requestedAt) {
        String avatarKey = user.getAvatarKey();
        return new FriendVo.UserCard(
                user.getId(),
                user.getUsername(),
                displayName(user),
                user.getNickname(),
                user.getRealName(),
                user.getClassName(),
                avatarService.signedUrl(avatarKey),
                avatarService.version(avatarKey),
                user.getSignature(),
                user.getRole() == null ? 0 : user.getRole().intValue(),
                relation,
                requestedAt);
    }

    /**
     * 「已注销用户」占位名片。
     * <p>用于社区里作者账号已被删除、但帖子还在的场景：与其让整个列表 500，
     * 不如给一张明确写着"已注销"的名片 —— 帖子内容本身仍然值得保留。
     * <p>只暴露 userId，<b>不暴露学号与姓名</b>：账号都注销了，
     * 再把学号挂在帖子上没有道理。
     */
    public FriendVo.UserCard deletedUserCard(Long userId) {
        return new FriendVo.UserCard(userId, "已注销", "已注销用户", null, null, null,
                null, null, null, 0, FriendVo.UserCard.RELATION_NONE, null);
    }

    /**
     * 好友列表 / 搜索结果 → 名片。
     * <p>
     * 关系是<b>逐条查</b>的：一次列表最多 50 人，50 次走唯一索引 {@code uk_edge}
     * 的点查，比"一次性把两个方向的边全捞出来再在内存里对齐"更好读，
     * 也避免把 {@code IN (50 个 id)} 这种写法散到各处。真到了瓶颈期再换。
     */
    public List<FriendVo.UserCard> toCards(List<FriendRelationMapper.FriendUserRow> rows) {
        long me = loginUser.id();
        return rows.stream()
                .map(row -> toCard(rowToUser(row), relationOf(me, row.userId()), null))
                .toList();
    }

    /**
     * 申请列表 → 名片（比 {@link #toCards} 多带一个"申请时间"）。
     * <p>
     * 刻意做成两个<b>类型不同</b>的重载，而不是一个带 {@code Function} 取值的通用方法：
     * 后者在调用处传 {@code null} 或 {@code row -> row.friendSince()} 时看不出差别，
     * 而这两种行的形状本来就不同（见 {@code FriendRelationMapper} 里两个 record 的注释）。
     * 更重要的是：<b>通用方法会让"某个查询少给一列"变成运行期才炸的错</b>，
     * 而分成两个 record 之后，列数与形状在编译期就是对得上的。
     */
    public List<FriendVo.UserCard> toRequestCards(
            List<FriendRelationMapper.FriendRequestRow> rows) {
        long me = loginUser.id();
        return rows.stream()
                .map(row -> toCard(rowToUser(row), relationOf(me, row.userId()),
                        row.requestedAt()))
                .toList();
    }

    /**
     * 判断"我"与某个用户的关系。
     * <p>三个查询都是走 {@code uk_edge} / {@code idx_friend_status} 的点查。
     * <p><b>顺序很重要</b>：先看是否是好友（最常用的分支），再看对方是否申请过我
     * （决定显示"同意/拒绝"还是"加好友"）。
     */
    public String relationOf(long me, long otherId) {
        if (me == otherId) {
            return FriendVo.UserCard.RELATION_SELF;
        }
        if (isFriend(me, otherId)) {
            return FriendVo.UserCard.RELATION_FRIEND;
        }
        if (friendRelationMapper.countPendingEdge(otherId, me) > 0) {
            // 对方申请加我 → 界面上应该出现"同意 / 拒绝"
            return FriendVo.UserCard.RELATION_INCOMING;
        }
        if (friendRelationMapper.countPendingEdge(me, otherId) > 0) {
            return FriendVo.UserCard.RELATION_OUTGOING;
        }
        return FriendVo.UserCard.RELATION_NONE;
    }

    /**
     * 是否已是好友：两个方向的边都存在，且都已是"好友"状态。
     * <p>
     * ⚠️ 这里<b>必须取出行来判 status</b>，不能用 {@code countPendingEdge}：
     * 那个方法的名字容易让人以为是"这条边存不存在"，实际它带 {@code status = 0}
     * 条件，只数"待处理的申请"。用它判好友会恒为 false ——
     * 表现就是"已是好友却提示还不是好友、发不出消息"。
     */
    public boolean isFriend(long me, long otherId) {
        if (me == otherId) {
            return false;
        }
        return isFriendEdge(me, otherId) && isFriendEdge(otherId, me);
    }

    private boolean isFriendEdge(long userId, long friendId) {
        FriendRelation edge = friendRelationMapper.selectEdge(userId, friendId);
        return edge != null && edge.getStatus() != null
                && edge.getStatus() == FriendRelation.STATUS_FRIEND;
    }

    /**
     * 展示名优先级：昵称 → 真实姓名 → 登录名。
     * <p>在服务端算好而不是让前端拼：好友列表、聊天标题、搜索结果、@ 提及
     * 任何一处漏了判断，就会出现"某处显示空白名字"的 bug。
     */
    public static String displayName(SysUser user) {
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        return user.getUsername();
    }

    /** 联表行 → 实体。只回填名片会用到的列，避免误以为拿到了完整用户 */
    private static SysUser rowToUser(FriendRelationMapper.FriendUserRow row) {
        SysUser user = new SysUser();
        user.setId(row.userId());
        user.setUsername(row.username());
        user.setNickname(row.nickname());
        user.setRealName(row.realName());
        user.setClassName(row.className());
        user.setAvatarKey(row.avatarKey());
        user.setSignature(row.signature());
        user.setRole(row.role());
        return user;
    }

    /** 申请行 → 实体（与上一行同样的列，只是行的类型不同） */
    private static SysUser rowToUser(FriendRelationMapper.FriendRequestRow row) {
        SysUser user = new SysUser();
        user.setId(row.userId());
        user.setUsername(row.username());
        user.setNickname(row.nickname());
        user.setRealName(row.realName());
        user.setClassName(row.className());
        user.setAvatarKey(row.avatarKey());
        user.setSignature(row.signature());
        user.setRole(row.role());
        return user;
    }
}
