package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像校验规则。
 * <p>这是"上传头像"的第一道也是最重要的一道门：只认 JPG/PNG、≤5MB，
 * 且必须真的能被解析 —— 光看扩展名或 Content-Type 都是客户端说了算，挡不住任何东西。
 */
class AvatarRulesTest {

    // ------------------------------------------------------------ 工具

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "ImageIO 应支持写出 " + format);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ magic bytes

    @Test
    @DisplayName("magic bytes：PNG 与 JPEG 能识别")
    void detectFormat() throws IOException {
        assertEquals(AvatarRules.FORMAT_PNG, AvatarRules.detectFormat(image("png", 4, 4)));
        assertEquals(AvatarRules.FORMAT_JPEG, AvatarRules.detectFormat(image("jpg", 4, 4)));
    }

    @Test
    @DisplayName("magic bytes：其它格式与垃圾数据一律识别不出")
    void detectFormatRejectsOthers() throws IOException {
        // GIF 的 magic 是 GIF89a，不在白名单里
        assertNull(AvatarRules.detectFormat(image("gif", 4, 4)));
        assertNull(AvatarRules.detectFormat("这不是图片".getBytes()));
        assertNull(AvatarRules.detectFormat(new byte[]{1, 2, 3}));
        assertNull(AvatarRules.detectFormat(null));
    }

    // ------------------------------------------------------------ 完整校验

    @Test
    @DisplayName("合法 PNG：解析出格式、尺寸与落库信息")
    void inspectPng() throws IOException {
        AvatarRules.ImageInfo info = AvatarRules.inspect(image("png", 128, 96));
        assertEquals(AvatarRules.FORMAT_PNG, info.format());
        assertEquals(128, info.width());
        assertEquals(96, info.height());
        assertEquals("png", info.extension());
        assertEquals("image/png", info.contentType());
    }

    @Test
    @DisplayName("合法 JPEG：扩展名归一化为 jpg")
    void inspectJpeg() throws IOException {
        AvatarRules.ImageInfo info = AvatarRules.inspect(image("jpg", 200, 200));
        assertEquals(AvatarRules.FORMAT_JPEG, info.format());
        assertEquals("jpg", info.extension());
        assertEquals("image/jpeg", info.contentType());
    }

    @Test
    @DisplayName("伪装成 .png 的文本被拒绝（不看扩展名）")
    void rejectsFakeImage() {
        BizException e = assertThrows(BizException.class,
                () -> AvatarRules.inspect("<?php echo 1; ?>".getBytes()));
        assertEquals(ErrorCode.BAD_PARAM, e.getErrorCode());
    }

    @Test
    @DisplayName("GIF 等不在白名单的图片被拒绝")
    void rejectsGif() throws IOException {
        assertThrows(BizException.class, () -> AvatarRules.inspect(image("gif", 10, 10)));
    }

    @Test
    @DisplayName("超过 5MB 被拒绝，错误码为 FILE_TOO_LARGE")
    void rejectsOversize() {
        byte[] huge = new byte[(int) AvatarRules.MAX_BYTES + 1];
        // 补上 PNG magic，确保拒绝原因一定是"太大"而不是"格式不对"
        huge[0] = (byte) 0x89;
        huge[1] = 'P';
        huge[2] = 'N';
        huge[3] = 'G';
        BizException e = assertThrows(BizException.class, () -> AvatarRules.inspect(huge));
        assertEquals(ErrorCode.FILE_TOO_LARGE, e.getErrorCode());
    }

    @Test
    @DisplayName("刚好 5MB 允许（边界）")
    void allowsExactlyMaxBytes() {
        assertDoesNotThrow(() -> AvatarRules.validateSize(AvatarRules.MAX_BYTES));
        assertThrows(BizException.class,
                () -> AvatarRules.validateSize(AvatarRules.MAX_BYTES + 1));
    }

    @Test
    @DisplayName("空文件被拒绝")
    void rejectsEmpty() {
        assertThrows(BizException.class, () -> AvatarRules.inspect(new byte[0]));
        assertThrows(BizException.class, () -> AvatarRules.inspect(null));
        assertThrows(BizException.class, () -> AvatarRules.validateSize(0));
    }

    @Test
    @DisplayName("超大尺寸被拒绝（防解压炸弹：只读尺寸不解码像素）")
    void rejectsHugeDimension() throws IOException {
        byte[] wide = image("png", AvatarRules.MAX_DIMENSION + 1, 4);
        BizException e = assertThrows(BizException.class, () -> AvatarRules.inspect(wide));
        assertTrue(e.getMessage().contains("尺寸"), e.getMessage());

        assertDoesNotThrow(() -> AvatarRules.inspect(image("png", AvatarRules.MAX_DIMENSION, 4)));
    }

    // ------------------------------------------------------------ ObjectKey

    @Test
    @DisplayName("头像 Key 形如 avatar/{userId}/{uuid}.{ext}")
    void avatarKeyFormat() {
        String key = ObjectKeys.newAvatarKey(1001L, "png");
        assertTrue(key.startsWith("avatar/1001/"), key);
        assertTrue(key.endsWith(".png"), key);
        // uuid32 是 32 位十六进制
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        assertEquals(32 + 4, fileName.length());
        assertTrue(fileName.matches("[0-9a-f]{32}\\.png"), fileName);
    }

    @Test
    @DisplayName("每次生成的头像 Key 都不同（换头像不复用旧 Key）")
    void avatarKeyIsUnique() {
        String first = ObjectKeys.newAvatarKey(1001L, "jpg");
        String second = ObjectKeys.newAvatarKey(1001L, "jpg");
        assertTrue(!first.equals(second), "两次生成应不同");
        assertEquals(ObjectKeys.avatarPrefix(1001L), "avatar/1001/");
    }

    @Test
    @DisplayName("从头像 Key 反解 userId（对账任务据此判断归属）")
    void parseUserId() {
        assertEquals(1001L, OssReconcileService.userIdOfAvatarKey("avatar/1001/abc.png"));
        assertEquals(7L, OssReconcileService.userIdOfAvatarKey("avatar/7/x.jpg"));
        // 解析不出来的返回 null，由调用方按"无主对象"处理
        assertNull(OssReconcileService.userIdOfAvatarKey("avatar/notanumber/x.png"));
        assertNull(OssReconcileService.userIdOfAvatarKey("avatar/"));
        assertNull(OssReconcileService.userIdOfAvatarKey("homework/1001/x.png"));
    }

    @Test
    @DisplayName("isAvatarKey 只认 avatar/ 前缀")
    void isAvatarKey() {
        assertTrue(OssReconcileService.isAvatarKey("avatar/1001/a.png"));
        assertTrue(!OssReconcileService.isAvatarKey("homework/1001/a.png"));
        assertTrue(!OssReconcileService.isAvatarKey(null));
        assertTrue(!OssReconcileService.isAvatarKey(""));
    }

    // ------------------------------------------------------------ 24 小时冷却

    @Test
    @DisplayName("头像冷却：从未改过 → 立即可改（返回 null）")
    void cooldownAllowsFirstChange() {
        assertNull(AvatarRules.nextChangeableAt(null));
        assertNull(AvatarRules.ensureChangeAllowed(null));
    }

    @Test
    @DisplayName("头像冷却：刚改过 → 被拒，且提示里给出还要等多久（期望 40123）")
    void cooldownRejectsRecentChange() {
        LocalDateTime justNow = LocalDateTime.now().minusMinutes(5);
        assertEquals(justNow.plusHours(AvatarRules.CHANGE_INTERVAL_HOURS),
                AvatarRules.nextChangeableAt(justNow));

        BizException e = assertThrows(BizException.class,
                () -> AvatarRules.ensureChangeAllowed(justNow));
        assertEquals(ErrorCode.AVATAR_CHANGE_TOO_FREQUENT, e.getErrorCode());
        assertTrue(e.getMessage().contains("24 小时"), e.getMessage());
        assertTrue(e.getMessage().contains("小时后"), e.getMessage());
    }

    @Test
    @DisplayName("头像冷却：满 24 小时后放行；差一分钟仍拒绝")
    void cooldownBoundary() {
        LocalDateTime justOver = LocalDateTime.now().minusHours(AvatarRules.CHANGE_INTERVAL_HOURS)
                .minusMinutes(1);
        assertDoesNotThrow(() -> AvatarRules.ensureChangeAllowed(justOver));

        LocalDateTime oneMinuteShort = LocalDateTime.now()
                .minusHours(AvatarRules.CHANGE_INTERVAL_HOURS).plusMinutes(1);
        assertThrows(BizException.class, () -> AvatarRules.ensureChangeAllowed(oneMinuteShort));
    }

    // ------------------------------------------------------------ 资料里的冷却字段

    @Test
    @DisplayName("avatarChangeableAt：从未改过 / 冷却已过 → null（表示现在就能改）")
    void changeableAtIsNullWhenAvailable() {
        assertNull(UserService.avatarChangeableAt(null));
        // 上个星期改的：nextChangeableAt 会返回一个过去的时间点，
        // 但对外语义必须是"现在就能改"，所以这里要收敛成 null
        assertNull(UserService.avatarChangeableAt(LocalDateTime.now().minusDays(7)));
        assertNull(UserService.avatarChangeableAt(
                LocalDateTime.now().minusHours(AvatarRules.CHANGE_INTERVAL_HOURS).minusMinutes(1)));
    }

    @Test
    @DisplayName("avatarChangeableAt：冷却中 → 给出 24 小时后的时间点")
    void changeableAtIsFutureWhileCooling() {
        LocalDateTime changedAt = LocalDateTime.now().minusHours(2);
        LocalDateTime at = UserService.avatarChangeableAt(changedAt);
        assertNotNull(at);
        assertEquals(changedAt.plusHours(AvatarRules.CHANGE_INTERVAL_HOURS), at);
        assertTrue(at.isAfter(LocalDateTime.now()));
    }
}
