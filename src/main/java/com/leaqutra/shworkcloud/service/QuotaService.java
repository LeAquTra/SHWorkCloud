package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.vo.AuthVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 容量配额。
 * <p>
 * {@code sys_user.used_storage} 是<b>缓存值</b>，权威值是
 * {@code SELECT SUM(size) FROM file_entry WHERE user_id=? AND status IN (0,1)}
 * —— 回收站里的文件同样占容量，因此界面必须提示「清空回收站可释放」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserMapper userMapper;
    private final FileEntryMapper fileEntryMapper;

    public AuthVo.QuotaVo query(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        long quota = user.getStorageQuota() == null ? 0L : user.getStorageQuota();
        long used = user.getUsedStorage() == null ? 0L : user.getUsedStorage();
        long recycled = fileEntryMapper.sumRecycledSize(userId);
        return new AuthVo.QuotaVo(quota, used, Math.max(0L, quota - used), recycled);
    }

    /** 上传前的预检：剩余空间不足直接拒绝，避免学生白传几个 GB */
    public void ensureFree(long userId, long needBytes) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        long quota = user.getStorageQuota() == null ? 0L : user.getStorageQuota();
        long used = user.getUsedStorage() == null ? 0L : user.getUsedStorage();
        if (used + needBytes > quota) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED);
        }
    }

    /**
     * 重算并修正某用户的已用容量。
     *
     * @return 修正前后的差值（正数表示原缓存值偏小）
     */
    @Transactional(rollbackFor = Exception.class)
    public long recalculate(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return 0L;
        }
        long actual = fileEntryMapper.sumUsedSize(userId);
        long cached = user.getUsedStorage() == null ? 0L : user.getUsedStorage();
        long diff = actual - cached;
        if (diff != 0L) {
            userMapper.resetUsedStorage(userId, actual);
            log.warn("容量对账修正 userId={} cached={} actual={} diff={}", userId, cached, actual, diff);
        }
        return diff;
    }
}
