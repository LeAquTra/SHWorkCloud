# SHWorkCloud 作业云盘

学生机房课堂文件保存系统：学生在机房电脑上把文件存进自己的网盘，教师课前批量开号、课后管理账号。

> **交付范围**：本期只做**后端**（REST 接口）。仓库中的 `homework-web/` 是配套的参考实现，
> 不属于验收内容，可以整体删除（`Remove-Item homework-web -Recurse`）。
> 接口契约与 curl 示例见 **[docs/后端接口手册.md](docs/后端接口手册.md)**，
> 端到端验收用 **[scripts/api-smoke.ps1](scripts/api-smoke.ps1)**。

- **前端对接指南：[docs/前端对接指南.md](docs/前端对接指南.md)**
- 接口手册：[docs/后端接口手册.md](docs/后端接口手册.md)
- 设计文档：[`docs/SHWordCloud_Standard_v2.0.md`](docs/SHWordCloud_Standard_v2.0.md)（实现依据）
- 评审报告：[`docs/SHWorkCloud_项目评审报告.md`](docs/SHWorkCloud_项目评审报告.md)（v1.1 缺陷分析）
- 技术栈：Spring Boot 4.1.1 + JDK 17 + Sa-Token 1.45.0 + MyBatis-Plus + MySQL 8 + Redis + 阿里云 OSS（STS 直传）

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 建议 21 |
| MySQL | 8.0 | 需 `default-time-zone='+08:00'` |
| Redis | 7 | 会话、验证码、上传令牌、限流 |
| 阿里云 OSS | — | **私有** Bucket + RAM 子用户 AK/SK + STS 角色 |
| Maven | 3.9+ | 或使用 `mvnw` |

> ⚠️ **时区必须统一为北京时间**。JDBC 已配置 `serverTimezone=Asia/Shanghai`，
> MySQL 侧也要设置 `default-time-zone='+08:00'`。否则「作业截止时间」类判断会整体错 8 小时。

---

## 2. 快速开始

### 2.1 初始化数据库

```bash
# 一条命令完成：建库 + 建表 + 索引 + 时区自检（脚本可重复执行）
mysql -uroot -p < src/main/resources/init_sql/init.sql

# 如果你的库是早期版本建的，按顺序补增量迁移：
# mysql -uroot -p shwork_cloud < src/main/resources/init_sql/migration_v2.1.sql   # 个性属性
# mysql -uroot -p shwork_cloud < src/main/resources/init_sql/migration_v2.2.sql   # 公告表 + 头像冷却时间戳
# mysql -uroot -p shwork_cloud < src/main/resources/init_sql/migration_v2.3.sql   # 好友关系 + 私聊消息
# mysql -uroot -p shwork_cloud < src/main/resources/init_sql/migration_v2.4.sql   # 社区帖子
```

### 2.2 填写本地配置

```bash
# Windows
copy src\main\resources\application-local.yaml.example src\main\resources\application-local.yaml
# Linux / macOS
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
```

然后编辑 `application-local.yaml` 填入：数据库密码、OSS Bucket、RAM 子用户 AK/SK、超管初始密码。

> 🔒 `application-local.yaml` 已在 `.gitignore` 中，**禁止提交**。
> `application.yaml` 里没有任何密钥，全部走 `${ENV}` 占位。

### 2.3 启动

推荐在 IDEA 中直接运行 `ShWorkCloudApplication`（离线环境也能用）。

命令行方式（需要能访问 Maven 仓库的完整依赖，见 §3）：

```bash
mvn spring-boot:run
```

启动后服务地址：`http://localhost:8081/api`

首次启动会自动创建超级管理员（`app.admin.init-*`），**首次登录强制改密**。

### 2.4 首次使用顺序

1. 用超管登录 → 修改密码
2. **两种开户方式，任选或都用**：
   - **用户自助注册**（默认已开启）：QQ 邮箱收验证码 → `POST /api/auth/register`；
   - **名单导入**（机房批量开户）：进「名单导入」下载 CSV 模板 → 填学号/姓名/班级 → 导入，
     学生用「学号 + 初始密码」登录，首次登录强制改密。
3. **人机验证**（登录 / 注册发邮件码 / 上传三处，用的是后台的验证码题库）：
   默认三个场景都开，但**题库为空时会自动降级为不要求** ——
   所以全新部署时登录、上传一切照旧，**上传题目后验证码才真正生效**。
   紧急情况下可用 `CAPTCHA_ENABLED=false` 一键全关（改 env 重启即可，不必重新打包）。
4. 学生登录后可自行修改**个性属性**（昵称/头像/签名/性别/生日），头像每 24 小时限改一次
5. 上传与下载：`POST /api/oss/ticket` → 直传 OSS → `POST /api/files/commit`；
   下载用 `GET /api/files/{id}/download`（支持断点续传）

---

## 3. 构建说明（重要：本机为离线环境）

### 3.1 已验证可用的命令

```bash
mvn -o -Dmaven.repo.local=%USERPROFILE%\.m2\repository test      # 280 个测试（含 6 个集成测试，默认跳过）
mvn -o -Dmaven.repo.local=%USERPROFILE%\.m2\repository compile
```

### 3.2 本机的两个环境问题（与代码无关）

**问题一：`settings.xml` 指向了不完整的本地仓库**

`E:\Maven\apache-maven-3.9.16\conf\settings.xml` 里配置的是：

```xml
<localRepository>E:\Maven\maven-repository</localRepository>
```

而 Boot 4.1.1 的依赖在 `C:\Users\<你>\.m2\repository` 里。这就是为什么直接执行
`mvn compile` 会报 `Non-resolvable parent POM ... spring-boot-starter-parent:4.1.1`。

**两种处理方式（任选其一）**：

1. 把 `settings.xml` 的 `<localRepository>` 改成 `C:\Users\<你>\.m2\repository`（推荐，一劳永逸）；
2. 每次构建加 `-Dmaven.repo.local=C:\Users\<你>\.m2\repository`。

