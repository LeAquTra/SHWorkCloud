package com.leaqutra.shworkcloud.service;

import cn.hutool.json.JSONUtil;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.dto.UploadTokenPayload;
import com.leaqutra.shworkcloud.entity.UploadSession;
import com.leaqutra.shworkcloud.mapper.UploadSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 一次性上传令牌（upload ticket）。
 * <p>
 * 这是「服务端签发 ObjectKey」方案的落地点：浏览器拿到 uploadToken 后，
 * 所有上传动作（取预签名 URL、分片、完成、放弃）都只凭这个 token 说话，
 * 服务端从 Redis 取回自己记录的 uploadKey，<b>完全忽略前端传的任何 objectKey</b>。
 * <p>
 * 同时落一条 {@code upload_session}，供孤儿对象对账任务回收
 * （学生传到一半下课关机留下的对象就是靠它清理的）。
 */
@Service
@RequiredArgsConstructor
public class UploadTokenService {

    private static final String KEY_PREFIX = "upload:";

    private final StringRedisTemplate redis;
    private final AppProperties appProperties;
    private final UploadSessionMapper uploadSessionMapper;

    /**
     * 生成一次性令牌并落库。
     *
     * @return uploadToken
     */
    public String mint(long userId, String objectKey) {
        String token = UUID.randomUUID().toString().replace("-", "");
        UploadTokenPayload payload = new UploadTokenPayload(userId, objectKey);
        redis.opsForValue().set(KEY_PREFIX + token, JSONUtil.toJsonStr(payload),
                Duration.ofSeconds(appProperties.getUpload().getUploadTokenSeconds()));

        UploadSession session = new UploadSession();
        session.setUserId(userId);
        session.setObjectKey(objectKey);
        session.setUploadToken(token);
        session.setStatus((byte) UploadSession.STATUS_PENDING);
        session.setSize(0L);
        uploadSessionMapper.insert(session);
        return token;
    }

    /** 读取令牌载荷；不存在、损坏或归属不符一律返回「凭证无效」 */
    public UploadTokenPayload read(String uploadToken, long currentUserId) {
        if (uploadToken == null || uploadToken.isBlank()) {
            throw new BizException(ErrorCode.UPLOAD_TOKEN_INVALID);
        }
        String json = redis.opsForValue().get(KEY_PREFIX + uploadToken);
        if (json == null) {
            throw new BizException(ErrorCode.UPLOAD_TOKEN_INVALID);
        }
        UploadTokenPayload payload;
        try {
            payload = JSONUtil.toBean(json, UploadTokenPayload.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPLOAD_TOKEN_INVALID);
        }
        if (payload == null || payload.getUserId() == null || payload.getUserId() != currentUserId
                || payload.getUploadKey() == null) {
            throw new BizException(ErrorCode.UPLOAD_TOKEN_INVALID);
        }
        return payload;
    }

    /** 取回该令牌对应的 ObjectKey（服务端权威值） */
    public String requireObjectKey(String uploadToken, long currentUserId) {
        return read(uploadToken, currentUserId).getUploadKey();
    }

    /** 消费令牌（commit 成功后调用，保证一次性） */
    public void consume(String uploadToken) {
        redis.delete(KEY_PREFIX + uploadToken);
    }
}
