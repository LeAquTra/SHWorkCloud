package com.leaqutra.shworkcloud.service;

import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 自定义头像。
 * <p>
 * 规则（需求）：仅 JPG / PNG，≤ 5MB，存阿里云 OSS。
 * <p>
 * <b>「换头像删旧文件」的落地方式</b>：每次上传都用新的 UUID Key，
 * 顺序是「先上传新对象 → 再更新数据库 → 最后删旧对象」。
 * 这样任何一步失败都只会留下<b>可被对账任务识别的孤儿</b>，
 * 而不会出现「数据库指向已被删除的对象」这种用户可见的坏数据。
 * <p>
 * 头像不计入用户存储配额：单张 ≤5MB、每人只留一张，计入反而会让
 * 「容量对账」在头像与网盘文件之间纠缠不清。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    /** 头像签名 URL 有效期（秒）。一天内一般够用，客户端可重新拉资料刷新 */
    public static final long SIGN_EXPIRE_SECONDS = 3600;

    private final OssSignService ossSignService;
    private final UserMapper userMapper;
    private final LoginUser loginUser;
    private final AuditService auditService;

    /**
     * 上传/更换头像。
     *
     * @return 新的 ObjectKey
     */
    @Transactional(rollbackFor = Exception.class)
    public String upload(MultipartFile file, String clientIp) {
        long userId = loginUser.id();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "请选择头像图片");
        }
        AvatarRules.validateSize(file.getSize());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_PARAM, "读取上传文件失败");
        }

        // 不信任文件名后缀与 Content-Type：用 magic bytes + ImageIO 真正解析一次
        AvatarRules.ImageInfo info = AvatarRules.inspect(bytes);

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        // 冷却检查必须放在写 OSS 之前：否则刷子即便被拒，对象也已经落进桶里了
        AvatarRules.ensureChangeAllowed(user.getAvatarUpdatedAt());
        String oldKey = user.getAvatarKey();

        // 1) 先上传新对象（用新 UUID，不复用旧 Key）
        String newKey = ObjectKeys.newAvatarKey(userId, info.extension());
        ossSignService.put(newKey, bytes, info.contentType());

        // 2) 再更新数据库，让引用指向新对象
        userMapper.updateAvatar(userId, newKey);

        // 3) 最后删旧对象。删除失败不影响本次上传成功：
        //    旧对象会变成孤儿，由 OSS 对账任务回收（见 OssReconcileJob）
        deleteQuietly(oldKey, newKey);

        auditService.log(userId, "AVATAR_UPDATE", "USER", String.valueOf(userId), clientIp, true,
                "format=%s,size=%d,dim=%dx%d".formatted(info.format(), bytes.length,
                        info.width(), info.height()));
        return newKey;
    }

    /** 清除头像（同时删除 OSS 对象） */
    @Transactional(rollbackFor = Exception.class)
    public void clear(String clientIp) {
        long userId = loginUser.id();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        // 清除同样受 24 小时冷却约束（upload 与 clear 共用同一个时间戳），
        // 否则"换了立刻清掉、再换"就能无限刷
        AvatarRules.ensureChangeAllowed(user.getAvatarUpdatedAt());
        String oldKey = user.getAvatarKey();
        userMapper.updateAvatar(userId, null);
        deleteQuietly(oldKey, null);
        auditService.log(userId, "AVATAR_CLEAR", "USER", String.valueOf(userId), clientIp, true, null);
    }

    /**
     * 以流的方式读取某个用户的头像。
     * <p>给「能自行设置请求头」的客户端用（稳定地址、不会因为签名过期而失效）。
     * 浏览器 {@code <img src>} 无法携带 Authorization 头，那种场景请用资料接口里的
     * {@code avatarUrl}（签名地址）。
     */
    public DownloadTarget open(long targetUserId) {
        SysUser user = userMapper.selectById(targetUserId);
        if (user == null || !StringUtils.hasText(user.getAvatarKey())) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "该用户还没有设置头像");
        }
        String key = user.getAvatarKey();
        OSSObject object = ossSignService.openStream(key, null, null);
        ObjectMetadata meta = object.getObjectMetadata();
        long size = meta.getContentLength();
        String contentType = StringUtils.hasText(meta.getContentType())
                ? meta.getContentType() : guessContentType(key);
        String fileName = key.endsWith(".png") ? "avatar.png" : "avatar.jpg";
        return new DownloadTarget(fileName, contentType, size, 0L, Math.max(0L, size - 1),
                false, object.getObjectContent(), object);
    }

    /** 生成签名地址（用于资料响应） */
    public String signedUrl(String avatarKey) {
        if (!StringUtils.hasText(avatarKey)) {
            return null;
        }
        return ossSignService.presignedObjectUrl(avatarKey, SIGN_EXPIRE_SECONDS);
    }

    /** 版本串：取 Key 的文件名部分，换头像后必然变化 */
    public String version(String avatarKey) {
        if (!StringUtils.hasText(avatarKey)) {
            return null;
        }
        int slash = avatarKey.lastIndexOf('/');
        String fileName = slash >= 0 ? avatarKey.substring(slash + 1) : avatarKey;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void deleteQuietly(String oldKey, String newKey) {
        if (!StringUtils.hasText(oldKey) || oldKey.equals(newKey)) {
            return;
        }
        // OssSignService.delete 内部已吞异常并记 error 日志，这里不阻断主流程
        ossSignService.delete(oldKey);
    }

    private String guessContentType(String key) {
        return key.endsWith(".png") ? "image/png" : "image/jpeg";
    }
}