> ⚠️ **只用 `-Dmaven.repo.local` 在离线模式下仍可能失败**（实测 Maven 3.9.16 + `-o`）：
> 报 `Non-resolvable parent POM ... has not been downloaded from it before` ——
> `~/.m2/repository` 里的 `_remote.repositories` 标记与当前仓库 id 对不上，
> 离线模式会拒绝使用它。此时用**显式 settings 文件**最稳（不必改用户级配置）：
>
> ```powershell
> # 在项目根建一个只给本仓库用的 settings.xml
> @'
> <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
>   <localRepository>C:\Users\<你>\.m2\repository</localRepository>
>   <offline>true</offline>
> </settings>
> '@ | Set-Content -Encoding UTF8 settings-local.xml
>
> mvn -o -s settings-local.xml test      # 已实测：266 个测试全绿
> ```

**问题二：离线无法打可执行 fat jar**

`spring-boot-maven-plugin` 的 `repackage` 目标需要 `spring-boot-loader-tools`
与 `spring-boot-buildpack-platform`，本机只有它们的 `.pom` 没有 `.jar`。因此：

| 操作 | 离线可用 | 说明 |
|------|:--------:|------|
| `compile` / `test` | ✅ | 已验证 |
| 普通 jar（`mvn package` 的 jar 目标） | ✅ | 但**不是**可执行 jar |
| `spring-boot:repackage`（可执行 fat jar） | ❌ | 需要联网一次，或补齐本地仓库 |
| `spring-boot:run` | ❌ | 同理 |
| IDEA 中直接 Run `ShWorkCloudApplication` | ✅ | **离线环境推荐这样跑** |

能联网时，`mvn package` 即可产出 `target/SHWorkCloud-0.0.1-SNAPSHOT.jar`（可 `java -jar` 运行）。

### 3.3 pom 中的离线适配（可安全移除）

`pom.xml` 的 `<properties>` 里把 7 个生命周期插件版本下调到了本机已完整缓存的版本
（即 Boot 3.5.15 使用的那一套），因为 Boot 4.1.1 默认的
`maven-filtering:3.5.0` 在本机只有 pom 没有 jar。补齐本地仓库或恢复外网后，
删除那 7 行即可回到 Boot 4.1.1 默认插件版本。

---

### 3.4 前端构建（**可选**，本期不交付前端）

> 前端只是配套参考实现。不做前端的话本节可以整段跳过，并可直接删除 `homework-web/`。

```bash
cd homework-web
npm install --offline --ignore-scripts          # 见下方说明
npm run build                                    # 产物在 homework-web/dist
```

本机有 3 个坑，都已经踩过并给出可行做法：

| 现象 | 原因 | 做法 |
|------|------|------|
| `EPERM open ...\npm-cache\_cacache\tmp` | npm 缓存目录在工作区之外，沙箱不允许写 | 把缓存复制到可写目录后指定 `--cache`：<br>`robocopy "%LOCALAPPDATA%\npm-cache\_cacache" "$env:TEMP\npm-cache\_cacache" /E`<br>`npm install --offline --ignore-scripts --cache "$env:TEMP\npm-cache"` |
| esbuild postinstall 报错 | 它的安装脚本会 spawn 子进程，受限环境下被拒 | 加 `--ignore-scripts`。esbuild 的平台二进制来自 `@esbuild/win32-x64` 依赖包，不影响使用 |
| `npm run build` 报 `spawn EPERM` | Vite 通过 esbuild 以**管道 stdio** 启动子进程，受限环境禁止 | 在 IDE 终端或普通终端里执行即可；沙箱内需放开该限制 |

依赖版本刻意与被缓存的版本对齐（`vue 3.5.40` / `vite 5.4.21` / `element-plus 2.14.2` 等，全部精确锁定，不用 `^`）。
另外 `package.json` 里有一段 `overrides` 把 `@vue/devtools-*` 锁到 `7.7.9` —— 缓存里只有这个补丁版本，
它只是构建期 devtools，恢复外网后可整段删除。

**MD5 自实现且有验证**：`spark-md5` 缓存里没有，`crypto.subtle` 在内网 http 下不可用，
因此 MD5 是自己实现的（`src/utils/md5.ts`）。它用 RFC 1321 全部 7 个标准向量 + 55/56/63/64/65/127/128/129 字节
边界 + 「一次性 vs 增量分块」一致性验证过：

```bash
cd homework-web && node scripts/verify-md5.mjs
```
### 3.5 后端接口验收（不需要前端）

```powershell
# 后端已启动、MySQL/Redis/OSS 可用时
pwsh ./scripts/api-smoke.ps1 -Login admin -Password 'YourInitPassword'
```

脚本会跑完整链路，并**逐字节校验"下载回本地"的内容与源文件一致**（含 Range 断点续传、
中文文件名编码、无效 token 被拒、回收站删除/还原/彻底删除）。
接口清单与 curl 示例见 [docs/后端接口手册.md](docs/后端接口手册.md)。

### 3.6 集成测试（**真的跑 SQL 的那一层**，强烈建议加进 CI）

```powershell
# 需要本机有 MySQL(3307) 与 Redis(6380)，库表先由 init.sql 建好
mysql --host=127.0.0.1 --port=3307 -uroot -p < src\main\resources\init_sql\init.sql
$env:RUN_INTEGRATION_TESTS='true'; mvn -o -s settings-local.xml test
```

不设这个环境变量时，集成测试会被跳过（`Skipped: 6`），只跑 274 个纯逻辑/静态守卫测试 ——
**这就是本项目此前最大的测试缺口**：266 个测试一条 SQL 都没跑过，于是
"加好友接口 500"这类问题只能靠猜，最后把一个"结果集列数 ≠ record 组件数"
的错误直接发到了线上。

