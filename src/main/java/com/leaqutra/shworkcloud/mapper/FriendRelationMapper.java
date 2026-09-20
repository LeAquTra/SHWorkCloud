package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.FriendRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 好友关系表。
 * <p>
 * 涉及 {@code sys_user} 的联表查询都<b>手写 SQL 并显式带上 {@code u.deleted = 0} 与
 * {@code u.status = 1}</b>：{@code @TableLogic} 只对 {@code FriendRelation} 自己的行生效，
 * 不会穿透到联表进来的用户表上。少了这个条件就会出现"已注销用户仍出现在好友列表里"。
 */
@Mapper
public interface FriendRelationMapper extends BaseMapper<FriendRelation> {

    // ---------------------------------------------------------------- 单边

    /** 指定的有向边；不存在返回 null */
    @Select("""
            SELECT * FROM friend_relation
            WHERE user_id = #{userId} AND friend_id = #{friendId}
            LIMIT 1
            """)
    FriendRelation selectEdge(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * 该用户已确认的好友数。
     * <p>用于 50 上限判断。计数走 {@code idx_user_status_friend} 覆盖索引。
     */
    @Select("SELECT COUNT(*) FROM friend_relation "
            + "WHERE user_id = #{userId} AND status = 1")
    long countFriends(@Param("userId") Long userId);

    /**
     * 该用户的待处理申请数（收到的 + 发出的）。
     * <p>用途有两个：① 加到"已用名额"上，避免出现"49 个好友 + 一堆等接受的申请"时
     * 一次涌进来把上限撑破；② 作为前端展示"还可添加 N 人"的口径。
     */
    @Select("SELECT COUNT(*) FROM friend_relation "
            + "WHERE status = 0 AND (user_id = #{userId} OR friend_id = #{userId})")
    long countPending(@Param("userId") Long userId);

    /**
     * 是否存在这一个方向的待处理申请。
     * <p>本表<b>没有逻辑删除</b>（物理删除，理由见 {@code FriendRelation} 的类注释），
     * 所以这里不需要任何 {@code deleted} 条件。
     */
    @Select("""
            SELECT COUNT(*) FROM friend_relation
            WHERE user_id = #{userId} AND friend_id = #{friendId}
              AND status = 0
            """)
    long countPendingEdge(@Param("userId") Long userId, @Param("friendId") Long friendId);

    // ---------------------------------------------------------------- 名单

    /**
     * 我的好友列表（带对方昵称 / 头像，按成为好友的时间倒序）。
     * <p>
     * ⚠️ MyBatis 的 record 构造器自动映射按<b>位置</b>对齐：列的个数与顺序必须与
     * {@link FriendUserRow} 的组件完全一致（<b>9 列 / 9 个组件</b>）。
     * 这条纪律由 {@code MapperResultSetContractTest} 静态守住 ——
     * 曾经因为列数与组件数不一致，导致接口运行期 500。
     * <p>"成为好友的时间"（{@code r.update_time}）<b>不下发</b>：名片上不需要它，
     * 会话列表要的是"最后消息时间"，那个由 {@code ChatService} 单独查。
     */
    @Select("""
            SELECT r.friend_id AS user_id,
                   u.username    AS username,
                   u.nickname    AS nickname,
                   u.real_name   AS real_name,
                   u.avatar_key  AS avatar_key,
                   u.role        AS role,
                   u.signature   AS signature,
                   u.class_name  AS class_name,
                   u.status      AS status
            FROM friend_relation r
            JOIN sys_user u ON u.id = r.friend_id
            WHERE r.user_id = #{userId} AND r.status = 1
              AND u.deleted = 0 AND u.status = 1
            ORDER BY r.update_time DESC, r.friend_id DESC
            """)
    List<FriendUserRow> selectFriends(@Param("userId") Long userId);

    /**
     * 我发出的、对方还没处理的申请。
     * <p>必须在列表里显示出来：否则用户点了"添加好友"之后界面上什么都没变，
     * 会以为没点上而反复点（然后被限流拦住，体验更差）。
     */
    @Select("""
            SELECT r.friend_id AS user_id,
                   u.username    AS username,
                   u.nickname    AS nickname,
                   u.real_name   AS real_name,
                   u.avatar_key  AS avatar_key,
                   u.role        AS role,
                   u.signature   AS signature,
                   u.class_name  AS class_name,
                   u.status      AS status,
                   r.create_time AS requested_at
            FROM friend_relation r
            JOIN sys_user u ON u.id = r.friend_id
            WHERE r.user_id = #{userId} AND r.status = 0 AND u.deleted = 0
            ORDER BY r.create_time DESC, r.friend_id DESC
            """)
    List<FriendRequestRow> selectOutgoing(@Param("userId") Long userId);

    /** 别人发给我的申请（待我处理） */
    @Select("""
            SELECT r.user_id     AS user_id,
                   u.username    AS username,
                   u.nickname    AS nickname,
                   u.real_name   AS real_name,
                   u.avatar_key  AS avatar_key,
                   u.role        AS role,
                   u.signature   AS signature,
                   u.class_name  AS class_name,
                   u.status      AS status,
                   r.create_time AS requested_at
            FROM friend_relation r
            JOIN sys_user u ON u.id = r.user_id
            WHERE r.friend_id = #{userId} AND r.status = 0 AND u.deleted = 0
            ORDER BY r.create_time DESC, r.user_id DESC
            """)
    List<FriendRequestRow> selectIncoming(@Param("userId") Long userId);

    /**
     * 按<b>用户 ID 精确</b>找人。
     * <p>
     * <b>为什么只允许按 ID 搜：</b>
     * <ul>
     *   <li><b>没有枚举面。</b> ID 是自增数字，用户必须先从别处（老师给的名单、
     *       对方主页链接的 {@code /user/1002}）拿到确切号码才能查。
     *       按姓名/昵称搜则等于开放"把全校人翻一遍"的能力 ——
     *       {@code LIKE '张%'} 能扫出所有姓张的同学，这正是骚扰的前置条件；</li>
     *   <li><b>查询恒定走主键。</b> {@code WHERE id = ?} 是一次主键点查，
     *       不受数据量影响；前缀匹配在 {@code real_name}/{@code nickname} 上没有索引，
     *       只能全表扫描（见 README §9 关于搜索的已知限制）。</li>
     * </ul>
     * 代价是"得先知道对方 ID"，这在教室场景里是可接受的：名单是公开的，
     * 主页地址里也直接带着 ID。
     * <p>
     * 刻意<b>不过滤自己</b>：搜到自己时返回自己的名片（{@code relation=SELF}），
     * 前端据此显示"这是你自己"。若过滤掉，用户会以为"搜不到 = 没这个人"。
     * <p>已禁用 / 已注销的用户不返回：这是"该用户不可用"的自然表达。
     */
    @Select("""
            SELECT u.id          AS user_id,
                   u.username    AS username,
                   u.nickname    AS nickname,
                   u.real_name   AS real_name,
                   u.avatar_key  AS avatar_key,
                   u.role        AS role,
                   u.signature   AS signature,
                   u.class_name  AS class_name,
                   u.status      AS status
            FROM sys_user u
            WHERE u.id = #{targetId}
              AND u.deleted = 0 AND u.status = 1
            """)
    List<FriendUserRow> searchById(@Param("targetId") Long targetId);

    // ---------------------------------------------------------------- 跨用户清理

    /**
     * 删除某个用户的<b>全部</b>好友边（两个方向都删）。
     * <p>用于删号场景：不清理的话，好友列表里会留下一个永远点不开的用户 ID
     * （对方的 {@code sys_user} 行已逻辑删除，JOIN 不上却被计数，名额被一个
     * 看不见的人占着，用户自己修不了）。
     * <p>本表一律物理删除（理由见 {@code FriendRelation} 的类注释）：
     * {@code uk_edge} 是唯一键，软删除的行会继续占着它。
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM friend_relation "
            + "WHERE user_id = #{userId} OR friend_id = #{userId}")
    int deleteAllEdgesOf(@Param("userId") Long userId);

    /** 把某条边置为"已是好友"，供"接受申请"使用 */
    @Update("UPDATE friend_relation SET status = 1 "
            + "WHERE user_id = #{userId} AND friend_id = #{friendId}")
    int markFriend(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * 好友 / 搜索结果行 —— <b>只给 Mapper 用，不出现在任何接口响应里</b>。
     * <p>
     * ⚠️ <b>MyBatis 的 record 构造器自动映射是按【位置】对齐的</b>：
     * 查询里的第 N 列必须正好是这里的第 N 个组件。<b>少给一列、或多加一个组件，
     * 都会在运行期抛</b> "The constructor takes 'N' arguments, but there are only 'M'
     * columns in the result set" —— 而不是编译期报错。
     * <p>这正是"加好友 500"的真实根因：这个 record 曾经有 11 个组件
     * （多一个只在申请场景用到的 requestedAt 与 friendSince），而好友/搜索查询
     * 只给了 9 列，于是 /friends/search 与 /friends/requests 一调用就 500。
     * <p><b>修法不是"给每处查询补 NULL 列"</b>（那只是把位置对齐的脆弱性藏得更深），
     * 而是<b>按用途拆成两个 record</b>，让每个查询给出的列与它声明的形状天然一致：
     * <ul>
     *   <li>{@link FriendUserRow}：9 列，用于好友列表与搜索结果；</li>
     *   <li>{@link FriendRequestRow}：在它的基础上多一列 requested_at，
     *       用于"我发出的申请 / 别人发来的申请"。</li>
     * </ul>
     * 位置对齐这条纪律由 MapperResultSetContractTest 静态守住。
     * <p>
     * 注意这里带了 class_name（好友之间互相知道班级是合理的），但对外响应会经
     * {@code FriendVo.UserCard} 过滤一遍：邮箱、生日、性别、容量一律不下发，
     * 避免"为了做个好友列表把用户表整行暴露出去"。
     * <p>JOIN 不到 sys_user 时整个对象为 null，调用方必须判空 ——
     * 账号被删掉后残留的关系行会走到这条路径。
     */
    record FriendUserRow(Long userId, String username, String nickname, String realName,
                         String avatarKey, Byte role, String signature, String className,
                         Byte status) {
    }

    /**
     * 带申请时间的行 —— 在 {@link FriendUserRow} 的基础上多一列。
     * <p>单独一个类型，是为了让"查询给了几列"与"类型声明了几个组件"一一对应：
     * 申请类查询确实需要 requested_at，好友列表确实不需要；
     * 用一个"什么都有、缺的补 NULL"的宽类型会让位置对齐变得极易出错。
     */
    record FriendRequestRow(Long userId, String username, String nickname, String realName,
                            String avatarKey, Byte role, String signature, String className,
                            Byte status, java.time.LocalDateTime requestedAt) {
    }
}
