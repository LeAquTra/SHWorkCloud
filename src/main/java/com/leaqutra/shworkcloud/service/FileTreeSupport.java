package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 文件树的公共校验与命名逻辑。
 * <p>
 * FileService（列表/移动/删除）与 UploadService（commit/秒传）都要用到这些规则，
 * 集中在一处避免「两套重名解析逻辑」这种典型的漏洞来源。
 */
@Component
@RequiredArgsConstructor
public class FileTreeSupport {

    /** 重名时最多尝试追加 (1)..(999) */
    private static final int MAX_NAME_ATTEMPT = 1000;

    private final FileEntryMapper fileEntryMapper;

    // ------------------------------------------------------------ 归属校验

    /** 取一条属于该用户的记录（不限状态）；不存在或非本人一律按「不存在」处理，避免探测 */
    public FileEntry requireOwned(long userId, Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        FileEntry entry = fileEntryMapper.selectById(id);
        if (entry == null || entry.getUserId() == null || entry.getUserId() != userId) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return entry;
    }

    public FileEntry requireActiveOwned(long userId, Long id) {
        FileEntry entry = requireOwned(userId, id);
        if (entry.getStatus() == null || entry.getStatus() != FileEntry.STATUS_NORMAL) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return entry;
    }

    public FileEntry requireFolder(long userId, Long id) {
        FileEntry entry = requireActiveOwned(userId, id);
        if (!entry.isFolderEntry()) {
            throw new BizException(ErrorCode.NOT_A_FOLDER);
        }
        return entry;
    }

    public FileEntry requireFile(long userId, Long id) {
        FileEntry entry = requireActiveOwned(userId, id);
        if (entry.isFolderEntry()) {
            throw new BizException(ErrorCode.NOT_A_FILE);
        }
        return entry;
    }

    // ------------------------------------------------------------ 路径与深度

    /**
     * 校验父目录并返回它的物化路径（即子项的祖先路径）。
     *
     * @param parentId 0 表示根目录
     */
    public String requireParentPath(long userId, Long parentId) {
        long pid = parentId == null ? 0L : parentId;
        if (pid == 0L) {
            return FilePaths.root();
        }
        FileEntry parent = requireFolder(userId, pid);
        // 父目录自身深度 = 祖先层数 + 1；达到上限则不允许再建子项
        int parentDepth = FilePaths.depthOf(parent.getPath()) + 1;
        if (parentDepth >= FileEntry.MAX_DEPTH) {
            throw new BizException(ErrorCode.DEPTH_EXCEEDED);
        }
        return parent.getPath() + parent.getId() + "/";
    }

    /**
     * 移动文件夹时校验新位置的深度是否会超限。
     *
     * @param entry           被移动的文件夹
     * @param newAncestorPath 新父目录的物化路径
     */
    public void ensureMoveDepthOk(long userId, FileEntry entry, String newAncestorPath) {
        int newAncestorDepth = FilePaths.depthOf(newAncestorPath);
        int newSelfDepth = newAncestorDepth + 1;
        if (newSelfDepth > FileEntry.MAX_DEPTH) {
            throw new BizException(ErrorCode.DEPTH_EXCEEDED);
        }
        if (!entry.isFolderEntry()) {
            return;
        }
        // 子孙最深处：maxDepthUnder 返回子孙 path 中最大的祖先层数
        int maxAncestorDepthUnder = fileEntryMapper.maxDepthUnder(userId, entry.selfPath());
        if (maxAncestorDepthUnder <= 0) {
            return;
        }
        int oldSelfAncestorDepth = FilePaths.depthOf(entry.getPath()) + 1;
        int relativeBelow = Math.max(0, maxAncestorDepthUnder - oldSelfAncestorDepth);
        int deepest = newAncestorDepth + 2 + relativeBelow;
        if (deepest > FileEntry.MAX_DEPTH) {
            throw new BizException(ErrorCode.DEPTH_EXCEEDED,
                    "移动后子目录深度将达到 " + deepest + " 层，超过上限 " + FileEntry.MAX_DEPTH);
        }
    }

    // ------------------------------------------------------------ 同级重名

    /**
     * 解析出可用的名字：若同级已有活跃同名记录，则追加 (1)、(2)…
     * <p>
     * 这只是"生成候选名"；并发场景下仍可能撞上唯一索引，
     * 调用方必须捕获 DuplicateKeyException 后重试。
     */
    public String resolveUniqueName(long userId, long parentId, String rawName) {
        String name = FileNaming.sanitize(rawName);
        if (fileEntryMapper.countActiveSibling(userId, parentId, name) == 0) {
            return name;
        }
        for (int i = 1; i < MAX_NAME_ATTEMPT; i++) {
            String candidate = FileNaming.appendIndex(name, i);
            if (fileEntryMapper.countActiveSibling(userId, parentId, candidate) == 0) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.NAME_CONFLICT);
    }

    /** 名字是否被同级活跃记录占用（用于还原时判断是否需要改名） */
    public boolean nameTaken(long userId, long parentId, String name) {
        return StringUtils.hasText(name)
                && fileEntryMapper.countActiveSibling(userId, parentId, name) > 0;
    }

    // ------------------------------------------------------------ 子树

    /** 某文件夹的全部子孙（不含自身） */
    public List<FileEntry> descendants(long userId, FileEntry folder) {
        if (folder == null || !folder.isFolderEntry()) {
            return List.of();
        }
        return fileEntryMapper.selectByPathPrefix(userId, folder.selfPath());
    }
}