| 测试 | 覆盖 |
|------|------|
| `FriendApiIntegrationTest` | 真实 MySQL + Redis 下打 HTTP 接口：好友页汇总 / 按 ID 查找（含搜到自己）/ 发起申请 / **拒绝后再申请** |
| `ShWorkCloudApplicationTests` | 完整 Spring 上下文能否装配 |
| `MapperResultSetContractTest` | 静态守"每个 `@Select` 的列数 == 目标 record 的组件数"——**没有数据库也能拦住上面那个错** |

连接串在 `src/test/resources/application-test.yaml`（指向 127.0.0.1:3307 / 6380），
需要指别的库时用 `--spring.datasource.url=...` 覆盖。

> ⚠️ 实测在受管沙箱里跑集成测试需要放行：`mysqld`/`redis-server` 的子进程启动、
> 以及 `~/.m2` 之外的仓库写入（见 §3.2 的 settings 方案）。

## 4. 项目结构

```
SHWorkCloud/
├── docs/                                    设计文档与评审报告
├── src/main/resources/
│   ├── application.yaml                     公共配置（无密钥）
│   ├── application-local.yaml.example       本地模板
│   ├── application-prod.yaml                生产（全 ${ENV}，缺失即启动失败）
│   └── init_sql/{init.sql,migration_v2.1.sql,migration_v2.2.sql}  一键初始化与增量迁移脚本
├── src/main/java/com/leaqutra/shworkcloud/
│   ├── common/                              R / ErrorCode / BizException / 全局异常处理
│   ├── config/                              OSS・STS・app 配置、Sa-Token、MyBatis-Plus
│   ├── security/                            登录上下文、角色缓存、限流、密码、CIDR
│   ├── entity/ mapper/                      10 张表
│   ├── dto/ vo/                             请求与响应模型
│   ├── service/                             业务服务 + job/ 定时任务
│   └── controller/                          REST 接口（admin/ 为后台）
├── src/test/java/                           280 个测试（274 纯逻辑/静态守卫 + 6 集成，见 §3.6）
└── homework-web/                            前端（Vue 3 + Vite + TS + Element Plus）
    ├── src/api/                             axios 封装 + 拦截器（Token 走 sessionStorage）
    ├── src/utils/uploader.ts                预签名直传：单次 PUT / 分片 / 断点续传 / 速率
    ├── src/utils/md5.ts                     纯 TS MD5（有 RFC 向量验证）
    ├── src/workers/md5.worker.ts            分块计算指纹，不卡界面
    ├── src/stores/                          user（会话隔离）/ uploader（上传队列）/ announcement（公告）/ friend（未读红点）
    ├── src/composables/useIdleLogout.ts     空闲自动登出
    ├── public/favicon.svg                   站点图标（标签页 + 页头同一个标识）
    └── src/views/                           登录、改密、网盘、社区、好友与聊天、用户主页、后台（用户/导入/题库/公告/运维/社区审核）
```

---

## 5. 接口速查

对外完整路径含 `context-path=/api`；Controller 里只写业务路径，**不要重复写 `/api`**。

| 模块 | 接口 | 说明 |
|------|------|------|
| 认证 | `POST /api/auth/login` | 学号/用户名 + 密码；题库启用时需 `captchaPassToken`（40105 即"请先完成人机验证"） |
| 认证 | `GET /api/auth/human-check` | **公开**：登录 / 注册 / 上传三处此刻是否需要人机验证（生效值） |
| 认证 | `POST /api/auth/logout` | 退出 |
| 认证 | `POST /api/auth/password` | 改密（首登强制也走这里） |
| 人机验证 | `GET /api/auth/captcha`、`POST /api/auth/captcha/verify` | 出题 / 作答；通过后拿 5 分钟有效的 `captchaPassToken` |
| 上传 | `GET /api/oss/sts` | 签发 STS 临时凭证 + 服务端 uploadKey/uploadToken（同样受人机验证约束） |
| 上传 | `POST /api/oss/ticket` | 申请上传凭证；题库启用时需 `captchaPassToken`，通过一次后有 10 分钟免验证窗口 |
| 上传 | `POST /api/files/instant-upload` | 尝试秒传 |
| 上传 | `POST /api/files/commit` | 直传完成后建索引（**幂等**） |
| 文件 | `GET /api/files` | 目录列表 / 分类 / 前缀搜索 |
| 文件 | `GET /api/files/tree`、`/files/breadcrumb` | 文件夹树、面包屑 |
| 文件 | `POST /api/folders`、`PUT /files/rename`、`POST /files/move`、`POST /files/copy` | 增改 |
| 文件 | `DELETE /api/files` | 批量进回收站 |
| 文件 | `GET /api/files/{id}/download-url`、`/preview-url` | 10 分钟签名 URL |
| 回收站 | `GET /api/recycle`、`POST /recycle/restore`、`DELETE /recycle/purge`、`/recycle/empty` | — |
| 用户 | `GET /api/user/profile`、`PUT /user/profile`、`GET /user/quota` | 资料/个性属性/容量 |
| 用户 | `GET /api/user/{userId}/profile` | **他人公开主页**（好友/聊天/社区点头像都走它）。字段已收窄：只有昵称/姓名/班级/头像/签名/角色 + 与我的关系，**不含邮箱/生日/性别/容量** |
| 好友 | `GET /api/friends`、`/friends/list`、`/friends/search?userId=` | 汇总（好友+申请+名额）/ 好友列表 / **按用户 ID 精确查找**（不支持姓名模糊搜） |
| 好友 | `POST /api/friends/requests`、`POST /friends/requests/handle`、`DELETE /friends/{friendId}` | 申请 / 同意·拒绝 / 删除。**上限 50**，发申请与同意两处都校验 |
| 私聊 | `GET /api/chat/conversations`、`GET /chat/messages?peerId=&afterId=&beforeId=&size=` | 会话列表（含未读）/ 拉消息。`afterId` 增量、`beforeId` 翻历史，返回 `maxId` 作下一个游标 |
| 私聊 | `POST /api/chat/messages`、`POST /chat/read/{peerId}`、`GET /chat/unread` | 发送（仅文字，≤1000 字）/ 标记已读 / 未读汇总（顶栏红点）。**非好友不能发** |
| 社区 | `GET /api/community/posts` | 广场（**只含已通过**）。游标翻页：回传 `nextBeforeId`；`?authorId=` 看某人的、`?mine=true` 看自己的（含待审与被拒） |
| 社区 | `POST /api/community/posts`、`PUT /community/posts/{id}`、`DELETE /community/posts/{id}` | 发帖（**一律待审核**）/ 编辑（**回到待审核**）/ 删除。请求体只有 `content` |
| 社区 | `GET /api/community/posts/{id}`、`/community/posts/mine/summary` | 详情（未通过的仅作者与审核者可见）/ 我的三个状态计数 |
| 后台 | `GET /api/admin/posts`、`/admin/posts/pending-count`、`POST /admin/posts/review` | **社区审核：教师及以上**（role 1/2/9）。见 §7 关于 `mode = SaMode.OR` 的故障记录 |
| 头像 | `POST /api/user/avatar`、`DELETE /api/user/avatar`、`GET /api/user/avatar/{userId}` | 仅 JPG/PNG、≤5MB、存 OSS；换头像自动删旧对象；**每 24 小时限一次**（冷却中返回 40123，`avatarChangeableAt` 给出下次可改时间） |
| 在线阅读 | `GET /api/files/{id}/preview-url`、`GET /api/files/{id}/preview`、`GET /api/files/{id}/text`（含 `html` 原格式）、`GET /api/files/{id}/embedded-images` | 图片/视频/音频流式预览（支持 Range）；**PDF 按需求不提供在线预览**；docx 渲染成结构化 HTML、xlsx 渲染成 HTML 表格；docx/pptx 内嵌图片以 data URL 返回 |
| 图片管理 | `GET /api/images` | 跨目录相册列表，每项已带签名预览地址 |
| 公告 | `GET /api/announcements/active` | 当前生效公告；`level` 1 普通 / 2 重要 → 顶部横幅，3 紧急 → 强制弹窗 |
| 后台 | `GET /api/admin/users`、`PUT /admin/users/{id}/{status,quota,reset-password,role}`、`PUT /admin/users/{id}`（代改资料，部分更新） | 用户管理 |
| 后台 | `GET /api/admin/students/import-template`、`POST /admin/students/import` | 名单导入 |
| 后台 | `/api/admin/captchas/**` | 题库管理 |
| 后台 | `/api/admin/announcements/**` | 公告管理：列表/新建/修改/发布/撤回/删除（仅超管；已发布必须先撤回才能改） |
| 运维 | `/api/admin/ops/**` | 孤儿对象、路径重算、机房清场、容量对账（仅超管） |

