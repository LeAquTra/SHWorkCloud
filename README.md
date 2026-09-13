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

# 如果你的库是早期版本建的（缺个性属性/头像字段），改用增量迁移：
# mysql -uroot -p shwork_cloud < src/main/resources/init_sql/migration_v2.1.sql
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
     默认**不要求图片验证码**，所以全新部署、题库为空时也能注册。
     若要更强防机刷，把 `app.register.require-image-captcha` 设为 `true`
     并先在题库里上传题目（题库为空时会被 40104 挡住）。
   - **名单导入**（机房批量开户）：进「名单导入」下载 CSV 模板 → 填学号/姓名/班级 → 导入，
     学生用「学号 + 初始密码」登录，首次登录强制改密。
3. 学生登录后可自行修改**个性属性**（昵称/头像/签名/性别/生日）
4. 上传与下载：`POST /api/oss/ticket` → 直传 OSS → `POST /api/files/commit`；
   下载用 `GET /api/files/{id}/download`（支持断点续传）

---

## 3. 构建说明（重要：本机为离线环境）

### 3.1 已验证可用的命令

```bash
mvn -o -Dmaven.repo.local=%USERPROFILE%\.m2\repository test      # 163 个单元测试
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

## 4. 项目结构

```
SHWorkCloud/
├── docs/                                    设计文档与评审报告
├── src/main/resources/
│   ├── application.yaml                     公共配置（无密钥）
│   ├── application-local.yaml.example       本地模板
│   ├── application-prod.yaml                生产（全 ${ENV}，缺失即启动失败）
│   └── init_sql/{init.sql,migration_v2.1.sql}  一键初始化与增量迁移脚本
├── src/main/java/com/leaqutra/shworkcloud/
│   ├── common/                              R / ErrorCode / BizException / 全局异常处理
│   ├── config/                              OSS・STS・app 配置、Sa-Token、MyBatis-Plus
│   ├── security/                            登录上下文、角色缓存、限流、密码、CIDR
│   ├── entity/ mapper/                      6 张表
│   ├── dto/ vo/                             请求与响应模型
│   ├── service/                             业务服务 + job/ 定时任务
│   └── controller/                          REST 接口（admin/ 为后台）
├── src/test/java/                           单元测试（108 个用例）
└── homework-web/                            前端（Vue 3 + Vite + TS + Element Plus）
    ├── src/api/                             axios 封装 + 拦截器（Token 走 sessionStorage）
    ├── src/utils/uploader.ts                预签名直传：单次 PUT / 分片 / 断点续传 / 速率
    ├── src/utils/md5.ts                     纯 TS MD5（有 RFC 向量验证）
    ├── src/workers/md5.worker.ts            分块计算指纹，不卡界面
    ├── src/stores/                          user（会话隔离）/ uploader（上传队列）
    ├── src/composables/useIdleLogout.ts     空闲自动登出
    └── src/views/                           登录、改密、网盘、后台（用户/导入/题库/运维）
