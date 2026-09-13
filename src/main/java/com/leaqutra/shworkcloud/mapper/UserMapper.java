package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    /** 按登录名或学号查（学生登录名即学号，这里兼容两种录入） */
    @Select("""
            SELECT * FROM sys_user
            WHERE deleted = 0 AND (username = #{login} OR student_no = #{login})
            LIMIT 1
            """)
    SysUser selectByLogin(@Param("login") String login);

    @Select("SELECT * FROM sys_user WHERE deleted = 0 AND email = #{email} LIMIT 1")
    SysUser selectByEmail(@Param("email") String email);

    /** 学号唯一性校验用（学号同时也是登录名，不能撞车） */
    @Select("SELECT * FROM sys_user WHERE deleted = 0 AND student_no = #{studentNo} LIMIT 1")
    SysUser selectByStudentNo(@Param("studentNo") String studentNo);

    @Select("SELECT COUNT(*) FROM sys_user WHERE role = 9 AND deleted = 0")
    long countSuperAdmin();

    /**
     * 取用户行并加锁。
     * commit 时用它串行化同一用户的并发提交，防止 used_storage 超额。
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    SysUser selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE sys_user SET used_storage = used_storage + #{delta} WHERE id = #{id}")
    int addUsedStorage(@Param("id") Long id, @Param("delta") long delta);

    /** 容量对账：直接写成权威值 */
    @Update("UPDATE sys_user SET used_storage = #{value} WHERE id = #{id}")
    int resetUsedStorage(@Param("id") Long id, @Param("value") long value);

    @Update("UPDATE sys_user SET status = #{status} WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") byte status);

    @Update("UPDATE sys_user SET storage_quota = #{quota} WHERE id = #{id} AND deleted = 0")
    int updateQuota(@Param("id") Long id, @Param("quota") long quota);

    @Update("UPDATE sys_user SET role = #{role} WHERE id = #{id} AND deleted = 0")
    int updateRole(@Param("id") Long id, @Param("role") byte role);

    /** 重置密码：同时把 pwd_changed 置 0，强制下次登录改密 */
    @Update("UPDATE sys_user SET password = #{password}, pwd_changed = #{pwdChanged}, login_fail_count = 0, locked_until = NULL WHERE id = #{id}")
    int updatePassword(@Param("id") Long id,
                       @Param("password") String password,
                       @Param("pwdChanged") byte pwdChanged);

    @Update("""
            UPDATE sys_user
            SET login_fail_count = login_fail_count + 1,
                locked_until = IF(login_fail_count + 1 >= #{maxFail}, #{lockUntil}, locked_until)
            WHERE id = #{id}
            """)
    int incrLoginFail(@Param("id") Long id,
                      @Param("maxFail") int maxFail,
                      @Param("lockUntil") LocalDateTime lockUntil);

    @Update("""
            UPDATE sys_user
            SET login_fail_count = 0, locked_until = NULL,
                last_login_time = #{loginTime}, last_login_ip = #{ip}
            WHERE id = #{id}
            """)
    int onLoginSuccess(@Param("id") Long id,
                       @Param("loginTime") LocalDateTime loginTime,
                       @Param("ip") String ip);

    /** 更新头像 ObjectKey（null 表示清除头像） */
    @Update("UPDATE sys_user SET avatar_key = #{avatarKey} WHERE id = #{id} AND deleted = 0")
    int updateAvatar(@Param("id") Long id, @Param("avatarKey") String avatarKey);

    /** 名单导入 update 策略：只更新姓名/班级/配额，不动密码与文件 */
    @Update("""
            UPDATE sys_user
            SET real_name = #{realName}, class_name = #{className}, storage_quota = #{quota}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateStudentInfo(@Param("id") Long id,
                          @Param("realName") String realName,
                          @Param("className") String className,
                          @Param("quota") long quota);

    /**
     * 更新个性属性。
     * <p>刻意只列这 5 列而不是 {@code updateById}：后者会把整行（含密码）写回，
     * 一旦实体里有字段没查全就有覆盖风险。
     */
    @Update("""
            UPDATE sys_user
            SET nickname = #{nickname}, signature = #{signature},
                gender = #{gender}, birthday = #{birthday}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("signature") String signature,
                      @Param("gender") Byte gender,
                      @Param("birthday") LocalDate birthday);

    /**
     * 后台代改身份资料。
     * <p>同样刻意只列这几列，不用 {@code updateById}：后者会把整行（含密码）写回。
     * <p>调用方需先把「未提交的字段」用当前值填好再传入，实现部分更新。
     */
    @Update("""
            UPDATE sys_user
            SET real_name = #{realName}, student_no = #{studentNo}, class_name = #{className},
                email = #{email}, nickname = #{nickname}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateAdminProfile(@Param("id") Long id,
                           @Param("realName") String realName,
                           @Param("studentNo") String studentNo,
                           @Param("className") String className,
                           @Param("email") String email,
                           @Param("nickname") String nickname);
}