统一返回：`{ "code": 0, "message": "ok", "data": {...} }`。
仅未登录 → HTTP 401、无权限 → HTTP 403，其余业务码走 HTTP 200。

---

## 6. 安全注意事项

1. **不要提交 `application-local.yaml`**（已在 `.gitignore`）；
2. OSS 用户侧 STS Policy 只给 `homework/{uid}/*` 的写权限 ——
   **不含** `ListObjects`、不含 `captcha/*`、不含 `DeleteObject`；
3. 生产环境 `application-prod.yaml` 的所有密钥都无默认值，缺失即启动失败（fail fast）；
4. 超管初始密码为空时**拒绝启动**，不会用默认密码兜底；
5. 账号禁用/删除/重置密码都会踢下线并封禁，且清用户状态缓存；
6. 建议在 CI / pre-commit 加入 `gitleaks`，仅靠 `.gitignore` 防不住手滑。

---

## 7. 与文档的实现差异

实现过程中因**本机离线**（本地仓库缺少部分 artifact）做了以下等价替代，均已在代码注释中标注：

| 项 | 文档写法 | 实际实现 | 原因 |
|----|----------|----------|------|
| STS 调用 | `aliyun-java-sdk-sts` 的 `AssumeRoleRequest` | `aliyun-java-sdk-core` 的 `CommonRequest` 通用 RPC 调用 STS | 本地无 `aliyun-java-sdk-sts` |
| 会话存储 | `sa-token-redis-jackson`（会话入 Redis） | Sa-Token 默认内存 DAO | 本地无 `sa-token-redis-jackson`。**多实例部署前必须补上** |
| 参数校验 | `spring-boot-starter-validation` 注解 | Service 层手写校验 + `PasswordValidator` | 本地无 hibernate-validator |
| 密码哈希 | Spring Security `BCryptPasswordEncoder` | Hutool `BCrypt`（同为 `$2a$` 格式，互相兼容） | 本地无 spring-security-crypto |
| 签名响应头 | `ResponseHeaderParameters` | `ResponseHeaderOverrides` | 文档中的类名在 OSS SDK 3.17.4 中不存在 |
| 名单导入配置键 | `app.import.*` | `app.student-import.*` | `import` 是 Java 保留字，无法作为配置字段名 |
| ObjectKey | `.../{uuid32}.{ext}` | `.../{uuid32}`（不带扩展名） | 服务端签发时不信任前端提供的扩展名，显示名与后缀以 commit 的 `name` 为准 |
| **上传方式** | 浏览器持 STS 凭证 + ali-oss SDK 直传 | **服务端预签名 URL**（`/oss/ticket` + `/oss/put-url` + `/oss/multipart/*`） | 内网 http 下 `crypto.subtle` 不存在，浏览器无法自行签名；`ali-oss` 也装不上。预签名方案下浏览器**完全接触不到 AK/SK** |
| `uploadToken` 有效期 | 300 秒 | 7200 秒 | 500MB 在机房共享带宽下可能传十几分钟，凭证中途失效会让已传分片作废 |
| **自助注册** | 默认关闭（辅通道） | **默认开启**，图片验证码改为可选 | 需求要求"用户能自行注册登录"；图片验证码依赖题库，强制要求会让全新部署注册不了 |
| **个性属性** | 仅昵称/头像 | 增加签名/性别/生日，`PUT /user/profile` 为**部分更新** | 不传的键不改动，避免"只改昵称把头像清空"的数据丢失 |
| **下载回本地** | 仅签名 URL | 增加 `GET /files/{id}/download` 流式下载（支持 `Range`） | 调用方只连业务服务器即可下载，且可断点续传 |
| OSS `Content-Type` | 未说明 | 预签名接口返回签名用的 `contentType`，客户端必须原样发送 | OSS V1 签名把 Content-Type 计入待签串，不一致会 SignatureDoesNotMatch |
| **前端** | 文档要求交付 Vue 前端 | **不交付**，只提供 REST 接口 | 需求方只负责后端 |
| **自定义头像** | `avatar` 是文本 URL 字段 | 改为服务端上传 OSS，DB 存 `avatar_key`；**每 24 小时限改一次**（`avatar_updated_at` + `avatarChangeableAt`） | 需求：仅 JPG/PNG、≤5MB、存 OSS；换头像要能删掉旧对象；限制频率是为了防"拿头像当免费图床反复刷图" |
| **PDF 不预览** | viewType=pdf 时用浏览器阅读器打开 | `FileViewType.viewable()` 对 pdf 返回 false → `previewable=false`、`preview-url` 返回 40073 | 需求方明确要求：点击表单项不弹窗、操作下拉框里删掉"预览"。注意 `viewType` 仍是 `pdf`（图标与分类要用） |
| **公告** | 无 | 新表 `announcement` + 注意力分级（1 普通 / 2 重要 → 可关横幅；3 紧急 → 强制弹窗）；已发布必须先撤回才能改 | 需求：超管管理/发布/撤回公告，公告做注意力分级。禁止直接改已发布公告是为了避免"用户正在看的公告被静默改内容" |
| **人机验证三场景** | 只有注册可选地要图片验证码（`app.register.require-image-captcha`，默认 false） | 统一到 `app.captcha.*`（总开关 + 三个场景开关），默认**全开**；新增公开接口 `GET /auth/human-check` 下发**生效值**；上传有 10 分钟免验证窗口；`/oss/sts` 同样校验 | 需求：把后台验证码题库真正用起来。两个关键点：① **题库为空必须自动降级**，否则全新部署时登录会被永久挡死（用户无法完成验证）；② **`/oss/sts` 也要校验**，否则它就是绕开上传验证码的后门 |
| **修正 `app.import` 配置键** | `application.yaml` 里写的是 `app.import.*` | 改为 `app.student-import.*`（与 `AppProperties.StudentImport` 字段名一致） | 🔴 真实缺陷：键名写错**不会报错**，Spring 只是静默忽略 —— 表现是"设了 `IMPORT_DEFAULT_PASSWORD` 却没生效，导入的学生拿不到初始密码" |
| **删除即清 OSS** | 仅彻底删除才删 OSS | `app.recycle.enabled=false` 时删除即彻底删除；删用户/头像/题目也删 OSS | 需求：保证 OSS 容器整洁 |
| **OSS 对账** | 无 | 每日扫 `homework/`、`avatar/`、`captcha/` 清理无引用对象（24h 宽限） | 远程删除可能失败，需要兜底 |
| **好友关系模型** | 非目标（§1.4 把"评论 / 消息通知"列为超出范围） | 有向边 + 双向两行：一行 = 「`user_id` 的列表里有 `friend_id`」，互为好友 = 两条边都 `status=1` | 单行存一对用户的话，查"我的好友"要写 `user_id=? OR friend_id=?`，**OR 用不上索引**；且必须额外存"申请是谁发的"，否则容易写出"一边显示好友、另一边显示待审核"的撕裂状态 |
| **好友上限 50** | 未说明 | `FriendRules.MAX_FRIENDS` **硬编码常量**；名额 = 已确认好友 + 待处理申请；**接受申请时先 `SELECT ... FOR UPDATE` 锁自己的用户行再重数** | 只算好友数的话，"49 个好友 + 十个申请同时被接受"会一起通过检查把上限撑破；不锁行就是典型的 check-then-act 竞态 |
| **拒绝申请** | 未说明 | **删掉**那条待处理行，不置"已拒绝"状态 | 留一个 REJECTED 标记会形成永久黑名单语义（对方再也申请不了）；删除则"拒绝后仍可再次申请" |
| **🔴 好友边一律物理删除** | 未说明 | `friend_relation` **没有 `deleted` 列**，`FriendRelation` 也没有 `@TableLogic`；拒绝申请 / 删除好友 / 删号一律 `DELETE` | **真实故障（用户报的）**："加好友时提示发送申请失败"（HTTP 500）。根因是**软删除与唯一键天生冲突**：`uk_edge(user_id, friend_id)` 是唯一键，MySQL 不支持条件唯一键，所以 `UPDATE deleted = 1` 之后那一行**仍然占着键** → A 被拒绝后再申请 B 撞 `Duplicate entry` → 500。<br>同类问题本项目早有先例：`file_entry` 用**生成列 `active_name`** 把回收站的行排除出唯一键（那里要保留回收站语义）。好友边不需要留痕，直接删更干净。<br>回归守卫：`FriendRelationSchemaTest`（静态检查 DDL/实体/Mapper 三处都不再出现该列与逻辑删除）+ `FriendRulesTest.deletionIsPhysical` |
| **🔴 MyBatis 结果集列数必须等于 record 组件数** | 未说明 | `FriendRelationMapper` 的查询行类型按用途拆成两个 record：`FriendUserRow`（9 组件）/ `FriendRequestRow`（10 组件）；每个 `@Select` 的列数与它声明的 record 严格一致 | 🔴 **真实故障（用户报的）**："添加好友失败 / 按 ID 搜好友失败"，两个接口都 500。响应体是 `Constructor auto-mapping of 'FriendUserRow(...11 args...)' failed. The constructor takes '11' arguments, but there are only '10' columns in the result set.` —— MyBatis 把结果映射到 record 时**按位置对齐**，而那个 record 当时有 11 个组件（多两个只在申请场景用得上的字段），好友/搜索查询只给了 9 列。**列数与组件数不一致在编译期完全看不出来，只有真跑一次 SQL 才会炸。**<br>修法不是"给每处查询补 `NULL` 列"（那只是把位置对齐的脆弱性藏得更深），而是按用途拆 record，让形状在编译期就对得上。<br>回归守卫：`MapperResultSetContractTest`（静态：列数 == 组件数）+ `FriendApiIntegrationTest`（真跑 MySQL） |
| **🔴 集成测试缺位是上面这个故障的根本原因** | 只有纯逻辑单测 | 新增 `FriendApiIntegrationTest` 与 `src/test/resources/application-test.yaml`；`ShWorkCloudApplicationTests` 也补上 `@ActiveProfiles("test")`，让 `RUN_INTEGRATION_TESTS=true mvn test` **开箱即用** | 这个项目此前 266 个测试全是"纯逻辑 + 静态文本检查"，**一条 SQL 都没真跑过**，所以"加好友 500"只能靠猜（我先后猜过"软删除撞唯一键"和"库结构缺列"，**两次都被证伪**）。补上真实 MySQL/Redis 的端到端测试后，同一类问题在提交前就会暴露。详见 §3.6 |
| **数据库异常要能自报根因** | 未说明 | `GlobalExceptionHandler` 新增 `DataAccessException` 处理器，把最内层异常消息放进响应 `message` | 以前数据库错误掉进 `Exception` 兜底，前端只拿到笼统的 `50000 服务器内部错误`，必须登服务器翻日志才能定位。现在响应体直接是 `数据库访问失败：... Unknown column 'x'`。只带异常类名 + 最内层 message，不含 SQL 全文/参数/堆栈 |
| **社区审核的"有人发帖"提示** | 未说明 | 待审数收敛到**唯一一处** `stores/postReview.ts`：后台侧栏「社区审核」的红点角标与审核页顶部的"当前还有 N 条待审核"读同一个数字；**20 秒轮询** `GET /admin/posts/pending-count`，并在**切回标签页 / 切回窗口**时立刻补拉（3 秒冷却） | 学生发帖只进待审队列，审核员不在这个页面上就无从知晓 —— 没有红点等于没人知道有活要干。为什么必须是同一个 store：两处各拉一次就会出现"侧栏 3 条、点进去 0 条"的自相矛盾。轮询失败静默保留上次的值（网络抖一下不该让红点闪掉） |
| **审核页要跟着队列自己动** | 未说明 | `PostReviewView` 订阅 store 的待审数：**变大**（有新帖）→ 自动刷新列表 + `el-badge` 页签角标 + 顶部提示"有 N 条新帖提交，已自动刷新"；**变小**（同事在别处审完了）→ 也刷新，避免列表里还挂着已被处理的帖子（点"通过"会报状态错误）。另外自动修正**页码越界**：审完最后一页最后一条时自动退一页，而不是显示"没有待审核的帖子"这种误导性的空列表 | 审核员的任务是"把队列清空"，让列表始终等于队列比让他反复手点刷新更符合实际用法 |
| **前端报错必须带出真实原因** | 未说明 | `PostReviewView` 的加载失败会显示 `[业务码] 后端 message`，并在页面顶部常驻一条 `el-alert` | 🔴 实际踩过：社区审核报"加载失败"四个字没有下文，只能登服务器翻日志。前端把 `ApiError.code/message` 丢掉之后，一个"部署的是旧后端"（40004/40300）和"数据库出错"（50000）在界面上长得一模一样 |
| **🔴 不要用"方法名"当事件处理器（会把事件对象喂进去）** | 未说明 | 需要页码的调用一律写成 `@click="reload()"`；`reload(toPage?: number)` 内部也只接受 `typeof toPage === 'number'` | 🔴 **真实故障（用户报的）**：后台「社区审核」**点右上角「刷新」就报"加载失败"**，而首次进页面完全正常。根因是模板写了 `@click="reload"` —— Vue 对 `@click="fn"` 的语义是"把事件对象当第一个实参"，于是 `page` 被赋成一个 `PointerEvent`，请求串变成 `page=[object%20PointerEvent]`，服务端 `Long page` 绑不上 → 40000「参数类型错误: page」。**`if (toPage)` 这种真假判断拦不住它**（事件对象是真值），而 esbuild/rollup 与 `tsc --noEmit` 都不会报（`.vue` 模板不在 tsc 检查范围内）。<br>回归守卫：`npm run check:handlers`（`homework-web/scripts/check-handlers.mjs`，已串进 `npm run build`）：只看**原生 DOM 事件名**上的**裸方法名**处理器，并解析组件 `defineEmits` 的载荷类型 —— `@select="switchFolder"`（FolderTree emit 的是 `number`）不会误报，而 `<el-button @click="reload">`（el-button 声明的是 `click: evt => evt instanceof MouseEvent`，即原生事件透传）会被抓住 |
| **好友查找只按用户 ID** | 未说明 | `GET /friends/search?userId=1002`，精确主键点查 | 原实现按学号/登录名精确 + 姓名/昵称前缀搜。改成只收 ID 是**主动收紧**：模糊搜等于把"翻一遍全校人"变成一项功能，而 ID 必须先拿到（名单、或对方主页地址 `/user/1002` 里的数字）。收益是没有枚举面，代价是多一步"问对方要 ID" |
| **聊天实时性** | 非目标 | **游标式轮询**（`afterId` / `maxId`），聊天窗打开时 4 秒一次、顶栏红点 30 秒一次 | WebSocket 依赖不在本地仓库里（`spring-boot-starter-websocket` 缺失），且会话是**内存 DAO**、token 走 `Authorization` 头，WS 握手要另设通道。接口契约与传输层解耦，将来换 SSE/WS 只改前端传输部分 |
| **聊天隐私** | 未说明 | 审计日志**只记长度不记正文**，后台没有任何查看聊天内容的接口 | 私聊属隐私；日志留副本等于把它存到了更容易被翻到的地方 |
| **社区审核模型** | §1.4 把"评论"列为超出范围 | 新表 `post`，状态机与 `announcement` 同构：待审核 → 已通过 / 已拒绝 | 两处审核语义一致。**发帖一律落为待审核**：不是靠前端少给按钮，而是接口层面就没有"直接发布"这条路（`PostReq` 里只有 `content`，契约测试守着） |
| **已通过的帖子被编辑后回到待审核** | 未说明 | 编辑 = 状态重置为 0，并清空上一次的审核结论 | 🔴 否则作者可以先用正常内容过审、再把正文换成任何东西。代价是编辑后帖子会从广场暂时消失，界面明确提示；待审核中的帖子不允许再改/删（改了会让审核员读到与他即将批准的不同内容） |
| **"链接特殊显示"由服务端切段** | 未说明 | `LinkSegmenter` 把正文切成 `text` / `link` 分段下发，前端只把 `link` 段渲染成 `<a>` | 若让前端用正则切：① "什么算链接"有了两份实现；② 渲染层越厚注入面越大。服务端切段还能把"链接数"（审核信号）与渲染判定统一到同一份逻辑 |
| **只认 http / https / www.** | 未说明 | `javascript:` / `data:` / `vbscript:` / `file:` 一律不识别，原样当文字留在正文里（不丢字，也不给 href） | 这些不是"链接"而是注入载荷。回归守卫：`LinkSegmenterTest` |
| **"管理员以上"含教师** | 未说明 | 审核权限 = role ∈ {1 管理员, 2 教师, 9 超管}，`@SaCheckRole(value = {"admin","teacher","super_admin"}, mode = SaMode.OR)`；并把 `/admin/posts/**` 加进 `SaTokenConfigure.TEACHER_ADMIN_PATHS` | ⚠️ 角色编号不是有序等级（0 学生/1 管理员/2 教师/9 超管），教师(2) 数值比管理员(1) 大却权限更小。**两个坑都要躲**：① 只写 `admin` 会让教师审不了学生的帖子；② 漏写 `mode = SaMode.OR` 会让注解退回 AND 语义（见下一行） |
| **🔴 `@SaCheckRole` 默认是 AND 语义** | 未说明 | 多角色注解一律写 `mode = SaMode.OR`；`/admin/posts/**` 同时加入教师白名单 | **真实故障（用户报的）**：社区审核"只有超级管理员能进，管理员进直接 403"。原因是 Sa-Token 的角色是**派生值**（`StpInterfaceImpl`）：只有超管同时拥有 `super_admin + admin + teacher`，管理员(1) 只有 `admin + teacher`、教师(2) 只有 `teacher`。不写 OR 时超管三项全中→通过，管理员差一个 `super_admin`→403。<br>**两个条件缺一不可**：注解里的 OR（管**方法级**）**和** 白名单里的路径（管**路由级**）—— 路由规则先执行，教师不在白名单就会先抛 403，注解根本没机会跑。本项目其它 Controller 的多角色注解全都写了 OR，社区审核当初是唯一漏掉的。<br>回归守卫：`AdminPostAuthorizationTest`（把注解交给 Sa-Token **真实的** `SaCheckRoleHandler` 判，不依赖 Spring）+ `CommunityContractTest.everyAdminEndpointAllowsTeacherToo` |
| **🔴 源码里的非 ASCII 字面量被编译坏** | 未说明 | `pom.xml` 声明 `project.build.sourceEncoding`，并在 `maven-compiler-plugin` 的 `<configuration>` 里**再写一次** `<encoding>UTF-8</encoding>`；`LinkSegmenter` 的标点用 `\uXXXX` 转义写死 | 🔴 **真实故障**：本机 JVM 默认编码是 **GBK**，maven-compiler-plugin 沿用平台编码读 `.java`。源文件是 UTF-8，于是 `"、。，；：！？…"` 编译进 class 后变成乱码，链接末尾标点剥离全部失效。既有中文（"下载"、"作业.docx"）都在 GBK 码位范围内，所以这颗雷一直没爆。**实测该插件 3.14.1 不读 `project.build.sourceEncoding` 属性**，必须在插件 `configuration` 里也写一次。回归守卫：`LinkSegmenterTest` |
| **排除 `sa-token-jackson`** | 未提及 | pom 里 `exclude` 掉 Sa-Token 带来的 `sa-token-jackson`，并自建 `SaTokenJsonConfig` 注入基于 **Jackson 3** 的 `SaJsonTemplate` | 🔴 **真实故障**：Sa-Token 1.45 会扫描所有 jar 的 `META-INF/satoken/` 并立即 install 插件，`sa-token-jackson` 的 `install()` 引用 **Jackson 2** 的 `PolymorphicTypeValidator`，而 Boot 4 只有 **Jackson 3**（`tools.jackson`）→ `NoClassDefFoundError` → **应用启动即崩、systemd 无限重启**。Sa-Token 对插件异常 fail-fast，不跳过坏插件，只能排除依赖。回归守卫：`SaTokenStackTest` |
| **OSS 客户端强制 HTTPS** | 未说明 | `OssClientConfig` 显式 `ClientBuilderConfiguration.setProtocol(Protocol.HTTPS)`，并规范化 endpoint 的协议前缀/结尾斜杠 | 🔴 **真实故障**：`aliyun-sdk-oss` 的 `ClientConfiguration` 默认 `Protocol.HTTP`，`generatePresignedUrl` 因此签出 `http://` 直传地址；前端在 https 页面下被浏览器按**混合内容（Mixed Content）**直接拦掉 XHR，前端只报"网络错误，上传中断"、OSS 侧只看到失败请求 —— 两头都像网络问题。同时服务端自身调 OSS 也走明文。回归守卫：`OssClientHttpsTest` |

