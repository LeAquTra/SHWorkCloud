package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.CaptchaImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CaptchaImageMapper extends BaseMapper<CaptchaImage> {

    /**
     * 启用题目的 id 与 weight。
     * <p>只取这两列用于加权随机抽样，避免把答案一起拉进缓存。
     * 刻意不用 {@code ORDER BY weight*RAND()}：那会导致全表扫描且无法命中索引。
     */
    @Select("SELECT id, weight FROM captcha_image WHERE status = 1")
    List<CaptchaImage> selectEnabledWeights();

    @Select("SELECT COUNT(*) FROM captcha_image WHERE status = 1")
    long countEnabled();

    @Select("SELECT COUNT(*) FROM captcha_image")
    long countAll();

    @Update("UPDATE captcha_image SET used_count = used_count + 1 WHERE id = #{id}")
    int incrUsedCount(@Param("id") Long id);

    @Update("UPDATE captcha_image SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") byte status);
}
