package com.leaqutra.shworkcloud.service;

import com.aliyun.oss.model.OSSObjectSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.config.OssProperties;
import com.leaqutra.shworkcloud.entity.CaptchaImage;
import com.leaqutra.shworkcloud.entity.FileEntry;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.entity.UploadSession;
import com.leaqutra.shworkcloud.mapper.CaptchaImageMapper;
import com.leaqutra.shworkcloud.mapper.FileEntryMapper;
import com.leaqutra.shworkcloud.mapper.UploadSessionMapper;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OSS 与数据库对账：把「数据库里没有任何引用」的对象清掉，保证 Bucket 整洁。
 * <p>
 * 覆盖三个前缀：
 * <ol>
 *   <li>{@code homework/} 用户文件 —— 未被 {@code file_entry.object_key} 引用，
 *       且不在 {@code upload_session}（可能正在上传/待提交）里；</li>
 *   <li>{@code avatar/} 头像 —— 不是该用户当前的 {@code avatar_key}（含用户已删除）；</li>
 *   <li>{@code captcha/} 验证码图 —— 未被 {@code captcha_image.object_key} 引用。</li>
 * </ol>
 * <b>宽限期（默认 24 小时）是关键安全阀</b>：只处理修改时间早于宽限期的对象，
 * 绝不会误删"刚签发了凭证、用户正在传"的文件。
 * <p>
 * 为什么需要它：应用层已经尽量做到"删索引必删对象"（见 {@link AfterCommit}），
 * 但远程调用可能失败、进程可能被强杀、上传可能中途断电。
 * 这类残留不会自己消失，只会在 Bucket 里长期占空间，所以需要一层兜底对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssReconcileService {

    /** 单次查询 IN 的批量大小 */
    private static final int QUERY_BATCH = 500;

    private final OssProperties ossProperties;
    private final AppProperties appProperties;
    private final OssSignService ossSignService;
    private final FileEntryMapper fileEntryMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final CaptchaImageMapper captchaImageMapper;
    private final UserMapper userMapper;

    /** 对账结果 */
    public record ReconcileResult(int scanned, int deleted, int kept, boolean truncated) {

        public String describe() {
            return "扫描 %d 个对象，删除 %d 个，保留 %d 个%s"
                    .formatted(scanned, deleted, kept, truncated ? "（已达扫描上限，未扫完）" : "");
        }
    }

    /**
     * 执行对账。
     *
     * @param dryRun true 表示只统计不删除（建议先用它预览）
     */
    public ReconcileResult reconcile(boolean dryRun) {
        int graceHours = Math.max(1, appProperties.getStorage().getReconcile().getGraceHours());
        int maxObjects = Math.max(1, appProperties.getStorage().getReconcile().getMaxObjects());
        LocalDateTime cutoff = LocalDateTime.now().minusHours(graceHours);

        int scanned = 0;
        int deleted = 0;
        int truncated = 0;

        // ---------- 1) 用户文件 ----------
        List<OSSObjectSummary> homework = ossSignService.listAll(ossProperties.getKeyPrefix() + "/");
        if (homework.size() > maxObjects) {
            truncated = 1;
            homework = homework.subList(0, maxObjects);
        }
        scanned += homework.size();
        List<String> homeworkCandidates = olderThan(homework, cutoff);
        List<String> orphanFiles = filterUnreferenced(homeworkCandidates);
        deleted += deleteAll(orphanFiles, dryRun, "用户文件孤儿");

        // ---------- 2) 头像 ----------
        List<OSSObjectSummary> avatars = ossSignService.listAll(ObjectKeys.AVATAR_PREFIX + "/");
        scanned += avatars.size();
        List<String> staleAvatars = filterStaleAvatars(olderThan(avatars, cutoff));
        deleted += deleteAll(staleAvatars, dryRun, "过期头像");

        // ---------- 3) 验证码图片 ----------
        List<OSSObjectSummary> captchas = ossSignService.listAll("captcha/");
        scanned += captchas.size();
        List<String> orphanCaptchas = filterUnreferencedCaptchas(olderThan(captchas, cutoff));
        deleted += deleteAll(orphanCaptchas, dryRun, "验证码孤儿图");

        int kept = scanned - deleted;
        log.info("OSS 对账完成 dryRun={} {}", dryRun,
                new ReconcileResult(scanned, deleted, kept, truncated != 0).describe());
        return new ReconcileResult(scanned, deleted, kept, truncated != 0);
    }

    // ------------------------------------------------------------ 内部

    /** 取修改时间早于宽限期的对象 Key */
    private List<String> olderThan(List<OSSObjectSummary> objects, LocalDateTime cutoff) {
        List<String> keys = new ArrayList<>();
        for (OSSObjectSummary summary : objects) {
            if (summary.getKey() == null) {
                continue;
            }
            LocalDateTime modified = summary.getLastModified() == null
                    ? LocalDateTime.MIN
                    : LocalDateTime.ofInstant(summary.getLastModified().toInstant(),
                            ZoneId.systemDefault());
            if (!modified.isAfter(cutoff)) {
                keys.add(summary.getKey());
            }
        }
        return keys;
    }

    /**
     * homework/ 里既不在 file_entry 也不在 upload_session 的 Key。
     * <p>upload_session 里任何状态都算"有引用"：PENDING 是正在上传，
     * 已提交/已放弃的由 {@code OrphanObjectJob} 专门处理，避免两个任务互相打架。
     */
    private List<String> filterUnreferenced(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Set<String> referenced = new HashSet<>();
        for (List<String> chunk : partition(keys)) {
            fileEntryMapper.selectList(new LambdaQueryWrapper<FileEntry>()
                            .select(FileEntry::getObjectKey)
                            .in(FileEntry::getObjectKey, chunk))
                    .forEach(entry -> referenced.add(entry.getObjectKey()));
            uploadSessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
                            .select(UploadSession::getObjectKey)
                            .in(UploadSession::getObjectKey, chunk))
                    .forEach(session -> referenced.add(session.getObjectKey()));
        }
        return keys.stream().filter(key -> !referenced.contains(key)).toList();
    }

    /** avatar/{userId}/... 里不是该用户当前头像的 Key（含用户已被删除的情况） */
    private List<String> filterStaleAvatars(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        // 从 Key 里解析出 userId
        Map<Long, List<String>> byUser = keys.stream()
                .filter(key -> key.startsWith(ObjectKeys.AVATAR_PREFIX + "/"))
                .collect(Collectors.groupingBy(OssReconcileService::userIdOfAvatarKey,
                        Collectors.toList()));

        Set<Long> userIds = byUser.keySet().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> currentAvatars = new java.util.HashMap<>();
        for (List<Long> chunk : partition(new ArrayList<>(userIds))) {
            // @TableLogic 会自动过滤已逻辑删除的用户 —— 那些用户的头像应当被回收
            List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .select(SysUser::getId, SysUser::getAvatarKey)
                    .in(SysUser::getId, chunk));
            users.forEach(user -> currentAvatars.put(user.getId(),
                    user.getAvatarKey() == null ? "" : user.getAvatarKey()));
        }

        List<String> stale = new ArrayList<>();
        byUser.forEach((userId, userKeys) -> {
            String current = userId == null ? "" : currentAvatars.getOrDefault(userId, "");
            for (String key : userKeys) {
                if (!key.equals(current)) {
                    stale.add(key);
                }
            }
        });
        return stale;
    }

    /**
     * 从 {@code avatar/{userId}/{file}} 中取出 userId。
     * <p>包级可见是为了单测 —— 解析错了会把别人的头像删掉。
     * <p>会先校验 {@code avatar/} 前缀：虽然当前唯一的调用点已经过滤过，
     * 但不校验的话 {@code homework/1001/x.png} 也会被解析成 1001，
     * 将来换个调用点就会误删用户文件。
     *
     * @return userId；解析不出返回 null（调用方按"无主对象"处理）
     */
    static Long userIdOfAvatarKey(String key) {
        if (!isAvatarKey(key)) {
            return null;
        }
        String[] parts = key.split("/");
        if (parts.length < 3) {
            return null;
        }
        try {
            return Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** captcha/ 里不在 captcha_image 中的 Key */
    private List<String> filterUnreferencedCaptchas(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Set<String> referenced = new HashSet<>();
        for (List<String> chunk : partition(keys)) {
            captchaImageMapper.selectList(new LambdaQueryWrapper<CaptchaImage>()
                            .select(CaptchaImage::getObjectKey)
                            .in(CaptchaImage::getObjectKey, chunk))
                    .forEach(image -> referenced.add(image.getObjectKey()));
        }
        return keys.stream().filter(key -> !referenced.contains(key)).toList();
    }

    private int deleteAll(List<String> keys, boolean dryRun, String reason) {
        if (keys.isEmpty()) {
            return 0;
        }
        if (dryRun) {
            log.info("[dryRun] 将删除 {} 个对象（{}）：{}", keys.size(), reason,
                    keys.size() <= 5 ? String.join(", ", keys) : keys.get(0) + " ...");
            return keys.size();
        }
        log.warn("回收 {} 个 OSS 对象（{}）：{}", keys.size(), reason,
                keys.size() <= 5 ? String.join(", ", keys) : keys.get(0) + " ...");
        ossSignService.deleteBatch(keys);
        return keys.size();
    }

    private static <T> List<List<T>> partition(List<T> source) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += QUERY_BATCH) {
            result.add(source.subList(i, Math.min(i + QUERY_BATCH, source.size())));
        }
        return result;
    }

    /** 便于单测/排查：判断某个对象是否"像"头像 Key */
    public static boolean isAvatarKey(String key) {
        return StringUtils.hasText(key) && key.startsWith(ObjectKeys.AVATAR_PREFIX + "/");
    }
}
