-- ============================================================================
--  作业云盘 SHWorkCloud —— 一键初始化脚本
--  版本：v2.3        日期：2026-09-20
--  对应设计文档：docs/SHWordCloud_Standard_v2.0.md §5
--
--  用法（全新部署，一条命令搞定）：
--      mysql -uroot -p < src/main/resources/init_sql/init.sql
--  或进 mysql 客户端后：
--      source /path/to/init.sql
--
--  本脚本包含：建库 -> 建表 -> 索引 -> 时区与字符集设置 -> 自检
--  可重复执行（全部使用 IF NOT EXISTS），但不会覆盖已有数据。
--
--  ⚠️ 超级管理员不在这里插入：
--     由应用启动时的 SuperAdminInitializer 按环境变量 ADMIN_INIT_PASSWORD 幂等创建，
--     避免把初始密码写死在 SQL 里。
--  ⚠️ 验证码题库也不预置：
--     生产环境预置题目等于把验证码答案公开在源码里。
--
--  如果你的库是 v2.0 时建的，请改用 migration_v2.1.sql 做增量升级。
--  如果你的库是 v2.2 及更早的，再补 migration_v2.3.sql（好友与私聊）、
--  migration_v2.4.sql（社区帖子）。
--  ⚠️ 本脚本是 idempotent 的合并版：它建的表已经包含 v2.1 ~ v2.4 的全部结构，
--     因此 **全新部署只需跑这一个文件**，不必再逐个执行 migration_v2.*。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 建库
-- ---------------------------------------------------------------------------
-- utf8mb4：支持 emoji 与全部中文（utf8 在 MySQL 里只有 3 字节，存不下 emoji）
-- utf8mb4_0900_ai_ci：MySQL 8 默认排序规则，大小写与重音不敏感
CREATE DATABASE IF NOT EXISTS `shwork_cloud`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `shwork_cloud`;

-- ---------------------------------------------------------------------------
-- 2. 会话级设置
-- ---------------------------------------------------------------------------
-- 全链路北京时间：作业截止时间之类的判断依赖它。
-- 会话级设置只对当前连接有效；持久生效请改 my.cnf（推荐）：
--     [mysqld]
--     default-time-zone = '+08:00'
SET time_zone = '+08:00';

