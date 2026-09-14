package com.leaqutra.shworkcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leaqutra.shworkcloud.entity.Announcement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告表。
 * <p>
 * "当前生效的公告"用 MyBatis-Plus 的 LambdaQueryWrapper 在 Service 里拼条件即可
 * （条件不复杂，不值得写 XML）；这里只继承基础 CRUD。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {
}
