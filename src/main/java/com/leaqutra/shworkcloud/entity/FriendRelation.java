package com.leaqutra.shworkcloud.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友关系（有向边）。
 * <p>
 * <b>模型：一行 = 「{@code userId} 的列表里有 {@code friendId} 这个人」。</b>
 * 好友关系存<b>两行</b>（A→B 与 B→A），"互为好友"的定义是两条边的
 * {@code status} 都是 {@link #STATUS_FRIEND}。
 * <p>
 * 为什么不用「一行存一对用户」：那样查"我的好友"必须写
 * {@code WHERE user_id = ? OR friend_id = ?}，<b>OR 用不上索引</b>；而且单行模型
 * 还得额外存"申请是谁发出的"，字段一多就容易写出"一边显示是好友、另一边显示待审核"
 * 的半好友状态。代价是接受申请时要补第二行 —— 换来查询永远命中
 * {@code (user_id, status)} 索引，且两条边天然对称。
 * <p>
 * <b>状态只有两个值</b>，没有"已拒绝"：
 * <ul>
 *   <li>{@link #STATUS_PENDING}：本行是 {@code userId} 发出的申请，等对方接受；</li>
 *   <li>{@link #STATUS_FRIEND}：已是好友。</li>
 * </ul>
 * 拒绝的实现是<b>删掉待处理行</b>，而不是置一个 REJECTED 状态。好处是"拒绝之后
 * 仍可再次申请"，不会留下永久黑名单语义（屏蔽/拉黑不在本期范围）。
 * <p>
 * 🔴 <b>本表没有逻辑删除，删除一律物理删除 —— 这不是图省事，是必须。</b>
 * 表上有 {@code uk_edge(user_id, friend_id)} 唯一键，而 MySQL 不支持条件唯一键
 * （partial index）：一行只要还在表里就占着那个键。若"拒绝申请 / 删除好友"走
 * {@code UPDATE deleted = 1}，接下来重新申请必然撞：
 * <pre>
 *   Duplicate entry '1-2' for key 'uk_edge'  →  用户看到"发送申请失败"（HTTP 500）
 * </pre>
 * 这个故障真实发生过（见 {@code FriendRelationSchemaTest} 的类注释）。
 * 本项目里同类问题还有一处、用了另一套解法：{@code file_entry} 的
 * {@code active_name} 生成列（见 init.sql 注释）—— 那里要保留"回收站"语义，
 * 所以用生成列把非活跃行从唯一键里排除；好友边不需要留痕，直接删更干净。
 * <p>
 * ⚠️ <b>不要给这个类加 {@code @TableLogic}</b>：那会让 {@code deleteById}
 * 退化成软删除，故障立刻复现。回归守卫：
 * {@code FriendRelationSchemaTest.entityMustNotUseLogicalDelete}。
 */
@Data
@TableName("friend_relation")
public class FriendRelation {

    /** 待对方接受（本行由 user_id 发出） */
    public static final int STATUS_PENDING = 0;

    /** 已是好友 */
    public static final int STATUS_FRIEND = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属方：本行属于谁的列表 */
    private Long userId;

    /** 对方用户 ID */
    private Long friendId;

    /** 0 待对方接受 / 1 已是好友 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