> ⚠️ **v2.3 / v2.4 的表依赖唯一索引与自增主键单调**：`friend_relation.uk_edge`（防重复申请）、
> `chat_message.id` / `post.id`（游标翻页）。MySQL 8 上无需额外配置。
> 已有库升级请按 §2.1 的顺序补 `migration_v2.3.sql` 与 `migration_v2.4.sql`。

---

## 8. OSS 容器整洁性（怎么保证 Bucket 里没有垃圾）

对象一旦写进 OSS 就会持续产生存储费用，所以「删数据」必须同时「删对象」。本项目有 **4 道防线**：

| # | 场景 | 处理 |
|---|------|------|
| 1 | 彻底删除文件 / 清空回收站 / 回收站到期 | 先删数据库（事务内），**提交后**再删 OSS 对象。顺序不能反：先删对象一旦回滚就会留下「有索引没对象」的坏数据 |
| 2 | 删除账号（`purgeFiles=true`） | 删索引 + 删该用户全部 OSS 对象；头像也一并清掉 |
| 3 | 更换 / 清除头像 | 先传新对象 → 更新数据库 → **删旧对象** |
| 4 | 兜底：`OssReconcileJob`（每日 06:00） | 扫描 `homework/`、`avatar/`、`captcha/` 三个前缀，删除「数据库里查不到引用」且超过 **24 小时宽限期**的对象 |