-- ---------------------------------------------------------------------------
-- 3. 用户表
-- ---------------------------------------------------------------------------
-- 角色 role：0 学生 / 1 管理员 / 2 教师(机房管理员) / 9 超级管理员
-- 数字 role 是权威值，Sa-Token 的角色字符串由 StpInterfaceImpl 派生
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`         VARCHAR(50)   NOT NULL COMMENT '登录名：学生为学号，其他为自定义账号',
  `student_no`       VARCHAR(32)   DEFAULT NULL COMMENT '学号（学生必填，教师/管理员为NULL）',
  `real_name`        VARCHAR(50)   DEFAULT NULL COMMENT '真实姓名（名单导入）',
  `class_name`       VARCHAR(100)  DEFAULT NULL COMMENT '班级，如 高一(3)班',
  `email`            VARCHAR(100)  DEFAULT NULL COMMENT 'QQ邮箱，可空（名单导入无邮箱）',
  `password`         VARCHAR(100)  NOT NULL COMMENT 'BCrypt 密文（强度 10）',
  `nickname`         VARCHAR(50)   DEFAULT NULL COMMENT '昵称',
  `avatar_key`       VARCHAR(512)  DEFAULT NULL COMMENT '自定义头像在 OSS 的 ObjectKey（形如 avatar/{userId}/{uuid}.png）',
  `avatar_updated_at` DATETIME     DEFAULT NULL COMMENT '上次修改/清除头像的时间；用于"24 小时只能改一次"的冷却判断',
  `signature`        VARCHAR(255)  DEFAULT NULL COMMENT '个性签名',
  `gender`           TINYINT       NOT NULL DEFAULT 0 COMMENT '性别：0未知 1男 2女',
  `birthday`         DATE          DEFAULT NULL COMMENT '生日',
  `role`             TINYINT       NOT NULL DEFAULT 0 COMMENT '0学生 1管理员 2教师 9超级管理员',
  `status`           TINYINT       NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  `storage_quota`    BIGINT        NOT NULL DEFAULT 2147483648 COMMENT '总容量(字节)，默认2GB',
  `used_storage`     BIGINT        NOT NULL DEFAULT 0 COMMENT '已用容量(字节)，缓存值',
  `pwd_changed`      TINYINT       NOT NULL DEFAULT 1 COMMENT '1已改密 0需强制改密（首登/重置后）',
  `login_fail_count` TINYINT       NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  `locked_until`     DATETIME      DEFAULT NULL COMMENT '锁定至（连续失败达阈值）',
  `last_login_time`  DATETIME      DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip`    VARCHAR(64)   DEFAULT NULL COMMENT '最后登录IP（机房清场用）',
  `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_student_no` (`student_no`),
  UNIQUE KEY `uk_email` (`email`),
  KEY `idx_role_status` (`role`, `status`, `deleted`),
  KEY `idx_class` (`class_name`),
  KEY `idx_last_login_ip` (`last_login_ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

-- ---------------------------------------------------------------------------
-- 4. 文件索引表（文件与文件夹统一建模）
-- ---------------------------------------------------------------------------
-- 两个关键设计（v1.1 的缺陷修正）：
--   1) uk_object_key：保证 commit 幂等。没有它，一次网络重试就会让文件索引
--      和已用容量翻倍。唯一索引允许多个 NULL，文件夹不受影响。
--   2) active_name 生成列 + uk_sibling_active：只约束「活跃记录」同级不重名。
--      v1.1 直接拿 status 参与唯一键，会导致「删除 -> 重传同名 -> 再删除」必现冲突，
--      因为 MySQL 没有部分索引（partial index）。
CREATE TABLE IF NOT EXISTS `file_entry` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '索引ID',
  `user_id`      BIGINT        NOT NULL COMMENT '归属用户',
  `parent_id`    BIGINT        NOT NULL DEFAULT 0 COMMENT '父目录ID，0=根目录',
  `name`         VARCHAR(255)  NOT NULL COMMENT '显示名称（回收站中保持不变）',
  `is_folder`    TINYINT       NOT NULL DEFAULT 0 COMMENT '1文件夹 0文件',
  `object_key`   VARCHAR(512)  DEFAULT NULL COMMENT 'OSS 物理Key（服务端签发）；文件夹为NULL',
  `size`         BIGINT        NOT NULL DEFAULT 0 COMMENT '文件大小(字节)，以 OSS headObject 为准',
  `suffix`       VARCHAR(32)   DEFAULT NULL COMMENT '扩展名（小写，不含点）',
  `content_type` VARCHAR(128)  DEFAULT NULL COMMENT 'MIME 类型',
  `md5`          CHAR(32)      DEFAULT NULL COMMENT '文件MD5（>200MB 为抽样指纹），用于秒传',
  `status`       TINYINT       NOT NULL DEFAULT 1 COMMENT '1正常 0回收站',
  `path`         VARCHAR(1000) NOT NULL DEFAULT '/' COMMENT '祖先ID物化路径，如 /12/35/；冗余加速列',
  `active_name`  VARCHAR(255)  GENERATED ALWAYS AS (IF(`status` = 1, `name`, NULL)) VIRTUAL
                 COMMENT '生成列：仅活跃记录参与同级唯一约束（数据库维护，代码禁止写入）',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `delete_time`  DATETIME      DEFAULT NULL COMMENT '进入回收站的时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_key` (`object_key`),
  UNIQUE KEY `uk_sibling_active` (`user_id`, `parent_id`, `active_name`),
  KEY `idx_user_parent` (`user_id`, `parent_id`, `status`, `is_folder`),
  KEY `idx_user_md5` (`user_id`, `md5`, `size`, `status`),
  KEY `idx_user_name` (`user_id`, `name`),
  KEY `idx_user_delete` (`user_id`, `status`, `delete_time`),
  KEY `idx_path` (`path`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网盘文件索引表';

-- ---------------------------------------------------------------------------
-- 5. 上传会话表（commit 幂等 + 孤儿对象回收）
-- ---------------------------------------------------------------------------
-- 每签发一次上传凭证就插一条 PENDING；commit 成功置 1；
-- 超过 24 小时仍未 commit 的由定时任务删掉 OSS 对象并置 2。
-- 这是「学生传到一半下课关机」不留下垃圾对象的关键。
CREATE TABLE IF NOT EXISTS `upload_session` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT        NOT NULL COMMENT '发起用户',
  `object_key`   VARCHAR(512)  NOT NULL COMMENT '服务端签发的 OSS Key',
  `upload_token` VARCHAR(64)   NOT NULL COMMENT '一次性上传令牌',
  `parent_id`    BIGINT        DEFAULT NULL COMMENT '目标父目录（commit 时回填）',
  `name`         VARCHAR(255)  DEFAULT NULL COMMENT '目标文件名（commit 时回填）',
  `size`         BIGINT        NOT NULL DEFAULT 0 COMMENT 'OSS 返回的真实大小',
  `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '0待提交 1已提交 2已放弃/已回收',
  `entry_id`     BIGINT        DEFAULT NULL COMMENT 'commit 成功后关联的 file_entry.id',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_key` (`object_key`),
  UNIQUE KEY `uk_upload_token` (`upload_token`),
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传会话表（幂等与孤儿回收）';

-- ---------------------------------------------------------------------------
-- 6. 验证码图片题库表
-- ---------------------------------------------------------------------------
-- 图片实体在 OSS 的 captcha/ 前缀（该前缀不对普通用户 STS 开放），
-- 答案与坐标标注只存本表，出题接口绝不下发答案。
-- 本表同时供"人机验证"使用（登录 / 注册发邮件码 / 上传文件三处，见 app.captcha.*）。
-- ⚠️ 本表为空时服务端会**自动不要求**人机验证 —— 否则全新部署时
--    登录与上传会被永久挡死，而用户无法完成验证（判定见 CaptchaRules.required）。
CREATE TABLE IF NOT EXISTS `captcha_image` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '题目ID',
  `type`        TINYINT       NOT NULL COMMENT '题型：1字符输入 2单选 3点选',
  `object_key`  VARCHAR(512)  NOT NULL COMMENT 'OSS 物理Key，captcha/ 前缀',
  `answer`      VARCHAR(255)  NOT NULL COMMENT '标准答案：字符/选项key/点选标注点ID顺序',
  `data_json`   JSON          DEFAULT NULL COMMENT '结构化标注：选项、点击坐标点等',
  `width`       INT           NOT NULL DEFAULT 0 COMMENT '图片原始宽(px)',
  `height`      INT           NOT NULL DEFAULT 0 COMMENT '图片原始高(px)',
  `weight`      INT           NOT NULL DEFAULT 100 COMMENT '出题权重，越大越容易被抽中',
  `used_count`  BIGINT        NOT NULL DEFAULT 0 COMMENT '累计出题次数（统计用）',
  `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `remark`      VARCHAR(255)  DEFAULT NULL COMMENT '管理员备注',
  `created_by`  BIGINT        NOT NULL COMMENT '上传/维护人（用户ID）',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_weight` (`status`, `weight`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注册图片验证码题库';

-- ---------------------------------------------------------------------------
-- 7. 操作审计日志
-- ---------------------------------------------------------------------------
-- 注意：detail 中禁止记录密码、Token、AK/SK 等敏感信息。
CREATE TABLE IF NOT EXISTS `operation_log` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT        DEFAULT NULL COMMENT '操作人ID（匿名注册为NULL）',
  `username`    VARCHAR(50)   DEFAULT NULL COMMENT '操作人登录名（冗余，便于排查）',
  `action`      VARCHAR(64)   NOT NULL COMMENT '动作码，如 FILE_UPLOAD / USER_DISABLE / PROFILE_UPDATE',
  `target_type` VARCHAR(32)   DEFAULT NULL COMMENT 'FILE / USER / CAPTCHA / SESSION',
  `target_id`   VARCHAR(64)   DEFAULT NULL COMMENT '目标ID',
  `detail`      VARCHAR(1000) DEFAULT NULL COMMENT '补充信息（禁止记录敏感数据）',
  `ip`          VARCHAR(64)   DEFAULT NULL,
  `user_agent`  VARCHAR(255)  DEFAULT NULL,
  `success`     TINYINT       NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_action_time` (`action`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志';

-- ---------------------------------------------------------------------------
-- 8. 邮件发送审计
-- ---------------------------------------------------------------------------
-- 用于排查「学生说没收到验证码」这类问题。
CREATE TABLE IF NOT EXISTS `email_send_log` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `email`       VARCHAR(100)  NOT NULL,
  `scene`       VARCHAR(32)   NOT NULL COMMENT 'REGISTER / RESET_PWD',
  `ip`          VARCHAR(64)   DEFAULT NULL,
  `success`     TINYINT       NOT NULL DEFAULT 1,
  `error_msg`   VARCHAR(500)  DEFAULT NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_email_time` (`email`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮件发送审计';

-- ---------------------------------------------------------------------------
-- 9. 系统公告（含注意力分级）
-- ---------------------------------------------------------------------------
-- level 注意力分级：
--   1 普通 —— 顶部横幅，可关闭
--   2 重要 —— 横幅 + 警示配色，可关闭
--   3 紧急 —— **弹窗强制确认**，不点"我知道了"不能继续
-- status：0 草稿 / 1 已发布 / 2 已撤回
--   "撤回"只改状态、保留数据，便于追溯谁在什么时候发过什么。
CREATE TABLE IF NOT EXISTS `announcement` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `title`        VARCHAR(120) NOT NULL COMMENT '标题',
  `content`      TEXT         NOT NULL COMMENT '正文（纯文本，前端按换行渲染）',
  `level`        TINYINT      NOT NULL DEFAULT 1 COMMENT '注意力分级：1普通 2重要 3紧急',
  `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布 2已撤回',
  `publish_time` DATETIME     DEFAULT NULL COMMENT '发布时间；撤回后保留用于追溯',
  `expire_time`  DATETIME     DEFAULT NULL COMMENT '过期时间；为空表示不过期',
  `created_by`   BIGINT       DEFAULT NULL COMMENT '创建/发布者用户ID',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_level` (`status`, `level`, `publish_time`),
  KEY `idx_publish_time` (`publish_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统公告';

-- ---------------------------------------------------------------------------
-- 10. 好友关系表（有向边 + 双向行）
-- ---------------------------------------------------------------------------
-- 一行表示「user_id 的列表里有 friend_id 这个人」，好友关系存两行
-- （A→B 与 B→A），互为好友 = 两条边的 status 都是 1。
-- 为什么不用「一行一对用户」：那样查"我的好友"要写 `user_id=? OR friend_id=?`，
-- OR 用不上索引；而且必须额外存"申请是谁发的"，否则容易写出"半好友"状态。
-- status：0 待对方接受 / 1 已是好友；拒绝 = 删掉待处理行（故无 status=2，
-- 好处是"拒绝后仍可再次申请"，不留永久黑名单语义）。
-- 🔴 本表没有 deleted 列、删除一律物理删除：uk_edge 是唯一键，而 MySQL 不支持
-- 条件唯一键 —— 软删除的行会继续占着键，导致"拒绝后再申请"撞 Duplicate entry。
-- 详见 migration_v2.3.sql 的注释。
CREATE TABLE IF NOT EXISTS `friend_relation` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  `user_id`     BIGINT      NOT NULL COMMENT '归属方：本行属于谁的列表',
  `friend_id`   BIGINT      NOT NULL COMMENT '对方用户ID',
  `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0待对方接受 1已是好友',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP COMMENT '成为好友/变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_edge` (`user_id`, `friend_id`),
  KEY `idx_user_status_friend` (`user_id`, `status`, `friend_id`),
  KEY `idx_friend_status` (`friend_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友关系（有向边，好友存双向两行）';

-- ---------------------------------------------------------------------------
-- 11. 好友私聊消息表
-- ---------------------------------------------------------------------------
-- 只存文字：content 是纯文本，前端按换行渲染，绝不下发/渲染 HTML。
-- id 同时充当增量拉取的游标（前端记 lastId，只拉比它大的）。
-- read_flag：0 未读 1 已读；已读是"整条会话一次性置位"，不做单条回执。
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID（兼作增量拉取游标）',
  `from_user`   BIGINT        NOT NULL COMMENT '发送者用户ID（取自 Sa-Token 会话）',
  `to_user`     BIGINT        NOT NULL COMMENT '接收者用户ID',
  `content`     VARCHAR(1000) NOT NULL COMMENT '纯文本正文（不存 HTML）',
  `read_flag`   TINYINT       NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  PRIMARY KEY (`id`),
  KEY `idx_to_read_id` (`to_user`, `read_flag`, `id`),
  KEY `idx_from_to_id` (`from_user`, `to_user`, `id`),
  KEY `idx_to_from_id` (`to_user`, `from_user`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友私聊消息（仅文字）';

-- ---------------------------------------------------------------------------
-- 12. 社区帖子表（文字动态 + 审核）
-- ---------------------------------------------------------------------------
-- 状态机与 announcement 同构，维护者只需要理解一套规则：
--     发帖 ──▶ 0 待审核 ──approve──▶ 1 已通过 ──编辑──▶ 0 待审核（重新排队）
--                 │                    │
--                 │                    └──reject──▶ 2 已拒绝
--                 └──reject──▶ 2 已拒绝 ──编辑──▶ 0 待审核
-- 三条关键约束：
--   1) 用户发帖一律落为「待审核」，绝不允许直接可见；
--   2) **已通过的帖子被编辑后必须回到待审核** —— 否则作者可以先用正常内容过审、
--      再换成任何东西，审核形同虚设；
--   3) 审核结果保留数据（reviewed_by / review_time / reject_reason），可追溯。
-- content 是纯文本，不存 HTML；链接在读取时由 LinkSegmenter 解析成分段下发。
CREATE TABLE IF NOT EXISTS `post` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
  `author_id`     BIGINT       NOT NULL COMMENT '作者用户ID',
  `content`       TEXT         NOT NULL COMMENT '正文（纯文本，不存 HTML；链接由服务端解析）',
  `link_count`    INT          NOT NULL DEFAULT 0 COMMENT '正文里的链接数（供审核筛选）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核 1已通过 2已拒绝',
  `reviewed_by`   BIGINT       DEFAULT NULL COMMENT '审核人用户ID；未审核为 NULL',
  `review_time`   DATETIME     DEFAULT NULL COMMENT '审核时间；未审核为 NULL',
  `reject_reason` VARCHAR(200) DEFAULT NULL COMMENT '拒绝理由（仅 status=2 时有值，作者可见）',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_id` (`status`, `id`),
  KEY `idx_author_status_id` (`author_id`, `status`, `id`),
  KEY `idx_status_review` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='社区帖子（文字动态，需审核）';

-- ---------------------------------------------------------------------------
-- 13. 可选：创建最小权限的应用账号（生产环境推荐，不用 root 连库）
-- ---------------------------------------------------------------------------
-- 把 'YourStrongPassword' 换成强密码，并与 application-prod.yaml 的 DB_PASSWORD 一致。
-- 只给业务必需的四类权限，不给 DROP / ALTER / GRANT。
--
-- CREATE USER IF NOT EXISTS 'shwork'@'%' IDENTIFIED BY 'YourStrongPassword';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON `shwork_cloud`.* TO 'shwork'@'%';
-- -- 若用 Flyway/Liquibase 自动建表，再补：
-- -- GRANT CREATE, ALTER, INDEX, REFERENCES ON `shwork_cloud`.* TO 'shwork'@'%';
-- FLUSH PRIVILEGES;