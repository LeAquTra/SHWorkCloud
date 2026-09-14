package com.leaqutra.shworkcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leaqutra.shworkcloud.entity.Announcement;
import com.leaqutra.shworkcloud.mapper.AnnouncementMapper;
import com.leaqutra.shworkcloud.vo.AnnouncementVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 用户端公告读取。
 * <p>
 * 只读接口，任何已登录用户都能拿；后台的增删改发在 {@link AdminAnnouncementService}。
 * <p>
 * 排序规则：<b>先按注意力分级降序</b>（紧急排最前），同级再按发布时间倒序 ——
 * 这样前端即使只展示第一条，展示的也是"最该被看到的那条"。
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    /**
     * 一次最多下发多少条。
     * <p>公告是"打扰"，不是信息流：一次给太多反而没人看。超出部分在后台仍可查。
     */
    private static final int MAX_ACTIVE = 20;

    private final AnnouncementMapper announcementMapper;

    /** 当前生效的公告（已发布、已到时间、未过期），按分级与时间排序 */
    public List<AnnouncementVo.Active> active() {
        LocalDateTime now = LocalDateTime.now();
        // 数据库侧先粗筛：状态已发布 + 发布时间已到（publish_time 为空的按"立即生效"处理）
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, Announcement.STATUS_PUBLISHED)
                .and(w -> w.isNull(Announcement::getPublishTime)
                        .or().le(Announcement::getPublishTime, now))
                .and(w -> w.isNull(Announcement::getExpireTime)
                        .or().gt(Announcement::getExpireTime, now));
        List<Announcement> rows = announcementMapper.selectList(wrapper);

        return rows.stream()
                // 再用同一份规则复核一次：数据库条件与 Java 判定必须一致，
                // 避免"SQL 写法与 isEffective 语义漂移"导致下发了不该发的公告
                .filter(item -> AnnouncementRules.isEffective(item, now))
                .sorted(Comparator
                        .comparingInt((Announcement item) -> item.getLevel() == null
                                ? 0 : item.getLevel()).reversed()
                        .thenComparing(Announcement::getPublishTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Announcement::getId, Comparator.reverseOrder()))
                .limit(MAX_ACTIVE)
                .map(item -> new AnnouncementVo.Active(item.getId(), item.getTitle(),
                        item.getContent(), item.getLevel(), item.getPublishTime(),
                        item.getExpireTime()))
                .toList();
    }
}