第 4 道防线是必需的：远程删除可能失败、进程可能被强杀、上传可能中途断电，这些残留不会自己消失。

```powershell
# 建议先 dry-run 看数量，确认合理再正式执行（仅超管）
curl -X POST "http://localhost:8081/api/admin/ops/oss-reconcile?dryRun=true" -H "Authorization: <token>"
curl -X POST "http://localhost:8081/api/admin/ops/oss-reconcile?dryRun=false" -H "Authorization: <token>"
```

**想让「删除」立即清 OSS、不用回收站？** 把 `app.recycle.enabled` 设为 `false`：
删除文件时直接删索引 + 删 OSS 对象，不可还原。默认是 `true`（回收站可还原，30 天后自动彻底清理）。

> ⚠️ **未完成分片**仍然无法由应用清理（OSS 不能全局枚举 uploadId），
> 必须在 Bucket 上配置生命周期规则 `AbortIncompleteMultipartUpload = 3 天`。

## 9. 已知限制

- **未完成分片**无法由应用清理，必须在 OSS 控制台配置生命周期规则
  `AbortIncompleteMultipartUpload = 3 天`；
- 秒传**仅限本人文件**，不跨用户（保护隐私）；跨用户公共素材库见文档 §9.6；
- 公告的"已读"状态存在前端（`sessionStorage`），后端**不记录谁读过哪条** ——
  公告是广播（如"今晚断电维护"），需要 per-user 送达确认的话得另外建表；
