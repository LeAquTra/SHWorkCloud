package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.ChatMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 好友私聊消息。
 * <p>
 * <b>性能上的关键约定</b>：所有查询都只做「单列索引上的范围扫描 + LIMIT」，
 * 不做聚合扫描。具体地说，会话列表的"最后一条消息"不用
 * {@code GROUP BY from_user HAVING MAX(id)}（那会扫掉整张表），而是
 * {@code WHERE to_user = ? ORDER BY id DESC LIMIT 1} —— 走
 * {@code idx_to_from_id (to_user, from_user, id)} 直接定位到最后一行再取一条。
 * 55 人的班级里代价可以忽略，但把模式定死，将来消息涨到百万级也不用重写。
 * <p>
 * 接续表用 {@code @MapKey("peerId")} 返回 Map：每会话一次回表，比在 Java 里
 * 遍历全部消息再分组要省内存得多。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    // ---------------------------------------------------------------- 会话列表

    /**
     * 我发出的消息里，每个收件人的最后一条（结果条数 = 我聊过的人数，≤ 50）。
     * <p>用 {@code to_user} 归并：我只可能给好友发消息，没有别的收件人。
     */
    @Select("""
            SELECT m.to_user       AS peer_id,
                   m.content       AS content,
                   m.create_time   AS create_time,
                   m.from_user     AS from_user
            FROM chat_message m
            JOIN (SELECT to_user, MAX(id) AS mid FROM chat_message
                   WHERE from_user = #{userId} GROUP BY to_user) t ON t.mid = m.id
            """)
    @MapKey("peerId")
    Map<Long, LastMessageRow> selectLastSentPerPeer(@Param("userId") Long userId);

    /**
     * 别人发给我的消息里，每个发送者的最后一条。
     * <p>这条走 {@code idx_to_from_id}：先按 {@code to_user} 收窄到"发给我的"，
     * 再按 {@code from_user} 分组取 MAX(id)。数据量 = 我收到的消息数。
     */
    @Select("""
            SELECT m.from_user     AS peer_id,
                   m.content       AS content,
                   m.create_time   AS create_time,
                   m.from_user     AS from_user
            FROM chat_message m
            JOIN (SELECT from_user, MAX(id) AS mid FROM chat_message
                   WHERE to_user = #{userId} GROUP BY from_user) t ON t.mid = m.id
            """)
    @MapKey("peerId")
    Map<Long, LastMessageRow> selectLastReceivedPerPeer(@Param("userId") Long userId);

    /**
     * 每个会话的未读数（别人的消息、我还没读的）。
     * <p>走 {@code idx_to_read_id (to_user, read_flag, id)}：两个等值条件先收窄，
     * 再按 {@code from_user} 分组 —— 分组只发生在"未读消息"这个小集合上，
     * 而不是整张表。
     */
    @Select("""
            SELECT from_user AS peer_id, COUNT(*) AS unread
            FROM chat_message
            WHERE to_user = #{userId} AND read_flag = 0
            GROUP BY from_user
            """)
    @MapKey("peerId")
    Map<Long, UnreadRow> selectUnreadPerPeer(@Param("userId") Long userId);

    // ---------------------------------------------------------------- 单会话

    /**
     * 增量拉取：取比游标更新的消息，按 id 升序（时间顺序）。
     * <p>用 id 而不是时间戳做游标：同一秒内可以有多条消息，按时间比大小会漏。
     */
    @Select("""
            SELECT * FROM chat_message
            WHERE ((from_user = #{me} AND to_user = #{peer})
                OR (from_user = #{peer} AND to_user = #{me}))
              AND id > #{afterId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<ChatMessage> selectSince(@Param("me") Long me, @Param("peer") Long peer,
                                  @Param("afterId") Long afterId, @Param("limit") int limit);

    /**
     * 往回翻历史：取比游标更旧的消息，<b>按 id 降序</b>取一页。
     * <p>刻意不在 SQL 里写 {@code ORDER BY id ASC} 再套子查询：那样必须先扫出
     * 全部更旧的行才能确定"最后 N 条"。降序 + LIMIT 才是"最近 N 条"的正确写法，
     * 返回后由 Service 反转成时间顺序。
     */
    @Select("""
            SELECT * FROM chat_message
            WHERE ((from_user = #{me} AND to_user = #{peer})
                OR (from_user = #{peer} AND to_user = #{me}))
              AND id < #{beforeId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<ChatMessage> selectBefore(@Param("me") Long me, @Param("peer") Long peer,
                                   @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /**
     * 对方已读到的最大消息 id（只算"我发给对方"的那些）。
     * <p>这就是<b>已读回执</b>：前端把 id ≤ 该值的己方气泡标成"已读"。
     * 走 {@code idx_from_to_id}。没有任何已读消息时返回 {@code NULL} → 0。
     */
    @Select("""
            SELECT COALESCE(MAX(id), 0) FROM chat_message
            WHERE from_user = #{me} AND to_user = #{peer} AND read_flag = 1
            """)
    long selectPeerLastReadId(@Param("me") Long me, @Param("peer") Long peer);

    /** 把「对方发给我」的未读消息全部置为已读，返回影响行数 */
    @Update("""
            UPDATE chat_message SET read_flag = 1
            WHERE to_user = #{me} AND from_user = #{peer} AND read_flag = 0
            """)
    int markReadFrom(@Param("me") Long me, @Param("peer") Long peer);

    /** 我的未读总数（顶栏红点用），只走 {@code idx_to_read_id} 的前两列 */
    @Select("SELECT COUNT(*) FROM chat_message WHERE to_user = #{me} AND read_flag = 0")
    long countUnread(@Param("me") Long me);

    // ---------------------------------------------------------------- 跨用户清理

    /**
     * 删除与某用户相关的全部消息（收发的都删）。
     * <p>用于彻底删除账号（{@code purge}）：ChatMessage 没有逻辑删除，
     * 也没有外键，必须显式清理，否则会留下指向不存在用户的孤儿消息。
     */
    @Delete("DELETE FROM chat_message WHERE from_user = #{userId} OR to_user = #{userId}")
    int deleteAllOf(@Param("userId") Long userId);

    /**
     * 会话里的"最后一条消息"原始行（只给 Mapper 用）。
     * <p>{@code peerId} 必须与 SQL 里的 {@code AS peer_id} 对齐 ——
     * {@code @MapKey} 是拿它当 Map 的键来索引结果集的。
     */
    record LastMessageRow(Long peerId, String content, java.time.LocalDateTime createTime,
                          Long fromUser) {
    }

    /** 某会话的未读数（只给 Mapper 用） */
    record UnreadRow(Long peerId, long unread) {
    }
}
