package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.FileEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileEntryMapper extends BaseMapper<FileEntry> {

    /**
     * 同级是否已存在活跃同名记录。
     * <p>注意：这里只用于「快速判断 + 生成候选名」，
     * 真正保证唯一性的是数据库的 {@code uk_sibling_active}，
     * 并发提交时仍必须捕获 DuplicateKeyException。
     */
    @Select("""
            SELECT COUNT(*) FROM file_entry
            WHERE user_id = #{userId} AND parent_id = #{parentId}
              AND status = 1 AND name = #{name}
            """)
    long countActiveSibling(@Param("userId") long userId,
                           @Param("parentId") long parentId,
                           @Param("name") String name);

    /** 按 ObjectKey 查（幂等提交用；唯一索引保证最多一条，文件夹为 NULL 不参与） */
    @Select("SELECT * FROM file_entry WHERE object_key = #{objectKey} LIMIT 1")
    FileEntry selectByObjectKey(@Param("objectKey") String objectKey);

    @Select("SELECT COUNT(*) FROM file_entry WHERE object_key = #{objectKey}")
    long countByObjectKey(@Param("objectKey") String objectKey);

    /** 秒传匹配：仅在本人活跃文件内查找 */
    @Select("""
            SELECT * FROM file_entry
            WHERE user_id = #{userId} AND md5 = #{md5} AND size = #{size} AND status = 1
            LIMIT 1
            """)
    FileEntry findUsableByMd5(@Param("userId") long userId,
                             @Param("md5") String md5,
                             @Param("size") long size);

    /** 子树查询（回收站清理、文件夹统计）：命中 idx_path 前缀 */
    @Select("SELECT * FROM file_entry WHERE user_id = #{userId} AND path LIKE CONCAT(#{pathPrefix}, '%')")
    List<FileEntry> selectByPathPrefix(@Param("userId") long userId,
                                       @Param("pathPrefix") String pathPrefix);

    /**
     * 移动文件夹时同步子孙的物化路径。
     * <p>把以 {@code oldSelfPath} 开头的 path 前缀替换为 {@code newSelfPath}。
     * 例：oldSelfPath=/12/、newSelfPath=/30/12/，
     * 子孙 /12/78/ -> /30/12/78/。
     */
    @Update("""
            UPDATE file_entry
            SET path = CONCAT(#{newSelfPath}, SUBSTRING(path, LENGTH(#{oldSelfPath}) + 1))
            WHERE user_id = #{userId} AND path LIKE CONCAT(#{oldSelfPath}, '%')
            """)
    int updatePathPrefix(@Param("userId") long userId,
                         @Param("oldSelfPath") String oldSelfPath,
                         @Param("newSelfPath") String newSelfPath);

    /** 本人全部文件夹（构建文件夹树用） */
    @Select("""
            SELECT * FROM file_entry
            WHERE user_id = #{userId} AND is_folder = 1 AND status = 1
            ORDER BY name ASC
            """)
    List<FileEntry> selectActiveFolders(@Param("userId") long userId);

    /** 容量权威值：活跃 + 回收站都占容量 */
    @Select("SELECT COALESCE(SUM(size), 0) FROM file_entry WHERE user_id = #{userId} AND status IN (0, 1)")
    long sumUsedSize(@Param("userId") long userId);

    @Select("SELECT COALESCE(SUM(size), 0) FROM file_entry WHERE user_id = #{userId} AND status = 0")
    long sumRecycledSize(@Param("userId") long userId);

    /** 子树中最深的祖先层数（用于移动时的深度校验） */
    @Select("""
            SELECT COALESCE(MAX(LENGTH(path) - LENGTH(REPLACE(path, '/', ''))), 0)
            FROM file_entry
            WHERE user_id = #{userId} AND path LIKE CONCAT(#{pathPrefix}, '%')
            """)
    int maxDepthUnder(@Param("userId") long userId, @Param("pathPrefix") String pathPrefix);

    /** 回收站中超过保留期的记录（分批） */
    @Select("""
            SELECT * FROM file_entry
            WHERE status = 0 AND delete_time < #{before}
            ORDER BY delete_time ASC
            LIMIT #{limit}
            """)
    List<FileEntry> selectExpiredRecycled(@Param("before") LocalDateTime before,
                                         @Param("limit") int limit);

    /** 某用户是否还有未清理的记录（删除用户前提示用） */
    @Select("SELECT COUNT(*) FROM file_entry WHERE user_id = #{userId}")
    long countByUser(@Param("userId") long userId);

    /** 批量更新状态（进回收站 / 还原） */
    @Update("""
            <script>
            UPDATE file_entry
            SET status = #{status}, delete_time = #{deleteTime}
            WHERE user_id = #{userId} AND id IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>
            """)
    int updateStatusBatch(@Param("userId") long userId,
                          @Param("ids") List<Long> ids,
                          @Param("status") int status,
                          @Param("deleteTime") LocalDateTime deleteTime);

    /** 单条更新父目录与名称（移动用） */
    @Update("""
            UPDATE file_entry
            SET parent_id = #{parentId}, name = #{name}, path = #{path}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateParentAndName(@Param("id") Long id,
                           @Param("userId") long userId,
                           @Param("parentId") long parentId,
                           @Param("name") String name,
                           @Param("path") String path);

    /** 只更新物化路径（运维重算用，不触发唯一索引与审计字段） */
    @Update("UPDATE file_entry SET path = #{path} WHERE id = #{id}")
    int updatePathOnly(@Param("id") Long id, @Param("path") String path);
}