- 头像冷却 24 小时是**硬编码常量**（`AvatarRules.CHANGE_INTERVAL_HOURS`），要调整得重新打包；
- **单用户每小时最多申请 120 次上传凭证**（`RateLimiter.STS_HOUR_LIMIT`）：
  一次上传 120 个以上文件的文件夹会被 `40114 发送次数超限` 拦住，需要分批上传或调大该常量；
  人机验证的免验证窗口（10 分钟）与这个限制是两回事，别混淆；
- 搜索为**前缀匹配**（`LIKE 'x%'`）；全模糊匹配需要 FULLTEXT(ngram) 或 ES；
- **好友查找只支持按「用户 ID」精确查**（`GET /friends/search?userId=1002`），
  不支持按姓名 / 学号模糊搜。这是有意的取舍：模糊搜等于把"翻一遍全校人"变成一项功能，
  而 ID 是精确值 —— 得先从名单或对方主页地址（`/user/1002`）拿到。
  代价是多一步"问对方要 ID"，收益是没有枚举面；
- **好友与私聊没有长连接**：所以"对方正在输入""消息已送达"这类实时状态没有实现，
  已读也只到"整条会话"粒度（不做单条回执）。聊天记录**按 id 游标分页**而非页码；
- **聊天内容教师与管理员看不到**，后台没有查看入口，审计日志只记长度。
  这是有意的隐私边界；若将来需要合规审计，得单独设计并明确告知用户；
