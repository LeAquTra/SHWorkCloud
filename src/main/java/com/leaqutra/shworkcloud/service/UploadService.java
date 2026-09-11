package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.config.OssProperties;
import com.leaqutra.shworkcloud.dto.FileDto;
import com.leaqutra.shworkcloud.dto.UploadTokenPayload;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.entity.UploadSession;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.mapper.UploadSessionMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.FileVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 上传落库：直传完成后的 commit，以及 MD5 秒传。
 * <p>
 * 这里是整个项目数据一致性的关键点，三条硬规则：
 * <ol>
 *   <li><b>ObjectKey 以服务端为准</b>：从 uploadToken 对应的服务端记录取回，
 *       前端传什么都忽略，因此前端无法自造/复用 Key；</li>
 *   <li><b>size 以 OSS 为准</b>：用 headObject 的 ContentLength，
 *       前端伪造 size 骗配额的路子被堵死；</li>
 *   <li><b>幂等</b>：先按 object_key 查已有索引，命中直接返回；
 *       并发场景由 uk_object_key 唯一索引兜底，捕获 DuplicateKeyException 后返回既有记录。
 *       没有这两层，一次网络重试就会让文件索引和已用容量翻倍。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private static final DateTimeFormatter RECEIPT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter READABLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OssSignService ossSignService;
    private final OssProperties ossProperties;
    private final AppProperties appProperties;
    private final FileEntryMapper fileEntryMapper;
    private final UserMapper userMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final UploadTokenService uploadTokenService;
    private final FileTreeSupport support;
    private final LoginUser loginUser;

    // ---------------------------------------------------------------- commit

    /** 浏览器直传 OSS 成功后回调：校验对象 + 建索引 + 加容量。幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public FileVo.CommitVo commit(FileDto.CommitReq req) {
        long userId = loginUser.id();

        // 1) 取回服务端签发的 uploadKey（忽略前端传值）
        UploadTokenPayload payload = uploadTokenService.read(req.uploadToken(), userId);
        String uploadKey = payload.getUploadKey();

        // 2) 前缀校验：必须是本人前缀下（双保险，正常不可能不满足）
        String expectPrefix = ossProperties.userPrefix(userId);
        if (!uploadKey.startsWith(expectPrefix)) {
            throw new BizException(ErrorCode.ILLEGAL_OBJECT_KEY);
        }

        UploadSession session = uploadSessionMapper.selectByUploadToken(req.uploadToken());
        FileVo.CommitVo result = doCommit(userId, uploadKey, req.parentId(), req.name(),
                req.contentType(), req.md5(), session);

        // 3) 消费一次性令牌
        uploadTokenService.consume(req.uploadToken());
        return result;
    }

    // ---------------------------------------------------------------- 秒传

    /**
     * 尝试秒传：命中的话由服务端在 OSS 内 CopyObject 生成新对象并直接建索引。
     * <p>匹配范围仅限当前用户自己的活跃文件，不跨用户，保护隐私。
     */
    @Transactional(rollbackFor = Exception.class)
    public FileVo.CommitVo instantUpload(FileDto.InstantUploadReq req) {
        long userId = loginUser.id();
        if (!StringUtils.hasText(req.md5())) {
            // 没有指纹就不做秒传，让前端走正常直传
            return FileVo.CommitVo.miss();
        }
        FileEntry source = fileEntryMapper.findUsableByMd5(userId, req.md5(), req.size());
        if (source == null) {
            return FileVo.CommitVo.miss();
        }

        String newKey = ObjectKeys.newUserKey(ossProperties.userPrefix(userId));

        // 先落 upload_session（PENDING）：即使后面的建索引失败，
        // 对账任务也能发现这个"已复制但无索引"的孤儿对象并回收。
        UploadSession session = new UploadSession();
        session.setUserId(userId);
        session.setObjectKey(newKey);
        session.setUploadToken("instant-" + ObjectKeys.uuid32());
        session.setStatus((byte) UploadSession.STATUS_PENDING);
        session.setSize(source.getSize() == null ? 0L : source.getSize());
        uploadSessionMapper.insert(session);

        // OSS 远程复制：注意它不受数据库事务保护，回滚不会撤销复制
        ossSignService.copy(source.getObjectKey(), newKey);

        return doCommit(userId, newKey, req.parentId(), req.name(),
                req.contentType() != null ? req.contentType() : source.getContentType(),
                source.getMd5(), session);
    }

    // ------------------------------------------------------------ 建索引核心

    private FileVo.CommitVo doCommit(long userId, String uploadKey, Long parentIdRaw, String rawName,
                                    String contentType, String md5, UploadSession session) {
        // --- 幂等第一步：该 ObjectKey 是否已经有索引 ---
        FileEntry existing = fileEntryMapper.selectByObjectKey(uploadKey);
        if (existing != null) {
            log.info("commit 幂等命中 userId={} objectKey={} fileId={}", userId, uploadKey, existing.getId());
            markSessionCommitted(session, existing);
            return toCommitVo(existing, true);
        }

        // --- 以 OSS 真实大小为准 ---
        long realSize = ossSignService.sizeOf(uploadKey);
        long maxFileSize = appProperties.getUpload().getMaxFileSizeBytes();
        if (maxFileSize > 0 && realSize > maxFileSize) {
            // 对象已上传，标记会话待回收（由孤儿任务删掉），不建索引、不占配额
            if (session != null) {
                uploadSessionMapper.markAbandoned(session.getId());
            }
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }

        // --- 父目录校验与深度限制 ---
        long parentId = parentIdRaw == null ? 0L : parentIdRaw;
        String ancestorPath = support.requireParentPath(userId, parentId);

        // --- 容量校验（行锁，串行化同一用户的并发提交） ---
        SysUser user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        long quota = user.getStorageQuota() == null ? 0L : user.getStorageQuota();
        long used = user.getUsedStorage() == null ? 0L : user.getUsedStorage();
        if (used + realSize > quota) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED);
        }

        // --- 同级重名解析 ---
        String name = support.resolveUniqueName(userId, parentId, rawName);
        boolean renamed = !name.equals(FileNaming.sanitize(rawName));

        FileEntry entry = new FileEntry();
        entry.setUserId(userId);
        entry.setParentId(parentId);
        entry.setName(name);
        entry.setIsFolder((byte) 0);
        entry.setObjectKey(uploadKey);
        entry.setSize(realSize);
        entry.setSuffix(FileNaming.extension(name));
        entry.setContentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
        entry.setMd5(StringUtils.hasText(md5) ? md5 : null);
        entry.setStatus((byte) FileEntry.STATUS_NORMAL);
        entry.setPath(ancestorPath);

        try {
            fileEntryMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // --- 幂等第二步：并发重复提交（唯一索引兜底） ---
            FileEntry raced = fileEntryMapper.selectByObjectKey(uploadKey);
            if (raced != null) {
                log.info("commit 并发幂等命中 userId={} objectKey={}", userId, uploadKey);
                markSessionCommitted(session, raced);
                return toCommitVo(raced, true);
            }
            // 同级同名的竞态：换名重试一次
            String retryName = support.resolveUniqueName(userId, parentId, rawName);
            entry.setId(null);
            entry.setName(retryName);
            entry.setSuffix(FileNaming.extension(retryName));
            fileEntryMapper.insert(entry);
            renamed = true;
        }

        userMapper.addUsedStorage(userId, realSize);
        markSessionCommitted(session, entry);
        log.info("commit 成功 userId={} fileId={} size={} name={}", userId, entry.getId(), realSize, name);
        return toCommitVo(entry, false, renamed);
    }

    private void markSessionCommitted(UploadSession session, FileEntry entry) {
        if (session == null) {
            return;
        }
        uploadSessionMapper.markCommitted(session.getId(), entry.getId(),
                entry.getSize() == null ? 0L : entry.getSize(),
                entry.getParentId(), entry.getName());
    }

    private FileVo.CommitVo toCommitVo(FileEntry entry, boolean duplicated) {
        return toCommitVo(entry, duplicated, false);
    }

    private FileVo.CommitVo toCommitVo(FileEntry entry, boolean duplicated, boolean renamed) {
        long size = entry.getSize() == null ? 0L : entry.getSize();
        LocalDateTime time = entry.getCreateTime() == null ? LocalDateTime.now() : entry.getCreateTime();
        return new FileVo.CommitVo(entry.getId(), entry.getName(), size,
                receiptOf(entry), READABLE_TIME.format(time),
                renamed, duplicated, true);
    }

    /** 提交凭证：便于学生/教师核对「这个文件真的存上去了」（文档 §3.6） */
    private String receiptOf(FileEntry entry) {
        LocalDateTime time = entry.getCreateTime() == null ? LocalDateTime.now() : entry.getCreateTime();
        return RECEIPT_TIME.format(time) + "-" + Long.toHexString(entry.getId() == null ? 0L : entry.getId());
    }
}
