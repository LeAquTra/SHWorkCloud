package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.UploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    @Select("SELECT * FROM upload_session WHERE object_key = #{objectKey} LIMIT 1")
    UploadSession selectByObjectKey(@Param("objectKey") String objectKey);

    @Select("SELECT * FROM upload_session WHERE upload_token = #{uploadToken} LIMIT 1")
    UploadSession selectByUploadToken(@Param("uploadToken") String uploadToken);

    @Update("""
            UPDATE upload_session
            SET status = 1, entry_id = #{entryId}, size = #{size},
                parent_id = #{parentId}, name = #{name}
            WHERE id = #{id}
            """)
    int markCommitted(@Param("id") Long id,
                      @Param("entryId") Long entryId,
                      @Param("size") long size,
                      @Param("parentId") Long parentId,
                      @Param("name") String name);

    @Update("UPDATE upload_session SET status = 2 WHERE id = #{id}")
    int markAbandoned(@Param("id") Long id);

    /** 孤儿扫描：已签发凭证但超过阈值仍未 commit */
    @Select("""
            SELECT * FROM upload_session
            WHERE status = 0 AND create_time < #{before}
            ORDER BY create_time ASC
            LIMIT #{limit}
            """)
    List<UploadSession> selectStalePending(@Param("before") LocalDateTime before,
                                           @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM upload_session WHERE status = 0 AND create_time < #{before}")
    long countStalePending(@Param("before") LocalDateTime before);
}
