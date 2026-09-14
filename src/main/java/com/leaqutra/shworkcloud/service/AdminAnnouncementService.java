package com.leaqutra.shworkcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.dto.AdminDto;
import com.leaqutra.shworkcloud.dto.AnnouncementQuery;
import com.leaqutra.shworkcloud.entity.Announcement;
import com.leaqutra.shworkcloud.mapper.AnnouncementMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.AnnouncementVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 后台公告管理（仅超级管理员）。
 * <p>
 * 状态机刻意做得"啰嗦"一点，只有四条边：
 * <pre>
 *   新建 ──▶ 草稿 ──publish──▶ 已发布 ──recall──▶ 已撤回 ──publish──▶ 已发布
 *                │                  │                │
 *                └──── delete ──────┘                └── delete ──┘
 * </pre>
 * 两条关键约束：
 * <ol>
 *   <li><b>已发布的公告不能直接改/删</b>，必须先 {@code recall} 撤回。
 *       否则"用户看到的公告"会在无人察觉的情况下被改内容 ——
 *       公告是有时间含义的（"今晚 22:00 断网维护"），静默改掉等于篡改历史；</li>
 *   <li><b>撤回只改状态、不删数据</b>，且保留 {@code publishTime}，
 *       事后能查清"谁在什么时候发过什么"。</li>
 * </ol>
 * 每次写操作都记审计日志（{@code ANNOUNCEMENT_*}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAnnouncementService {

    private final AnnouncementMapper announcementMapper;
    private final AuditService auditService;
    private final LoginUser loginUser;

    /** 后台列表：默认按 id 倒序（最新建的在前），状态/分级/关键字可筛 */
    public PageVO<AnnouncementVo.Manage> page(AnnouncementQuery query) {
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<>();
        if (query.getStatus() != null) {
            if (query.getStatus() < Announcement.STATUS_DRAFT
                    || query.getStatus() > Announcement.STATUS_RECALLED) {
                throw new BizException(ErrorCode.BAD_PARAM, "状态只能是 0(草稿) / 1(已发布) / 2(已撤回)");
            }
            wrapper.eq(Announcement::getStatus, query.getStatus());
        }
        if (query.getLevel() != null) {
            wrapper.eq(Announcement::getLevel, AnnouncementRules.normalizeLevel(query.getLevel()));
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(Announcement::getTitle, keyword)
                    .or().like(Announcement::getContent, keyword));
        }
        wrapper.orderByDesc(Announcement::getId);

        Page<Announcement> page = new Page<>(query.normalizedPage(), query.normalizedSize());
        // 只取一次 now：同一页里各条 effective 的判定基准必须一致
        LocalDateTime now = LocalDateTime.now();
        return PageVO.of(announcementMapper.selectPage(page, wrapper), item -> toManage(item, now));
    }

    /** 后台详情 */
    public AnnouncementVo.Manage detail(Long id) {
        return toManage(mustExist(id), LocalDateTime.now());
    }

    /** 新建：一律先落为<b>草稿</b>，"发布"必须是单独一次明确动作 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(AdminDto.AnnouncementUpsertReq req, String clientIp) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少公告数据");
        }
        Announcement entity = new Announcement();
        applyUpsert(entity, req);
        entity.setStatus(Announcement.STATUS_DRAFT);
        entity.setCreatedBy(loginUser.id());
        announcementMapper.insert(entity);

        auditService.log(loginUser.id(), "ANNOUNCEMENT_CREATE", "ANNOUNCEMENT",
                String.valueOf(entity.getId()), clientIp, true, "level=" + entity.getLevel());
        return entity.getId();
    }

    /** 修改：仅草稿/已撤回可改；已发布必须先撤回 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AdminDto.AnnouncementUpsertReq req, String clientIp) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少公告数据");
        }
        Announcement entity = mustExist(id);
        requireEditable(entity, "修改");
        applyUpsert(entity, req);
        announcementMapper.updateById(entity);

        auditService.log(loginUser.id(), "ANNOUNCEMENT_UPDATE", "ANNOUNCEMENT",
                String.valueOf(id), clientIp, true, "level=" + entity.getLevel());
    }

    /** 发布：草稿/已撤回 → 已发布，发布时间取当下 */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id, String clientIp) {
        Announcement entity = mustExist(id);
        if (entity.getStatus() != null && entity.getStatus() == Announcement.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_STATE_INVALID, "该公告已经是发布状态");
        }
        entity.setStatus(Announcement.STATUS_PUBLISHED);
        entity.setPublishTime(LocalDateTime.now());
        announcementMapper.updateById(entity);

        auditService.log(loginUser.id(), "ANNOUNCEMENT_PUBLISH", "ANNOUNCEMENT",
                String.valueOf(id), clientIp, true, "level=" + entity.getLevel());
    }

    /** 撤回：已发布 → 已撤回（保留 publishTime，便于追溯） */
    @Transactional(rollbackFor = Exception.class)
    public void recall(Long id, String clientIp) {
        Announcement entity = mustExist(id);
        if (entity.getStatus() == null || entity.getStatus() != Announcement.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_STATE_INVALID, "只有已发布的公告才能撤回");
        }
        entity.setStatus(Announcement.STATUS_RECALLED);
        announcementMapper.updateById(entity);

        auditService.log(loginUser.id(), "ANNOUNCEMENT_RECALL", "ANNOUNCEMENT",
                String.valueOf(id), clientIp, true, null);
    }

    /**
     * 删除：仅草稿/已撤回可删。
     * <p>物理删除而不是逻辑删除 —— 公告表长不大，且"撤回"已经是软删除语义，
     * 再叠一层 deleted 标志会让所有查询都要多带一个条件，不划算。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String clientIp) {
        Announcement entity = mustExist(id);
        requireEditable(entity, "删除");
        announcementMapper.deleteById(id);

        auditService.log(loginUser.id(), "ANNOUNCEMENT_DELETE", "ANNOUNCEMENT",
                String.valueOf(id), clientIp, true, null);
    }

    // ---------------------------------------------------------------- 内部

    private void applyUpsert(Announcement entity, AdminDto.AnnouncementUpsertReq req) {
        entity.setTitle(AnnouncementRules.normalizeTitle(req.title()));
        entity.setContent(AnnouncementRules.normalizeContent(req.content()));
        entity.setLevel(AnnouncementRules.normalizeLevel(req.level()));
        entity.setExpireTime(AnnouncementRules.normalizeExpireTime(req.expireTime()));
    }

    private void requireEditable(Announcement entity, String action) {
        if (entity.getStatus() != null && entity.getStatus() == Announcement.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_STATE_INVALID,
                    "该公告正在发布中，请先撤回再" + action
                            + "（避免用户看到的公告被静默改动）");
        }
    }

    private Announcement mustExist(Long id) {
        Announcement entity = id == null ? null : announcementMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        return entity;
    }

    private AnnouncementVo.Manage toManage(Announcement item, LocalDateTime now) {
        return new AnnouncementVo.Manage(item.getId(), item.getTitle(), item.getContent(),
                item.getLevel(), item.getStatus(), item.getPublishTime(), item.getExpireTime(),
                item.getCreatedBy(), item.getCreateTime(), item.getUpdateTime(),
                AnnouncementRules.isEffective(item, now));
    }
}
