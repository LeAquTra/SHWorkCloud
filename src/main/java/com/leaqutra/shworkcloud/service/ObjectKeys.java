package com.leaqutra.shworkcloud.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * OSS ObjectKey 生成规则（文档 §4.3）。集中在一处，避免各处各写一套。
 * <pre>
 *   用户文件      homework/{userId}/{yyyyMM}/{uuid32}
 *   自定义头像    avatar/{userId}/{uuid32}.{ext}
 *   验证码图片    captcha/{yyyyMM}/{uuid32}.{ext}
 * </pre>
 * 用户文件刻意<b>不</b>把扩展名拼进 Key：显示名与后缀以 commit 时的 name 为准，
 * 避免信任前端提供的扩展名。
 * <p>头像与验证码由服务端直接上传，扩展名由服务端从真实图片格式推导，因此可以带上。
 */
public final class ObjectKeys {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** 头像前缀 */
    public static final String AVATAR_PREFIX = "avatar";

    private ObjectKeys() {
    }

    /** 用户文件前缀（含结尾斜杠），如 homework/1001/ */
    public static String userPrefix(String keyPrefix, long userId) {
        String prefix = keyPrefix == null || keyPrefix.isBlank() ? "homework" : keyPrefix;
        return prefix + "/" + userId + "/";
    }

    /** 新的用户文件 Key（服务端签发，前端必须原样使用） */
    public static String newUserKey(String userPrefix) {
        return userPrefix + MONTH.format(LocalDate.now()) + "/" + uuid32();
    }

    /** 某个用户头像的目录前缀，如 avatar/1001/ */
    public static String avatarPrefix(long userId) {
        return AVATAR_PREFIX + "/" + userId + "/";
    }

    /**
     * 新的头像 Key，如 {@code avatar/1001/9b2f7c...png}。
     * <p>每次更换头像都用新 UUID：旧对象换完立刻删掉，Key 不复用，
     * 这样即使删除失败也只会留下一个可被对账任务识别的孤儿，而不会被新头像覆盖后无法区分。
     *
     * @param extension 由真实图片格式推导（png / jpg），不是用户文件名里的后缀
     */
    public static String newAvatarKey(long userId, String extension) {
        return avatarPrefix(userId) + uuid32() + "." + extension;
    }

    /** 验证码图片 Key；该前缀不对普通用户 STS 开放 */
    public static String newCaptchaKey() {
        return "captcha/" + MONTH.format(LocalDate.now()) + "/" + uuid32();
    }

    public static String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
