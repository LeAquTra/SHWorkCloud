-- ============================================================================
--  迁移脚本 v2.0 -> v2.1
--  适用：**已经用 v2.0 的脚本建过表**的库。
--
--  全新部署不需要执行本脚本 —— 直接用 init.sql 即可（新库已包含全部变更）。
--
--  变更内容：
--    1. sys_user 新增个性属性：signature / gender / birthday
--    2. sys_user.avatar -> avatar_key：头像从「任意 URL」改为「OSS ObjectKey」
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 个性签名
-- ---------------------------------------------------------------------------
ALTER TABLE `sys_user`
  ADD COLUMN `signature` VARCHAR(255) DEFAULT NULL COMMENT '个性签名' AFTER `avatar`;

-- ---------------------------------------------------------------------------
-- 2) 性别：0 未知 1 男 2 女
-- ---------------------------------------------------------------------------
ALTER TABLE `sys_user`
  ADD COLUMN `gender` TINYINT NOT NULL DEFAULT 0 COMMENT '性别：0未知 1男 2女' AFTER `signature`;

-- ---------------------------------------------------------------------------
-- 3) 生日
-- ---------------------------------------------------------------------------
ALTER TABLE `sys_user`
  ADD COLUMN `birthday` DATE DEFAULT NULL COMMENT '生日' AFTER `gender`;

-- ---------------------------------------------------------------------------
-- 4) 头像：avatar(VARCHAR URL) -> avatar_key(VARCHAR OSS ObjectKey)
-- ---------------------------------------------------------------------------
-- 语义变了：v2.0 允许用户填任意图片 URL，v2.1 改为「服务端上传到 OSS」，
-- 数据库只存 ObjectKey（形如 avatar/{userId}/{uuid}.png）。
-- 旧值不是合法 ObjectKey，留着会让签名 URL 生成失败，因此统一清空 ——
-- 学生下次上传头像即可，不需要数据修复脚本。
ALTER TABLE `sys_user`
  CHANGE COLUMN `avatar` `avatar_key` VARCHAR(512) DEFAULT NULL
  COMMENT '自定义头像在 OSS 的 ObjectKey（形如 avatar/{userId}/{uuid}.png）';

UPDATE `sys_user`
   SET `avatar_key` = NULL
 WHERE `avatar_key` IS NOT NULL
   AND `avatar_key` NOT LIKE 'avatar/%';

-- ---------------------------------------------------------------------------
-- 5) 校验
-- ---------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'sys_user'
   AND COLUMN_NAME IN ('avatar_key', 'signature', 'gender', 'birthday')
 ORDER BY ORDINAL_POSITION;

-- ============================================================================
--  回滚（如确需回退）：
--    ALTER TABLE `sys_user` DROP COLUMN `birthday`;
--    ALTER TABLE `sys_user` DROP COLUMN `gender`;
--    ALTER TABLE `sys_user` DROP COLUMN `signature`;
--    ALTER TABLE `sys_user` CHANGE COLUMN `avatar_key` `avatar` VARCHAR(512) DEFAULT NULL;
-- ============================================================================
