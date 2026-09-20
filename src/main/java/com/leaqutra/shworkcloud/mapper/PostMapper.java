package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 社区帖子。
 * <p>
 * 需要作者昵称/头像的查询都手写 SQL 并显式带上 {@code u.deleted = 0}：
 * {@code @TableLogic} 只对 {@code Post} 自己的行生效，不会穿透到联表进来的用户表，
 * 少了这个条件就会出现"已注销用户的帖子还挂在时间线上、头像点不开"。
 * <p>
 * 时间线用 <b>id 游标</b>而不是页码：社区会不断有新帖插入，
 * {@code page=2} 会因为"前面又多了几条"而重复或漏掉内容（与聊天记录同一个理由）。
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 公开时间线：只含已通过的帖子，按 id 倒序，{@code id < beforeId} 翻页。
     *
     * @param authorId 只看某个作者的帖子；传 null 表示不筛
     */
    @Select("""
            SELECT p.id            AS post_id,
                   p.author_id     AS author_id,
                   p.content       AS content,
                   p.link_count    AS link_count,
                   p.status        AS status,
                   p.reviewed_by   AS reviewed_by,
                   p.review_time   AS review_time,
                   p.reject_reason AS reject_reason,
                   p.create_time   AS create_time,
                   p.update_time   AS update_time,
                   u.username      AS author_username,
                   u.nickname      AS author_nickname,
                   u.real_name     AS author_real_name,
                   u.class_name    AS author_class_name,
                   u.avatar_key    AS author_avatar_key,
                   u.role          AS author_role
            FROM post p
            JOIN sys_user u ON u.id = p.author_id
            WHERE u.deleted = 0 AND u.status = 1
              AND p.status = 1
              AND p.id < #{beforeId}
              AND (#{authorId,jdbcType=BIGINT} IS NULL OR p.author_id = #{authorId,jdbcType=BIGINT})
            ORDER BY p.id DESC
            LIMIT #{limit}
            """)
    List<PostRow> selectFeed(@Param("beforeId") long beforeId,
                             @Param("authorId") Long authorId,
                             @Param("limit") int limit);

    /**
     * 我的帖子：含待审与被拒的，按 id 倒序。
     * <p>不加 {@code u.status = 1} 的过滤：作者自己就该能看到自己帖子的状态，
     * 哪怕账号此刻被禁用（否则他连"为什么我的帖子不见了"都查不出来）。
     */
    @Select("""
            SELECT p.id            AS post_id,
                   p.author_id     AS author_id,
                   p.content       AS content,
                   p.link_count    AS link_count,
                   p.status        AS status,
                   p.reviewed_by   AS reviewed_by,
                   p.review_time   AS review_time,
                   p.reject_reason AS reject_reason,
                   p.create_time   AS create_time,
                   p.update_time   AS update_time,
                   u.username      AS author_username,
                   u.nickname      AS author_nickname,
                   u.real_name     AS author_real_name,
                   u.class_name    AS author_class_name,
                   u.avatar_key    AS author_avatar_key,
                   u.role          AS author_role
            FROM post p
            JOIN sys_user u ON u.id = p.author_id
            WHERE u.deleted = 0
              AND p.author_id = #{authorId}
              AND p.id < #{beforeId}
            ORDER BY p.id DESC
            LIMIT #{limit}
            """)
    List<PostRow> selectMine(@Param("authorId") Long authorId,
                             @Param("beforeId") long beforeId,
                             @Param("limit") int limit);

    /**
     * 审核队列：按状态筛选（不传状态表示全部），按 id 倒序。
     * <p>用 MyBatis-Plus 的 {@code selectPage} 也能做，但那样要再逐条查作者名
     * （N+1）；这里一次 JOIN 出来，列表页的"作者是谁"是审核的核心信息。
     */
    @Select("""
            <script>
            SELECT p.id            AS post_id,
                   p.author_id     AS author_id,
                   p.content       AS content,
                   p.link_count    AS link_count,
                   p.status        AS status,
                   p.reviewed_by   AS reviewed_by,
                   p.review_time   AS review_time,
                   p.reject_reason AS reject_reason,
                   p.create_time   AS create_time,
                   p.update_time   AS update_time,
                   u.username      AS author_username,
                   u.nickname      AS author_nickname,
                   u.real_name     AS author_real_name,
                   u.class_name    AS author_class_name,
                   u.avatar_key    AS author_avatar_key,
                   u.role          AS author_role
            FROM post p
            JOIN sys_user u ON u.id = p.author_id
            WHERE u.deleted = 0
              <if test="status != null">AND p.status = #{status}</if>
            ORDER BY p.id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<PostRow> selectForReview(@Param("status") Integer status,
                                  @Param("offset") long offset,
                                  @Param("limit") long limit);

    /** 审核队列的总数（分页用） */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM post p
            JOIN sys_user u ON u.id = p.author_id
            WHERE u.deleted = 0
              <if test="status != null">AND p.status = #{status}</if>
            </script>
            """)
    long countForReview(@Param("status") Integer status);

    /** 待审核数量（后台导航的角标用） */
    @Select("SELECT COUNT(*) FROM post WHERE status = 0")
    long countPending();

    /** 某作者已通过的帖子数（个人主页的展示用） */
    @Select("SELECT COUNT(*) FROM post WHERE author_id = #{authorId} AND status = 1")
    long countApprovedByAuthor(@Param("authorId") Long authorId);

    /** 某作者某个状态的帖子数（"我的"页签角标） */
    @Select("SELECT COUNT(*) FROM post WHERE author_id = #{authorId} AND status = #{status}")
    long countByAuthorAndStatus(@Param("authorId") Long authorId, @Param("status") int status);

    /**
     * 按 id 取单条（带作者信息），不限状态。
     * <p>可见性判断在 Service 层做：这个方法只负责"把数据取出来"，
     * 它不可能是越权入口 —— 调用方（{@code PostService.mustVisible}）拿不到
     * 可见性许可时会直接抛错。
     */
    @Select("""
            SELECT p.id            AS post_id,
                   p.author_id     AS author_id,
                   p.content       AS content,
                   p.link_count    AS link_count,
                   p.status        AS status,
                   p.reviewed_by   AS reviewed_by,
                   p.review_time   AS review_time,
                   p.reject_reason AS reject_reason,
                   p.create_time   AS create_time,
                   p.update_time   AS update_time,
                   u.username      AS author_username,
                   u.nickname      AS author_nickname,
                   u.real_name     AS author_real_name,
                   u.class_name    AS author_class_name,
                   u.avatar_key    AS author_avatar_key,
                   u.role          AS author_role
            FROM post p
            LEFT JOIN sys_user u ON u.id = p.author_id
            WHERE p.id = #{id}
            """)
    PostRow selectFeedRowById(@Param("id") Long id);

    /** 删除某作者的全部帖子（账号彻底删除时级联） */
    @org.apache.ibatis.annotations.Delete("DELETE FROM post WHERE author_id = #{authorId}")
    int deleteAllOfAuthor(@Param("authorId") Long authorId);

    /**
     * 联表查询的原始行 —— <b>只给 Mapper 用，不出现在任何接口响应里</b>。
     * <p>字段名与 SQL 的下划线别名一一对应，靠全局
     * {@code map-underscore-to-camel-case} 自动映射。
     * <p>作者信息在这里是**反范式地摊平**的（authorUsername / authorNickname / …），
     * 由 {@code UserCardAssembler} 重新装配成收窄过的 {@code UserCard} ——
     * 这样"作者是谁"永远走与好友、聊天同一套字段白名单，
     * 不会因为社区另开一条路而把邮箱之类的字段漏出去。
     */
    record PostRow(Long postId, Long authorId, String content, Integer linkCount, Integer status,
                   Long reviewedBy, java.time.LocalDateTime reviewTime, String rejectReason,
                   java.time.LocalDateTime createTime, java.time.LocalDateTime updateTime,
                   String authorUsername, String authorNickname, String authorRealName,
                   String authorClassName, String authorAvatarKey, Byte authorRole) {
    }
}