```

---

## 5. 接口速查

对外完整路径含 `context-path=/api`；Controller 里只写业务路径，**不要重复写 `/api`**。

| 模块 | 接口 | 说明 |
|------|------|------|
| 认证 | `POST /api/auth/login` | 学号/用户名 + 密码 |
| 认证 | `POST /api/auth/logout` | 退出 |
| 认证 | `POST /api/auth/password` | 改密（首登强制也走这里） |
| 上传 | `GET /api/oss/sts` | 签发 STS 临时凭证 + 服务端 uploadKey/uploadToken |
| 上传 | `POST /api/files/instant-upload` | 尝试秒传 |
| 上传 | `POST /api/files/commit` | 直传完成后建索引（**幂等**） |
| 文件 | `GET /api/files` | 目录列表 / 分类 / 前缀搜索 |
| 文件 | `GET /api/files/tree`、`/files/breadcrumb` | 文件夹树、面包屑 |
| 文件 | `POST /api/folders`、`PUT /files/rename`、`POST /files/move`、`POST /files/copy` | 增改 |
| 文件 | `DELETE /api/files` | 批量进回收站 |
| 文件 | `GET /api/files/{id}/download-url`、`/preview-url` | 10 分钟签名 URL |
| 回收站 | `GET /api/recycle`、`POST /recycle/restore`、`DELETE /recycle/purge`、`/recycle/empty` | — |
| 用户 | `GET /api/user/profile`、`PUT /user/profile`、`GET /user/quota` | 资料/个性属性/容量 |
| 头像 | `POST /api/user/avatar`、`DELETE /api/user/avatar`、`GET /api/user/avatar/{userId}` | 仅 JPG/PNG、≤5MB、存 OSS；换头像自动删旧对象 |
| 在线阅读 | `GET /api/files/{id}/preview-url`、`GET /api/files/{id}/preview`、`GET /api/files/{id}/text`（含 `html` 原格式）、`GET /api/files/{id}/embedded-images` | 图片/PDF/视频/音频流式预览（支持 Range）；**docx 渲染成结构化 HTML、xlsx 渲染成 HTML 表格**；docx/pptx 内嵌图片以 data URL 返回 |
| 图片管理 | `GET /api/images` | 跨目录相册列表，每项已带签名预览地址 |
| 后台 | `GET /api/admin/users`、`PUT /admin/users/{id}/{status,quota,reset-password,role}`、`PUT /admin/users/{id}`（代改资料，部分更新） | 用户管理 |
| 后台 | `GET /api/admin/students/import-template`、`POST /admin/students/import` | 名单导入 |
| 后台 | `/api/admin/captchas/**` | 题库管理 |
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
| **自定义头像** | `avatar` 是文本 URL 字段 | 改为服务端上传 OSS，DB 存 `avatar_key` | 需求：仅 JPG/PNG、≤5MB、存 OSS；且换头像要能删掉旧对象 |
| **删除即清 OSS** | 仅彻底删除才删 OSS | `app.recycle.enabled=false` 时删除即彻底删除；删用户/头像/题目也删 OSS | 需求：保证 OSS 容器整洁 |
| **OSS 对账** | 无 | 每日扫 `homework/`、`avatar/`、`captcha/` 清理无引用对象（24h 宽限） | 远程删除可能失败，需要兜底 |
| **排除 `sa-token-jackson`** | 未提及 | pom 里 `exclude` 掉 Sa-Token 带来的 `sa-token-jackson`，并自建 `SaTokenJsonConfig` 注入基于 **Jackson 3** 的 `SaJsonTemplate` | 🔴 **真实故障**：Sa-Token 1.45 会扫描所有 jar 的 `META-INF/satoken/` 并立即 install 插件，`sa-token-jackson` 的 `install()` 引用 **Jackson 2** 的 `PolymorphicTypeValidator`，而 Boot 4 只有 **Jackson 3**（`tools.jackson`）→ `NoClassDefFoundError` → **应用启动即崩、systemd 无限重启**。Sa-Token 对插件异常 fail-fast，不跳过坏插件，只能排除依赖。回归守卫：`SaTokenStackTest` |
| **OSS 客户端强制 HTTPS** | 未说明 | `OssClientConfig` 显式 `ClientBuilderConfiguration.setProtocol(Protocol.HTTPS)`，并规范化 endpoint 的协议前缀/结尾斜杠 | 🔴 **真实故障**：`aliyun-sdk-oss` 的 `ClientConfiguration` 默认 `Protocol.HTTP`，`generatePresignedUrl` 因此签出 `http://` 直传地址；前端在 https 页面下被浏览器按**混合内容（Mixed Content）**直接拦掉 XHR，前端只报"网络错误，上传中断"、OSS 侧只看到失败请求 —— 两头都像网络问题。同时服务端自身调 OSS 也走明文。回归守卫：`OssClientHttpsTest` |

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
- 搜索为**前缀匹配**（`LIKE 'x%'`）；全模糊匹配需要 FULLTEXT(ngram) 或 ES；
- 教师收作业闭环（班级/作业/提交/批改）属**第二阶段**，文档 §13 已给出模型与预留字段；
- 集成测试需真实 MySQL/Redis/OSS：
  `RUN_INTEGRATION_TESTS=true mvn test -Dtest=ShWorkCloudApplicationTests`
