package com.leaqutra.shworkcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.dto.FileQuery;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.FileVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网盘文件管理：列表、目录树、面包屑、新建/重命名/移动/复制、回收站、下载与预览。
 * <p>
 * 上层规则：
 * <ul>
 *   <li>所有查询都带 userId（来自 Sa-Token 会话），不接受前端传入的 userId；</li>
 *   <li>移动/重命名只改数据库索引，不动 OSS 对象；</li>
 *   <li>删除进回收站时，文件夹的整棵子树一起进去；</li>
 *   <li>彻底删除先删数据库（事务内），OSS 对象在事务提交后异步删，失败由对账任务兜底。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    /** 下载/预览签名 URL 有效期（秒） */
    private static final long SIGN_EXPIRE_SECONDS = 600;
    /** 相册列表里的签名地址有效期（秒）：给长一点，避免滚动浏览时链接失效 */
    private static final long PREVIEW_SIGN_SECONDS = 3600;
    /** 文件列表里图片缩略图的签名地址有效期（秒）：同上，页面停留久了不能变裂图 */
    private static final long THUMBNAIL_SECONDS = 3600;

    private final FileEntryMapper fileEntryMapper;
    private final UserMapper userMapper;
    private final FileTreeSupport support;
    private final OssSignService oss;
    private final TextExtractService textExtractService;
    private final AppProperties appProperties;
    private final LoginUser loginUser;

    public long currentUserId() {
        return loginUser.id();
    }

    // ---------------------------------------------------------------- 列表

    public PageVO<FileVo.FileItemVo> list(FileQuery query) {
        long userId = loginUser.id();
        long parentId = query.getParentId() == null ? 0L : query.getParentId();

        // 访问子目录前先确认父目录归属（根目录 0 无需校验）
        if (parentId != 0L) {
            support.requireFolder(userId, parentId);
        }

        LambdaQueryWrapper<FileEntry> wrapper = new LambdaQueryWrapper<FileEntry>()
                .eq(FileEntry::getUserId, userId)
                .eq(FileEntry::getParentId, parentId)
                .eq(FileEntry::getStatus, FileEntry.STATUS_NORMAL);

        // 分类筛选：文件夹始终显示，只对文件按后缀过滤
        String category = query.getCategory() == null ? "" : query.getCategory().trim().toLowerCase();
        if (!category.isEmpty() && !"all".equals(category)) {
            Set<String> suffixes = FileNaming.suffixesOf(category);
            if (!suffixes.isEmpty()) {
                wrapper.and(w -> w.eq(FileEntry::getIsFolder, 1)
                        .or().in(FileEntry::getSuffix, suffixes));
            } else if ("other".equals(category)) {
                Set<String> known = FileNaming.allKnownSuffixes();
                wrapper.and(w -> w.eq(FileEntry::getIsFolder, 1)
                        .or().and(x -> x.isNull(FileEntry::getSuffix)
                                .or().notIn(FileEntry::getSuffix, known)));
            }
            // 未知分类：忽略筛选（按全部返回），避免用户看到"筛选后空白"却不知为何
        }

        if (StringUtils.hasText(query.getKeyword())) {
            // 前缀匹配才能命中 idx_user_name；全模糊 LIKE '%x%' 会全表扫描
            wrapper.likeRight(FileEntry::getName, query.getKeyword().trim());
        }

        wrapper.orderByDesc(FileEntry::getIsFolder).orderByAsc(FileEntry::getName);

        Page<FileEntry> page = new Page<>(query.normalizedPage(), query.normalizedSize());
        IPage<FileEntry> result = fileEntryMapper.selectPage(page, wrapper);
        return PageVO.of(result, this::toItem);
    }

    private FileVo.FileItemVo toItem(FileEntry entry) {
        // viewType 由服务端判定并下发，前端不必自己维护一份后缀白名单
        String viewType = entry.isFolderEntry()
                ? FileViewType.NONE : FileViewType.of(entry.getSuffix());
        // 只给图片签缩略图地址：列表里要直接显示小图（像头像那样）。
        // 其它类型签了没人用，白白拉长每条记录的响应。
        String previewUrl = FileViewType.IMAGE.equals(viewType)
                ? thumbnailUrl(entry.getObjectKey()) : null;
        return new FileVo.FileItemVo(
                entry.getId(), entry.getName(), entry.isFolderEntry(),
                entry.getSize() == null ? 0L : entry.getSize(),
                entry.getSuffix(), viewType, entry.getContentType(),
                entry.getCreateTime(), entry.getUpdateTime(),
                !FileViewType.NONE.equals(viewType), previewUrl);
    }

    /** 列表缩略图签名地址；单张签名失败只返回 null，不让整个列表 500 */
    private String thumbnailUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            return oss.presignedObjectUrl(objectKey, THUMBNAIL_SECONDS);
        } catch (Exception e) {
            log.warn("列表缩略图签名失败 key={} err={}", objectKey, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ 目录树

    /** 当前用户的文件夹树（移动对话框用） */
    public List<FileVo.FolderNodeVo> tree() {
        long userId = loginUser.id();
        List<FileEntry> folders = fileEntryMapper.selectActiveFolders(userId);

        Map<Long, List<FileEntry>> byParent = new LinkedHashMap<>();
        for (FileEntry folder : folders) {
            byParent.computeIfAbsent(folder.getParentId(), k -> new ArrayList<>()).add(folder);
        }
        return buildNodes(0L, byParent, new HashSet<>());
    }

    private List<FileVo.FolderNodeVo> buildNodes(long parentId, Map<Long, List<FileEntry>> byParent,
                                                Set<Long> visited) {
        List<FileEntry> children = byParent.get(parentId);
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<FileVo.FolderNodeVo> nodes = new ArrayList<>(children.size());
        for (FileEntry child : children) {
            // 防御环状数据（脏数据不应导致栈溢出）
            if (!visited.add(child.getId())) {
                continue;
            }
            nodes.add(new FileVo.FolderNodeVo(child.getId(), child.getName(),
                    buildNodes(child.getId(), byParent, visited)));
        }
        nodes.sort(Comparator.comparing(FileVo.FolderNodeVo::name));
        return nodes;
    }

    /** 面包屑：从根到目标目录 */
    public List<FileVo.BreadcrumbVo> breadcrumb(Long id) {
        long userId = loginUser.id();
        List<FileVo.BreadcrumbVo> chain = new ArrayList<>();
        Long cursor = id;
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ <= FileEntry.MAX_DEPTH + 2) {
            FileEntry entry = support.requireOwned(userId, cursor);
            chain.add(new FileVo.BreadcrumbVo(entry.getId(), entry.getName()));
            cursor = entry.getParentId();
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    // ------------------------------------------------------------ 新建/改名/移动

    @Transactional(rollbackFor = Exception.class)
    public Long createFolder(Long parentId, String name) {
        long userId = loginUser.id();
        long pid = parentId == null ? 0L : parentId;
        String ancestorPath = support.requireParentPath(userId, pid);

        FileEntry folder = new FileEntry();
        folder.setUserId(userId);
        folder.setParentId(pid);
        folder.setIsFolder((byte) 1);
        folder.setSize(0L);
        folder.setStatus((byte) FileEntry.STATUS_NORMAL);
        folder.setPath(ancestorPath);
        // 重名时追加 (1)(2)…，并捕获唯一键冲突重试
        return insertWithUniqueName(folder, pid, name);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rename(Long id, String newName) {
        long userId = loginUser.id();
        FileEntry entry = support.requireActiveOwned(userId, id);
        String resolved = support.resolveUniqueName(userId, entry.getParentId(), newName);
        if (resolved.equals(entry.getName())) {
            return;
        }
        entry.setName(resolved);
        fileEntryMapper.updateById(entry);
    }

    @Transactional(rollbackFor = Exception.class)
    public void move(Long id, Long targetParentId) {
        long userId = loginUser.id();
        FileEntry entry = support.requireActiveOwned(userId, id);
        long targetId = targetParentId == null ? 0L : targetParentId;

        if (targetId == entry.getId()) {
            throw new BizException(ErrorCode.CANNOT_MOVE_INTO_SELF);
        }
        String newAncestorPath = support.requireParentPath(userId, targetId);

        // 不能移动到自身子孙下
        if (entry.isFolderEntry() && FilePaths.isUnder(newAncestorPath, entry.selfPath())) {
            throw new BizException(ErrorCode.CANNOT_MOVE_INTO_SELF);
        }
        support.ensureMoveDepthOk(userId, entry, newAncestorPath);

        String newName = support.resolveUniqueName(userId, targetId, entry.getName());
        String oldSelfPath = entry.selfPath();
        String newSelfPath = newAncestorPath + entry.getId() + "/";

        fileEntryMapper.updateParentAndName(entry.getId(), userId, targetId, newName, newAncestorPath);

        // 文件夹：同一事务内同步子孙物化路径
        if (entry.isFolderEntry() && !oldSelfPath.equals(newSelfPath)) {
            fileEntryMapper.updatePathPrefix(userId, oldSelfPath, newSelfPath);
        }
    }

    /**
     * 复制。文件夹递归复制整棵子树，每个文件都在 OSS 上生成新的 ObjectKey。
     * <p>代价说明：跨用户秒传之外，复制会让 OSS 实际占用翻倍，配额也按逻辑文件累计 —— 这是预期行为。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long copy(Long id, Long targetParentId, String newName) {
        long userId = loginUser.id();
        FileEntry source = support.requireActiveOwned(userId, id);
        long targetId = targetParentId == null ? 0L : targetParentId;
        String ancestorPath = support.requireParentPath(userId, targetId);
        String name = StringUtils.hasText(newName) ? newName : source.getName();
        return copyRecursive(userId, source, targetId, ancestorPath, name);
    }

    private Long copyRecursive(long userId, FileEntry source, long targetParentId,
                               String ancestorPath, String name) {
        FileEntry copy = new FileEntry();
        copy.setUserId(userId);
        copy.setParentId(targetParentId);
        copy.setIsFolder(source.getIsFolder());
        copy.setSize(source.getSize());
        copy.setSuffix(source.getSuffix());
        copy.setContentType(source.getContentType());
        copy.setMd5(source.getMd5());
        copy.setStatus((byte) FileEntry.STATUS_NORMAL);
        copy.setPath(ancestorPath);

        if (!source.isFolderEntry()) {
            String newKey = ObjectKeys.newUserKey(ObjectKeys.userPrefix(null, userId));
            oss.copy(source.getObjectKey(), newKey);
            copy.setObjectKey(newKey);
        }

        Long newId = insertWithUniqueName(copy, targetParentId, name);
        if (source.isFolderEntry()) {
            FileEntry created = fileEntryMapper.selectById(newId);
            for (FileEntry child : support.descendants(userId, source)) {
                copyRecursive(userId, child, newId,
                        created.getPath() + created.getId() + "/", child.getName());
            }
        }
        return newId;
    }

    // ------------------------------------------------------------ 回收站

    /**
     * 删除文件。
     * <p>默认进回收站（可还原，OSS 对象保留）。
     * 若 {@code app.recycle.enabled=false}，则**立即彻底删除索引与 OSS 对象**，不可还原 ——
     * 适合希望 OSS 里不留任何过渡对象的部署。
     */
    @Transactional(rollbackFor = Exception.class)
    public int softDelete(List<Long> ids) {
        long userId = loginUser.id();
        if (!appProperties.getRecycle().isEnabled()) {
            return purgeAs(userId, ids);
        }
        Set<Long> targets = collectWithDescendants(userId, ids, FileEntry.STATUS_NORMAL);
        if (targets.isEmpty()) {
            return 0;
        }
        int rows = fileEntryMapper.updateStatusBatch(userId, new ArrayList<>(targets),
                FileEntry.STATUS_RECYCLED, LocalDateTime.now());
        log.info("进入回收站 userId={} count={}", userId, rows);
        return rows;
    }

    public PageVO<FileVo.FileItemVo> recycleList(long page, long size) {
        long userId = loginUser.id();
        LambdaQueryWrapper<FileEntry> wrapper = new LambdaQueryWrapper<FileEntry>()
                .eq(FileEntry::getUserId, userId)
                .eq(FileEntry::getStatus, FileEntry.STATUS_RECYCLED)
                .orderByDesc(FileEntry::getDeleteTime);
        Page<FileEntry> p = new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200));
        return PageVO.of(fileEntryMapper.selectPage(p, wrapper), this::toItem);
    }

    /**
     * 还原。
     * <p>两件容易被忽略的事：
     * <ol>
     *   <li>如果原父目录还在回收站里，必须把它一并还原，否则还原出来的东西在界面上看不见；</li>
     *   <li>原名可能已被同级新文件占用（v1.1 的唯一索引在这里必现冲突），
     *       因此逐条判断并自动改名。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public int restore(List<Long> ids) {
        long userId = loginUser.id();
        Set<Long> targets = new LinkedHashSet<>();

        for (Long id : ids) {
            FileEntry entry = support.requireOwned(userId, id);
            if (entry.getStatus() != FileEntry.STATUS_RECYCLED) {
                continue;
            }
            targets.add(entry.getId());
            for (FileEntry child : support.descendants(userId, entry)) {
                if (child.getStatus() == FileEntry.STATUS_RECYCLED) {
                    targets.add(child.getId());
                }
            }
            // 向上补齐仍处于回收站的祖先
            Long cursor = entry.getParentId();
            int guard = 0;
            while (cursor != null && cursor != 0L && guard++ <= FileEntry.MAX_DEPTH + 2) {
                FileEntry parent = fileEntryMapper.selectById(cursor);
                if (parent == null || parent.getUserId() == null || parent.getUserId() != userId) {
                    break;
                }
                if (parent.getStatus() == FileEntry.STATUS_RECYCLED) {
                    targets.add(parent.getId());
                }
                cursor = parent.getParentId();
            }
        }

        int restored = 0;
        // 先按路径深度排序：父目录先还原，子项后还原，保证顺序可读且路径有效
        List<FileEntry> ordered = targets.stream()
                .map(fileEntryMapper::selectById)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(e -> FilePaths.depthOf(e.getPath())))
                .toList();

        for (FileEntry entry : ordered) {
            if (entry.getStatus() != FileEntry.STATUS_RECYCLED) {
                continue;
            }
            String name = entry.getName();
            if (support.nameTaken(userId, entry.getParentId(), name)) {
                name = support.resolveUniqueName(userId, entry.getParentId(), name);
            }
            entry.setName(name);
            entry.setStatus((byte) FileEntry.STATUS_NORMAL);
            entry.setDeleteTime(null);
            fileEntryMapper.updateById(entry);
            restored++;
        }
        log.info("回收站还原 userId={} count={}", userId, restored);
        return restored;
    }

    /** 彻底删除：删索引 + 释放容量；OSS 对象在事务提交后删 */
    @Transactional(rollbackFor = Exception.class)
    public int purge(List<Long> ids) {
        return purgeAs(loginUser.id(), ids);
    }

    /**
     * 以指定用户的身份彻底删除。
     * <p>抽出来是为了让定时任务（回收站过期清理）在没有 Sa-Token 会话的情况下复用同一套逻辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeAs(long userId, List<Long> ids) {
        Set<Long> targets = collectWithDescendants(userId, ids, null);
        if (targets.isEmpty()) {
            return 0;
        }
        List<FileEntry> entries = targets.stream()
                .map(fileEntryMapper::selectById)
                .filter(java.util.Objects::nonNull)
                .filter(e -> e.getUserId() != null && e.getUserId() == userId)
                .toList();

        long released = entries.stream()
                .filter(e -> !e.isFolderEntry())
                .mapToLong(e -> e.getSize() == null ? 0L : e.getSize())
                .sum();
        List<String> keys = entries.stream()
                .filter(e -> !e.isFolderEntry())
                .map(FileEntry::getObjectKey)
                .filter(StringUtils::hasText)
                .toList();

        fileEntryMapper.deleteByIds(entries.stream().map(FileEntry::getId).toList());
        if (released > 0) {
            userMapper.addUsedStorage(userId, -released);
        }

        // 关键顺序：先删数据库（事务内），提交后再删 OSS。
        // 反过来做一旦数据库回滚，就会出现「有索引没对象」的坏数据，且无法通过重试修复。
        afterCommit(() -> oss.deleteBatch(keys));
        log.info("彻底删除 userId={} rows={} releasedBytes={}", userId, entries.size(), released);
        return entries.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public int emptyRecycle() {
        long userId = loginUser.id();
        LambdaQueryWrapper<FileEntry> wrapper = new LambdaQueryWrapper<FileEntry>()
                .eq(FileEntry::getUserId, userId)
                .eq(FileEntry::getStatus, FileEntry.STATUS_RECYCLED)
                .select(FileEntry::getId);
        List<Long> ids = fileEntryMapper.selectList(wrapper).stream().map(FileEntry::getId).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return purge(ids);
    }

    // ------------------------------------------------------------ 下载与预览

    public String downloadUrl(Long id) {
        long userId = loginUser.id();
        FileEntry entry = support.requireFile(userId, id);
        return oss.presignedDownloadUrl(entry.getObjectKey(), entry.getName(), SIGN_EXPIRE_SECONDS);
    }

    public String previewUrl(Long id) {
        long userId = loginUser.id();
        FileEntry entry = support.requireFile(userId, id);
        if (!FileNaming.previewable(entry.getSuffix())) {
            // 只允许可安全内联的类型（图片/视频/音频/文本/Office 正文提取）；
            // pdf 已按需求排除，html/svg 也绝不允许内联——否则等于在自己的域名下执行脚本
            throw new BizException(ErrorCode.PREVIEW_NOT_SUPPORTED);
        }
        // 用普通签名 URL（不带 response-* 覆盖）：头像/相册/验证码/缩略图都走这条，
        // 一直是正常的；带覆盖的那条会让浏览器拿到签名不匹配的地址（详见 OssSignService）
        return oss.presignedObjectUrl(entry.getObjectKey(), SIGN_EXPIRE_SECONDS);
    }

    /**
     * 准备服务端流式下载（把文件真正"下载回本地"）。
     * <p>
     * 与 {@link #downloadUrl(Long)} 的区别：签名 URL 是让浏览器直接去 OSS 取；
     * 这里是<strong>服务端把字节流转发给调用方</strong>。好处是：
     * <ul>
     *   <li>调用方（curl / Postman / 自研客户端）不需要能访问 OSS 域名；</li>
     *   <li>可以支持 {@code Range}，从而支持断点续传下载；</li>
     *   <li>下载链接不对外暴露 OSS 的 ObjectKey 与签名。</li>
     * </ul>
     * 代价是流量经过业务服务器，大文件批量下载时要注意带宽。
     *
     * @param rangeHeader 原始 {@code Range} 请求头，可为 null
     */
    public DownloadTarget prepareDownload(Long id, String rangeHeader) {
        long userId = loginUser.id();
        FileEntry entry = support.requireFile(userId, id);
        return openTarget(entry, rangeHeader, null);
    }

    /**
     * 在线阅览：图片 / PDF / 视频 / 音频的流式响应。
     * <p>与下载共用同一套 Range 逻辑，区别有两点：
     * <ul>
     *   <li>响应头带 {@code Content-Disposition: inline}，浏览器直接渲染而不是弹下载框；</li>
     *   <li><b>Content-Type 由服务端按后缀映射</b>，绝不回显上传时客户端声明的类型 ——
     *       否则用户把文件声明成 {@code text/html} 就能在我们的源上执行脚本。</li>
     * </ul>
     * 视频/音频/PDF 支持 {@code Range}，所以 {@code <video>} 可以拖动进度条。
     */
    public DownloadTarget preparePreview(Long id, String rangeHeader) {
        long userId = loginUser.id();
        FileEntry entry = support.requireFile(userId, id);
        String viewType = FileViewType.of(entry.getSuffix());
        if (!FileViewType.streamable(viewType)) {
            throw new BizException(ErrorCode.PREVIEW_NOT_SUPPORTED,
                    FileViewType.extractable(viewType)
                            ? "该文件请用 GET /api/files/" + id + "/text 读取正文"
                            : "该文件类型不支持在线阅览，请下载后查看");
        }
        return openTarget(entry, rangeHeader, FileViewType.inlineContentType(entry.getSuffix()));
    }

    /**
     * 图片管理：跨目录列出本人全部可在线预览的图片（相册视图）。
     * <p>与 {@code GET /files?category=image} 的区别：那个只在<b>当前目录</b>里筛选，
     * 这个是把整个网盘里的图片摊平，按上传时间倒序 —— 适合"找那张图"的场景。
     */
    public PageVO<FileVo.ImageItemVo> listImages(long page, long size) {
        long userId = loginUser.id();
        LambdaQueryWrapper<FileEntry> wrapper = new LambdaQueryWrapper<FileEntry>()
                .eq(FileEntry::getUserId, userId)
                .eq(FileEntry::getIsFolder, 0)
                .eq(FileEntry::getStatus, FileEntry.STATUS_NORMAL)
                .in(FileEntry::getSuffix, FileViewType.gallerySuffixes())
                .orderByDesc(FileEntry::getCreateTime);
        Page<FileEntry> p = new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200));
        return PageVO.of(fileEntryMapper.selectPage(p, wrapper), this::toImageItem);
    }

    private FileVo.ImageItemVo toImageItem(FileEntry entry) {
        // 相册里每张图都直接给签名地址，避免前端为每张图再发一次请求。
        // ⚠️ 必须用普通签名（不带 response-* 覆盖），否则相册也是"裂图 + 文件名"，
        // 详见 OssSignService#presignedObjectUrl 上记录的踩坑经过。
        String signed = StringUtils.hasText(entry.getObjectKey())
                ? oss.presignedObjectUrl(entry.getObjectKey(), PREVIEW_SIGN_SECONDS)
                : null;
        return new FileVo.ImageItemVo(entry.getId(), entry.getName(), entry.getSuffix(),
                entry.getSize() == null ? 0L : entry.getSize(), entry.getParentId(),
                FileViewType.IMAGE, signed, "/api/files/" + entry.getId() + "/preview",
                entry.getCreateTime(), entry.getUpdateTime());
    }

    /**
     * 在线阅览 docx / pptx 里内嵌的图片。
     * <p>
     * 正文提取（{@link #extractText}）会把 XML 标签连图片一起剥掉，
     * 所以图文作业只看正文接口是"只有字、没有图"。这里把 zip 里
     * {@code word/media/}（pptx 为 {@code ppt/media/}）的位图取出来，
     * 以 data URL 返回给前端直接渲染。
     * <p>非 Office 文件返回空列表（不是错误：前端对任何类型调用都不会炸）。
     */
    public FileVo.EmbeddedImagesVo extractEmbeddedImages(Long id) {
        long userId = loginUser.id();
        FileEntry entry = support.requireFile(userId, id);
        String suffix = entry.getSuffix();
        if (!FileViewType.OFFICE.equals(FileViewType.of(suffix))) {
            return new FileVo.EmbeddedImagesVo(List.of(), 0);
        }
        byte[] bytes = oss.readAll(entry.getObjectKey(), TextExtractService.MAX_OFFICE_BYTES);
        TextExtractService.EmbeddedImages extracted =
                textExtractService.extractEmbeddedImages(suffix, bytes);
        List<FileVo.EmbeddedImageVo> images = extracted.images().stream()
                .map(image -> new FileVo.EmbeddedImageVo(
                        image.name(), image.contentType(), image.size(), image.dataUrl()))
                .toList();
        return new FileVo.EmbeddedImagesVo(images, extracted.skipped());
    }

    /**
     * 在线阅览文本 / Office 正文。
     * <p>文本类会自动处理 GBK 与 UTF-8 BOM；doc/docx/pptx/xlsx 会提取纯文本（排版会丢失），
     * docx / xlsx 还会带一份服务端渲染的结构化 {@code html}（近似原格式，非像素级还原）。
     * 超过上限的文件拒绝在线阅览并提示下载。
     */
    public FileVo.TextContentVo extractText(Long id) {
        long userId = loginUser.id();
        FileEntry entry = support.requireFile(userId, id);
        String suffix = entry.getSuffix();
        String viewType = FileViewType.of(suffix);
        if (!FileViewType.extractable(viewType)) {
            throw new BizException(ErrorCode.PREVIEW_NOT_SUPPORTED,
                    FileViewType.streamable(viewType)
                            ? "该文件请用 GET /api/files/" + id + "/preview 流式阅览"
                            : "该文件类型不支持在线阅览，请下载后查看");
        }
        long limit = FileViewType.TEXT.equals(viewType)
                ? TextExtractService.MAX_TEXT_BYTES : TextExtractService.MAX_OFFICE_BYTES;
        byte[] bytes = oss.readAll(entry.getObjectKey(), limit);
        TextExtractService.Extracted extracted = textExtractService.extract(suffix, bytes);
        return new FileVo.TextContentVo(entry.getId(), entry.getName(), suffix, viewType,
                entry.getSize() == null ? bytes.length : entry.getSize(),
                extracted.charset(), extracted.content(), renderHtml(suffix, bytes),
                extracted.truncated(),
                TextExtractService.MAX_CHARS, extracted.hint());
    }

    /**
     * 为 docx / xlsx 额外渲染一份结构化 HTML，用于「原格式」在线阅览。
     * <p>
     * 刻意<b>不影响</b>纯文本结果：渲染失败（结构异常、被 XXE 防护拦下等）只返回 null，
     * 前端会自动回退到纯文本视图 —— 看不了"原格式"总比整页打不开好。
     */
    private String renderHtml(String suffix, byte[] bytes) {
        String normalized = suffix == null ? "" : suffix.trim().toLowerCase();
        try {
            if ("docx".equals(normalized)) {
                return OfficeHtmlService.docxToHtml(bytes);
            }
            if ("xlsx".equals(normalized)) {
                return OfficeHtmlService.xlsxToHtml(bytes);
            }
        } catch (Exception e) {
            log.warn("Office 原格式渲染失败 suffix={} err={}", normalized, e.getMessage());
        }
        return null;
    }

    /**
     * Range 解析 + 打开 OSS 流的公共实现。
     *
     * @param forcedContentType 非空则用它作为响应 Content-Type（预览场景由服务端映射）
     */
    private DownloadTarget openTarget(FileEntry entry, String rangeHeader, String forcedContentType) {
        String objectKey = entry.getObjectKey();
        if (!StringUtils.hasText(objectKey)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        boolean hasRange = StringUtils.hasText(rangeHeader);
        long totalSize = entry.getSize() == null ? 0L : entry.getSize();

        // 有 Range 时先 HEAD 一次拿权威总长度：分片响应里只有分片长度，用它算 Content-Range 会错
        if (hasRange) {
            try {
                totalSize = oss.metadata(objectKey).getContentLength();
            } catch (Exception e) {
                throw new BizException(ErrorCode.OSS_OBJECT_NOT_FOUND);
            }
        }

        HttpRange.ByteRange range = hasRange ? HttpRange.parse(rangeHeader, totalSize) : null;
        OSSObject object = range == null
                ? oss.openStream(objectKey, null, null)
                : oss.openStream(objectKey, range.start(), range.end());

        ObjectMetadata meta = object.getObjectMetadata();
        if (range == null) {
            // 无 Range：以 OSS 实际长度为准（顺带修正 DB 里可能过期的 size）
            totalSize = meta.getContentLength();
        }

        String contentType = StringUtils.hasText(forcedContentType)
                ? forcedContentType
                : (StringUtils.hasText(meta.getContentType())
                        ? meta.getContentType()
                        : (StringUtils.hasText(entry.getContentType())
                                ? entry.getContentType() : "application/octet-stream"));

        long start = range == null ? 0L : range.start();
        long end = range == null ? Math.max(0L, totalSize - 1) : range.end();

        return new DownloadTarget(entry.getName(), contentType, totalSize,
                start, end, range != null, object.getObjectContent(), object);
    }

    // ------------------------------------------------------------ 内部工具

    /**
     * 收集「选中项 + 其子树」的 id 集合。
     *
     * @param onlyStatus 只收集该状态的记录；null 表示不限状态
     */
    private Set<Long> collectWithDescendants(long userId, List<Long> ids, Integer onlyStatus) {
        Set<Long> targets = new LinkedHashSet<>();
        if (ids == null) {
            return targets;
        }
        for (Long id : ids) {
            FileEntry entry = support.requireOwned(userId, id);
            if (onlyStatus != null && !statusEquals(entry, onlyStatus)) {
                continue;
            }
            targets.add(entry.getId());
            for (FileEntry child : support.descendants(userId, entry)) {
                if (onlyStatus == null || statusEquals(child, onlyStatus)) {
                    targets.add(child.getId());
                }
            }
        }
        return targets;
    }

    /**
     * 插入并保证同级唯一：先尝试解析出的名字，撞唯一索引则换下一个候选名重试。
     * <p>「先查后插」在并发下必然有竞态，唯一索引才是最终防线。
     */
    private Long insertWithUniqueName(FileEntry entity, long parentId, String rawName) {
        String name = support.resolveUniqueName(entity.getUserId(), parentId, rawName);
        entity.setName(name);
        entity.setSuffix(entity.isFolderEntry() ? null : FileNaming.extension(name));
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                fileEntryMapper.insert(entity);
                return entity.getId();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发下被别人抢先占用：换一个候选名重试
                String candidate = FileNaming.appendIndex(name, attempt);
                log.debug("同级重名，重试候选名 {} -> {}", name, candidate);
                entity.setId(null);
                entity.setName(candidate);
                entity.setSuffix(entity.isFolderEntry() ? null : FileNaming.extension(candidate));
            }
        }
        throw new BizException(ErrorCode.NAME_CONFLICT);
    }

    /** Byte 与 Integer 之间不能直接用 == 比较（Java 不会对两个包装类做拆箱比较） */
    private boolean statusEquals(FileEntry entry, int status) {
        return entry.getStatus() != null && entry.getStatus() == status;
    }

    /** 把动作推迟到事务提交之后执行；无事务时立即执行 */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