- **好友关系的并发上限有一个理论敞口**：A 的两个不同好友同时点"同意"时，
  各自锁的是**自己**的用户行，因此 A 可能达到 51 人。彻底关掉需要按 id 升序
  同时锁双方的用户行（防死锁）。当前规模下不值得引入这个复杂度，
  已在 `FriendService.acceptInternal` 的注释里写明；
- **社区不做评论、点赞、转发、图片**：本期只有文字动态 + 链接识别。
  要加图片就要接 OSS 配额与内容审核，那是另一个量级的工作；
- **社区没有举报入口**：违规内容靠审核入口拦截，已发布内容出问题需要管理员
  或作者自行处理。举报/申诉流不在本期范围；
- **审核没有统计页**：只落了 `reviewed_by` / `review_time`（数据库可查），
  后台没有"谁审核了多少条"的页面；
- **已通过的帖子被编辑会暂时从广场消失**（回到待审核）。这是刻意取舍，见 §7；
  若业务上不能接受"编辑导致下线"，需要引入"草稿版本 + 已发布版本"双版本模型，
  工作量会显著上升；
- 教师收作业闭环（班级/作业/提交/批改）属**第二阶段**，文档 §13 已给出模型与预留字段；
- 集成测试需真实 MySQL/Redis/OSS：
  `RUN_INTEGRATION_TESTS=true mvn test -Dtest=ShWorkCloudApplicationTests`
