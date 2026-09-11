# SHWorkCloud 作业云盘 — 项目评审报告

> 评审对象：`docs/SHWordCloud_Standard.md`（v1.1，1592 行） + 当前工程代码
> 评审日期：2026-09-11
> 评审基线：**Spring Boot 4.1.1 + Sa-Token 1.45.0 + MyBatis-Plus 3.5.15**（以现有工程为准，已与需求方确认）
> 评审范围：设计文档完整性、文档与工程一致性、安全性、数据一致性、机房场景适配度、可运维性
> 结论文件：`docs/SHWordCloud_Standard_v2.0.md`（优化后的开发文档）

> 📌 **说明**：本报告在原始版本中被误删后重建。重建时**已把原先作为证据引用的真实密钥做脱敏处理**
> （例如 `LTAI5t7****`），避免"评审报告本身又变成一份密钥泄露源"。

---

## 0. 评审方法与结论摘要

### 0.1 评审方法

| 手段 | 说明 |
|------|------|
| 文档通读 | 逐节精读 v1.1 全 1592 行，逐条比对需求 / 设计 / DDL / 接口 / 代码示例 |
| 工程对照 | 读取 `pom.xml`、`application.yaml`、`config/` 下全部 5 个类、`git status` |
| 交叉验证 | 文档内部自洽性（DDL ↔ 代码 ↔ 接口 ↔ 错误码）、文档与工程一致性 |
| 依赖核实 | 查阅 MyBatis-Plus 官方更新日志、Sa-Token 官方 Release 确认 Boot 4 适配坐标 |
| 实证验证 | 后续实现阶段用 `mvn test` 的 39 个单元测试与 `javap` 反查 SDK 签名，验证了本报告的判断 |

### 0.2 结论摘要

设计文档的**主线设计是好的**：OSS 直传 + STS 动态收窄前缀、逻辑索引与物理存储解耦、物化路径加速子树查询，这三条是同类项目里质量偏上的设计。文档结构清晰、有 mermaid 架构图、有安全清单，作为课程项目文档已经高于平均水平。

但存在三类严重问题：

| 类别 | 严重度 | 概要 |
|------|--------|------|
| **工程现状违规** | 🔴 P0 | `application.yaml` 硬编码了**真实的**数据库密码、QQ 邮箱授权码、OSS AK/SK，且已 `git add`；`.gitignore` 未排除该文件 |
| **技术栈脱节** | 🔴 P0 | 文档写 Boot 3.2 + JWT(JJWT) + `com.example.cloud`，工程实际是 Boot 4.1.1 + Sa-Token + `com.leaqutra.shworkcloud`；MyBatis-Plus 用错 starter 坐标 |
| **代码示例本身有 bug** | 🟠 P1 | 文档 6.3 节 14 段关键代码中，至少 **10 处会直接导致越权、启动失败或运行报错**，照抄即踩坑 |
| **机房场景未适配** | 🟠 P1 | 文档面向"公网个人网盘"，与"学生机房上课存放文件"的实际场景有 **8 处硬冲突** |
| **作业闭环缺失** | 🟡 P2 | 名为"作业云盘"但只有个人网盘，无教师/班级/作业任务/提交模型（已按需求方决定列为第二阶段） |

### 0.3 缺陷统计

| 等级 | 数量 | 含义 |
|------|------|------|
| 🔴 P0 | 7 | 阻断级：不解决就无法安全上线，或工程无法启动 |
| 🟠 P1 | 15 | 会导致数据错误、越权或机房场景直接不可用 |
| 🟡 P2 | 11 | 设计缺口：可运维性、机房适配、非功能章节缺失 |
| ⚪ P3 | 12 | 文档质量与工程一致性问题 |

**总体评价：文档可以作为蓝本继续演进，但不能直接照抄实现。** 建议先执行 P0 的 7 项（约 0.5 人日），再按 v2.0 文档推进 M1。

---

## 1. 🔴 P0 阻断级问题（必须立即处理）

### P0-1 【严重】真实密钥明文硬编码并已进入 Git 暂存区

**证据**：`src/main/resources/application.yaml`（**下列值已脱敏**）

```yaml
26:    url: jdbc:mysql://localhost:3306/solo-helper?useSSL=false&serverTimezone=UTC
28:    password: ******（真实数据库密码，明文）
35:    username: ******@qq.com
36:    password: ******（QQ 邮箱 SMTP 授权码，明文）
56:    access-key-id: LTAI5t7****（阿里云 AK，明文）
57:    access-key-secret: 4mAJ****（阿里云 SK，明文）
58:    bucket-name: sakura-4826
```

```powershell
$ git status --short
AM src/main/resources/application.yaml     # A = 已 git add
```

**为什么这是最严重的问题**：

1. 文档 §8 自己写明"AK/SK 仅服务端持有，通过环境变量注入，禁止入库/入前端包"，**工程直接违反了自家安全规范**；
2. `.gitignore` 只忽略了 `HELP.md`、`target/`、IDE 目录，**没有排除 `application.yaml`**；
3. 虽然仓库尚无 commit（`master` 无提交记录），但**只要执行一次 `git commit`、`git push`，密钥即永久进入历史**，之后删除文件也无效；
4. OSS `access-key-secret` 泄露的后果取决于该 AK 的授权范围——若绑定了 `AliyunOSSFullAccess` 或含 `oss:ListObjects`/`oss:DeleteObject`，攻击者可**遍历并删空整个 Bucket**；
5. QQ 邮箱授权码泄露可被用于**冒名发送钓鱼邮件**，且发件人显示为你自己的邮箱。

**处理动作（按顺序执行，不可跳步）**：

| 步 | 动作 | 说明 |
|----|------|------|
| 1 | **立即轮换全部凭证** | 阿里云 RAM 控制台禁用并删除该 AK，新建最小权限 AK；QQ 邮箱重新生成授权码；修改数据库密码 |
| 2 | 拆分配置 | `application.yaml`（公共，无密钥） + `application-local.yaml`（本地，进 .gitignore） + `application-prod.yaml`（全部 `${ENV}` 占位） |
| 3 | 补 `.gitignore` | 追加 `application-local*.y*ml`、`.env`、`*.p12`、`*.jks` |
| 4 | 清理暂存区 | `git rm --cached src/main/resources/application.yaml` 后重新 `git add` 脱敏版本 |
| 5 | 加防泄漏卡口 | 引入 `gitleaks` / `git-secrets` 到 pre-commit；`.gitignore` 不能防手滑，必须靠扫描 |
| 6 | 提供模板 | 新增 `application-local.yaml.example`，让新同学知道要填哪些项 |

> ⚠️ **不要再等**：凭证一旦被 `git add`，就应当按"已泄露"处理。即使立刻删除文件，本地 `.git/objects` 与 IDE 的 Local History 里仍有副本。

**✅ 实现阶段已完成**：配置已拆分为三层，`.gitignore` 已加固，全仓扫描确认无明文密钥残留。

---

### P0-2 【严重】MyBatis-Plus 使用了错误的 starter，Boot 4 下有启动失败风险

**证据**：`pom.xml`

```xml
5:   <parent>
7:     <artifactId>spring-boot-starter-parent</artifactId>
8:     <version>4.1.1</version>          <!-- Spring Framework 7 -->
...
77:    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>   <!-- ⚠️ boot3 -->
78:    <version>3.5.15</version>
```

**问题**：Spring Boot 4.1.1 基于 **Spring Framework 7**，而 `mybatis-plus-spring-boot3-starter` 面向 Spring Framework 6 时代的 Boot 3。MyBatis-Plus 官方为 Boot 4 提供了**独立坐标**：

- v3.5.13（2025.08.29）：`feat: 新增 spring-boot4 支持`
- v3.5.14（2025.08.29）：`feat: 增加 bom 对 mybatis-plus-spring-boot4-starter 与 mybatis-plus-spring-boot4-starter-test 管理`
- v3.5.15（2025.11.30）：`feat: 支持 SpringBoot 4.0.0`、`feat: 支持 Jackson 3.0`
- v3.5.16（2026.01.11）：`feat: 升级 mybatis-spring 至 4.0.0`

（来源：[MyBatis-Plus 更新日志](https://baomidou.com/resources/changlog/)）

**修正**：改用 `com.baomidou:mybatis-plus-spring-boot4-starter`（有网络时建议 3.5.16+）。

**顺带影响**：Boot 4 默认使用 **Jackson 3**（包名 `tools.jackson`）。文档 §6.3.9 用到的 `JsonUtil.toJson/parse` 需确认底层来源；`pom.xml:144-147` 显式引入 `jackson-databind` 与 Boot 4 的 Jackson 3 并存，可能造成序列化行为不一致。

> 📌 **实现阶段的重要发现**：Jackson 3 **刻意继续依赖** `com.fasterxml.jackson.core:jackson-annotations` 2.x
> （官方 pom 注释："Annotations remain at Jackson 2.x group id"）。
> 因此"Jackson 2/3 混用"的风险只在 **databind** 层面，注解包共用是官方设计。
> 实测：`tools.jackson.core:jackson-databind:3.1.5` 的 pom 依赖 `jackson-annotations:2.21`。

**验证方式**：CI 中加一次冒烟启动（本项目已提供 `RUN_INTEGRATION_TESTS=true` 的上下文测试）。

---

### P0-3 【越权】Sa-Token 角色体系未实现，后台鉴权必然失效或抛异常

**证据**：`src/main/java/com/leaqutra/shworkcloud/config/SaTokenConfigure.java`

```java
22:  SaRouter.match("/admin/**")
23:          .check(r -> StpUtil.checkRoleOr("super_admin", "admin"));
26:  SaRouter.match("/admin/captcha/**")
27:          .check(r -> StpUtil.checkRole("super_admin"));
30:  SaRouter.match("/marketplace/**")          // ⚠️ 与本项目无关
31:          .check(r -> StpUtil.checkRole("user"));
```

**问题**：

1. `StpUtil.checkRoleOr(...)` 依赖 `StpInterface` 实现来返回当前账号的角色列表。**工程中不存在任何 `StpInterface` 实现类**（`config/` 下 5 个文件已全部确认）。缺实现时 Sa-Token 取不到角色，`checkRole` 一律判定"无角色"→ `/admin/**` 对所有人返回 403，后台完全不可用。
2. 角色命名**两套体系并存**：这里用字符串 `"super_admin" / "admin" / "user"`，而文档 §3.5 与 DDL 用数字 `0 / 1 / 9`。两套不统一。
3. `is-log: true`、`timeout: 2592000`（30 天）配置在 `application.yaml`，与文档"JWT 24 小时过期"完全不同。

**修正**：新增 `StpInterfaceImpl`，把数据库数字 role 映射为 Sa-Token 角色标识，并在文档中固定该映射契约。

> ✅ **实现阶段已修复**：`config/StpInterfaceImpl.java` 已落地，映射为
> `9→super_admin/admin/teacher/user`、`1→admin/teacher/user`、`2→teacher/user`、`0→user`。

---

### P0-4 【越权面】`/marketplace/**` 是从别的项目复制来的残留

**证据**：`SaTokenConfigure.java:30-31` 校验了"知识市场"路由；`application.yaml:26` 的数据库名是 **`solo-helper`**——都不是本项目的东西。

**问题**：作业云盘不存在"知识市场"模块。这类残留有三个实际危害：

1. 留下**无人知道其用途的访问控制分支**，将来 `/marketplace/**` 被新接口复用时鉴权语义完全错乱；
2. 数据库名 `solo-helper` 说明这套配置整体是从另一个项目粘贴而来，**其他配置项也可能带错**（P0-6 的时区、P0-7 的路径问题都印证了这一点）；
3. 代码评审时无法解释，属于典型的技术债起点。

**修正**：删除该三行；数据库名改为 `shwork_cloud`；对 `config/` 下 5 个文件做一次**整体重审**。

---

### P0-5 【安全】禁用用户后 Token 仍然有效 30 天

**证据**：`application.yaml:61-68`

```yaml
sa-token:
  timeout: 2592000        # 30 天
  is-concurrent: true
  is-share: false
```

文档 §6.2 有 `PUT /api/admin/users/{id}/status`（禁用/启用用户），但**全文没有任何地方说明"禁用用户后如何让其立即失效"**。

**修正**（三层）：

1. 禁用时 `StpUtil.kickout(userId)` 踢下线；
2. `StpUtil.disable(userId, -1)` 禁止再次登录；
3. 拦截器中对已登录请求做**账号状态二次校验**（60 秒缓存），清缓存必须显式做。

> ✅ **实现阶段已修复**：`UserCache`（60 秒）+ `SaTokenConfigure` 第二步状态校验 + `AdminUserService` 的 kickout/disable/evict 三连。

---

### P0-6 【数据错误】数据库时区设为 UTC，作业时间会整体偏移 8 小时

**证据**：`application.yaml:26`

```yaml
url: jdbc:mysql://localhost:3306/solo-helper?useSSL=false&serverTimezone=UTC
```

而文档 §6.1 给的是 `serverTimezone=Asia/Shanghai`，DDL 里全部是 `DATETIME DEFAULT CURRENT_TIMESTAMP`。

**问题**：对普通网盘只影响"创建时间显示错 8 小时"；但第二阶段引入**作业截止时间**后，`提交时间 > 截止时间` 的判断会整体错 8 小时——**迟交变按时、按时变迟交**。

**修正**：JDBC 改 `serverTimezone=Asia/Shanghai`；MySQL `default-time-zone='+08:00'`；JVM `-Duser.timezone=Asia/Shanghai`；三者统一。

---

### P0-7 【接口 404】`context-path: /api` 与文档接口清单的 `/api/**` 路径冲突

**证据**：

```yaml
server:
  port: 8081
  servlet:
    context-path: /api        # 全局前缀
```

```java
// SaTokenConfigure.java:17-19  —— 拦截器路径不含 context-path
SaRouter.match("/**").notMatch("/auth/**")
```

```
# 文档 §6.2 接口清单
POST /api/auth/register
```

**问题**：若 Controller 照文档写 `@RequestMapping("/api/auth")`，实际 URL = **`/api/api/auth/register`**。文档通篇未说明 `context-path` 的存在，实现者极大概率写出双 `/api`。

**修正**：明确约定——**保留 `context-path: /api`，所有 Controller 只写业务路径**；文档中的"接口路径"统一标注为"对外完整路径"。

> ✅ **实现阶段已落地**：所有 Controller 均只写 `/auth`、`/files`、`/admin/**`，README 与文档都加了路径约定说明。

---

## 2. 🟠 P1 数据与安全设计缺陷

### P1-1 【必现 bug】`uk_sibling_name` 唯一索引在"回收站 + 重建同名"场景必然冲突

**证据**：文档 §4.2 DDL 第 385 行

```sql
UNIQUE KEY uk_sibling_name (user_id, parent_id, name, status)
```

**必现路径**：

| 步 | 操作 | 索引中的行 |
|----|------|-----------|
| 1 | 上传 `作业.docx` | `(1, 0, '作业.docx', 1)` |
| 2 | 删除它（进回收站，status→0） | `(1, 0, '作业.docx', 0)` |
| 3 | 重新上传同名 `作业.docx` | `(1, 0, '作业.docx', 1)` ✅ 不冲突 |
| 4 | 删除新文件（status→0） | ❌ **与第 2 步的行冲突，抛 `Duplicate entry`** |

MySQL 没有部分索引（partial index），无法表达"仅对 status=1 唯一"。

**修正方案（推荐 C）**：虚拟生成列 + 唯一索引

```sql
active_name VARCHAR(255) GENERATED ALWAYS AS (IF(status = 1, name, NULL)) VIRTUAL,
UNIQUE KEY uk_sibling_active (user_id, parent_id, active_name)
```

唯一索引允许多个 NULL，因此回收站记录彼此不冲突，也不与活跃记录冲突。

> ✅ **实现阶段已落地**：DDL 采用方案 C，且 `FileEntry.activeName` 用
> `insertStrategy/updateStrategy = FieldStrategy.NEVER` 保证代码永不写该列。

---

### P1-2 【数据翻倍】`file_entry` 缺 `object_key` 唯一索引，`commit` 不幂等

**触发场景**：前端 commit 超时重试、用户上传后刷新页面、秒传与直传竞争、手动重放。

**后果**：同一 OSS 对象产生多条索引，`used_storage` 被重复累加，用户配额被凭空吃掉一倍。

**修正**：加 `UNIQUE KEY uk_object_key (object_key)` + `commit` 开头按 objectKey 查已存在索引并幂等返回 + 捕获 `DuplicateKeyException` 兜底。

> ✅ **实现阶段已落地**：`UploadService.doCommit` 实现了两层幂等（先查 + 唯一索引兜底）。

---

### P1-3 【安全 + 容量账目】objectKey 由前端生成，服务端只校验前缀

**问题**：

1. 用户可在自己前缀内**故意复用**同一 Key，用 1 字节内容覆盖已入库的 10MB 文件 → `size` 与实际不符；
2. 前端可**伪造 `size`** 骗过配额（`doesObjectExist` 只校验存在性）；
3. 前端生成 Key 还引出 P1-9 的 `crypto.randomUUID` 不可用问题。

**修正（已实现）**：服务端签发上传凭证

```
GET /api/oss/sts → { ..., uploadKey, uploadToken }
POST /api/files/commit { uploadToken, parentId, name, contentType, md5 }
```

服务端从 Redis 取回服务端记录的 `uploadKey`（忽略前端传值），并用 `headObject` 的
`Content-Length` 作为权威 size。

---

### P1-4 【隐性成本】孤儿对象与未完成分片无回收机制

| 泄漏类型 | 成因 | 后果 |
|----------|------|------|
| **孤儿对象** | 直传 OSS 成功后 `commit` 失败（关浏览器 / 断网 / 下课关机） | OSS 有对象、数据库无索引 → 不占配额、不可见、**永久收费** |
| **未完成分片** | 分片上传中断未 `AbortMultipartUpload` | OSS 按"未完成分片存储"**持续收费** |

**修正（三道防线）**：① OSS 生命周期规则 3 天中止未完成分片；② `upload_session` 表 + 每日对账任务回收超 24 小时未 commit 的对象；③ `homework/tmp/` 前缀兜底过期。

> ✅ **实现阶段已落地**：`upload_session` 表 + `OrphanObjectJob`（每日 04:00）+ 超管手工清理接口。
> ⚠️ **未完成分片无法由应用清理**（OSS 不能全局枚举 uploadId），必须配生命周期规则 —— 已写入 README 与文档。

---

### P1-5 【误导】`oss_url` 字段冗余，且把 Bucket/Endpoint 硬编码进业务表

**问题**：私有 Bucket 的规范 URL 直接访问**必然 403**；且把 endpoint 固化进数据行，换域名/CDN 需全表刷新。

**修正**：删除 `file_entry.oss_url` 与 `captcha_image.oss_url`，URL 由 `object_key` + 配置运行时拼装。

> ✅ **实现阶段已落地**：两张表均无 `oss_url`，`OssSignService.baseUrl()` 运行时拼装。

---

### P1-6 【账目漂移】`used_storage` 增量维护缺少对账机制

**修正**：明确定位为**缓存值**，权威值是 `SUM(size) WHERE status IN (0,1)`；每日对账重算；提供管理后台手动重算接口。

> ✅ **实现阶段已落地**：`QuotaService.recalculate` + `StorageReconcileJob`（每日 05:00）+ `/admin/users/{id}/recalc-storage` + `/admin/ops/reconcile-storage`。

---

### P1-7 【越权漏洞】`@RequireRole` 标在 Service 方法上，拦截器读不到

**证据**：

```java
// 文档 §6.3.8 —— 拦截器从 Controller 方法取注解
859:  RequireRole ann = hm.getMethodAnnotation(RequireRole.class);

// 文档 §6.3.13 —— 但注解标在 Service 上
1083: @RequireRole(1)
1084: public IPage<SysUserVO> page(UserQuery q) { ... }
```

`HandlerInterceptor.preHandle` 的 `handler` 参数是 **`HandlerMethod`（Controller 方法）**，永远看不到 Service 层方法的注解。因此这些后台接口就是**裸奔状态**：任何登录用户都能调 `changeRole`、`deleteUser`。

**修正（已实现）**：Sa-Token 路由拦截（粗粒度）+ `@SaCheckRole` 标在 **Controller 方法**上（细粒度），两层防线。

---

### P1-8 【功能不可用】`instant-check` 接口语义与实现矛盾

文档 §6.2 叫"秒传检查"（读），§6.3.4 的实现却执行 `CopyObject` 并建索引（写）。

**修正（已实现）**：重命名为 `POST /api/files/instant-upload`，语义为"尝试秒传，命中直接返回 fileId"，响应 `hit` 字段；命中后前端需刷新容量。

---

### P1-9 【前端直接崩】`crypto.randomUUID()` 在 http 非安全上下文下不存在

文档 §7.3：`crypto.randomUUID().replace(/-/g, '')`。

`crypto.randomUUID()` 属于 Web Crypto API，**仅在安全上下文（HTTPS 或 localhost）可用**。机房学生几乎一定通过 **`http://192.168.x.x:8081`** 访问 → `undefined` → **上传流程 100% 崩溃**，且本地开发完全测不出来。

**修正（已实现）**：objectKey 改由**服务端签发**，前端不再生成 UUID。前端如需本地 ID，用不依赖 Web Crypto 的兜底实现。

---

### P1-10 【必失败】ali-oss `refreshSTSToken` 返回字段名与 SDK 期望不符

文档 §7.3 的 `refreshSTSToken` 返回 `securityToken`，而 ali-oss 需要 `stsToken`。
且 `region` 硬编码 `oss-cn-hangzhou`，工程实际是 `oss-cn-beijing`。

**修正**：显式字段映射 `stsToken: data.securityToken`，`region`/`endpoint` 全部从接口下发。

---

### P1-11 【认知错误】断点续传"刷新页面可续传"与 SDK 实际行为不符

ali-oss 的 `Checkpoint` 含 **`File` 引用**，无法完整序列化进 `localStorage`。
**必须由用户重新选择同一文件**才能重建续传上下文。

**修正**：文档明确边界 —— 同一会话内暂停/继续；跨刷新需重选同一文件后按 `(name,size,lastModified)` 匹配断点续传。**不承诺"刷新后自动续传"**。

---

### P1-12 【体验】机房同出口 IP 下，IP 级限流会"封杀全班"

文档：`if (hour > 5 || day > 10 || ipCnt > 20) throw EMAIL_CODE_LIMIT;`

机房（甚至整个校园网出口）**所有学生共用同一个公网 IP** → 第 21 个学生开始**全班都无法注册**。

**修正（已实现）**：`RateLimiter` 维度改为 **`IP + 账号` 组合**，对命中 `app.security.internal-networks` 的
来源**豁免 IP 维度计数**，公网 IP 阈值放宽到 200/小时。

> ✅ 附带修复了**取真实 IP 的伪造风险**：只在可信代理后读 `X-Forwarded-For` 且取第一跳，
> Nginx 侧用 `$remote_addr` 覆盖而非追加（文档已给出配置）。

---

### P1-13 【容量体验】配额只在 `commit` 时校验，用户可能白传 10GB

**修正（已实现）**：`GET /api/user/quota` 前端预检 + `GET /api/oss/sts` 服务端预检 + `commit` 最终校验（三层）。

---

### P1-14 【搜索性能】`keyword` 模糊搜索无索引支撑

`WHERE name LIKE '%关键字%'` 无法使用 B-Tree 索引。

**修正（已实现）**：改为**前缀匹配** `likeRight`，命中 `idx_user_name (user_id, name)`；全模糊需 FULLTEXT(ngram) 或 ES，记入文档可优化项。

---

### P1-15 【实现期新增】文档中的 OSS SDK 类名不存在

文档 §6.5.7 使用 `new ResponseHeaderParameters().withContentDisposition(...)`。经 `javap` 反查
`aliyun-sdk-oss:3.17.4`，**该类不存在**，真实类名是 `ResponseHeaderOverrides`，且
`GeneratePresignedUrlRequest` 没有 `withXxx` 链式方法，只能逐个 setter。

同类问题还有 §6.3.3 的 `new GenericBucket(...)`（编译不过）。
这类"文档代码与 SDK 版本不匹配"必须靠**真实编译**才能发现 —— 实现阶段已全部修正。

---

## 3. 🟠 P1 机房场景适配缺口

| # | 约束 | 现实情况 | v1.1 的问题 |
|---|------|----------|-------------|
| 1 | **还原卡 / 冰点还原** | 重启后 C 盘复原 | 依赖本地持久化的续传/缓存都不可靠 |
| 2 | **多人共用一台机器** | 一天数十人轮流使用 | Token 存 localStorage → **跨学生会话串号** |
| 3 | **同出口 IP** | 全校共用一个公网 IP | IP 级限流 → **封杀全班**（P1-12） |
| 4 | **课时短（40~45 分钟）** | 存文件时间约 5~10 分钟 | 三步注册流程来不及，邮箱还可能收不到 |
| 5 | **内网 http 访问** | 无证书 | Web Crypto 等 API 不可用（P1-9） |
| 6 | **弱网 / 断网** | 交换机质量参差 | 需要真实可用的重试与续传 |
| 7 | **学生可能无 QQ 邮箱** | 部分学生不用 QQ | 强制 `@qq.com` 会把一部分学生挡在门外 |
| 8 | **下课强制关机** | 直接断电 | 必须有服务端确认的**提交凭证** |

### P2-1 【关键缺口】账号体系与机房实际不匹配

v1.1 把"自助注册"作为**唯一**开户路径，且 `sys_user` **没有 `student_no`/`class_name`/`real_name`**，
也没有名单批量导入。45 分钟一节课让学生现场完成"图形验证 + 收邮件 + 填密码"，不现实。

**修正（已实现）**：**主路径**改为"教师/管理员导入 CSV 名单 → 学号 + 初始密码登录 → 首次登录强制改密"；
自助注册降级为**可开关的辅通道**（默认关闭）。

> ✅ 实现细节：CSV 解析兼容 **UTF-8 BOM 与 GBK**、引号包裹的逗号、CRLF；
> 采用"先全校验再写库"避免写一半失败；逐行独立提交避免一行失败牵连全部。
> 以上均有单元测试覆盖。

### P2-2 【关键缺口】共用电脑的登录态隔离，存在隐私事故风险

文档 §7.1 要求 Token 存 **localStorage** → 学生 A 忘退就下课，学生 B 打开页面**直接进入 A 的网盘**。

**修正**：Token 存 `sessionStorage` + `sa-token.active-timeout: 1800` 空闲自动登出 +
退出彻底清理（含 IndexedDB 断点）+ 常驻身份提示 + 超管"机房清场"接口。

> ✅ 服务端部分已实现：`SaTokenConfigure` 的 `active-timeout` 与
> `POST /api/admin/ops/sessions/flush`（按 IP 前缀批量踢出会话）。

### P2-3 【缺口】缺少提交凭证与核对机制

v1.1 到 `commit` 返回文件 ID 就结束，而机房现实是"学生看到 100% 以为成功了，其实 commit 还没走完"。

**修正（已实现）**：`commit` 返回 `receipt`（提交凭证，如 `20260911-143052-8f3a`）与 `commitTime`，
便于学生记录、教师按凭证核对；前端应做两阶段进度与离开页面拦截。

### P2-4 ~ P2-11 其他机房适配项

| 编号 | 缺口 | 建议 |
|------|------|------|
| P2-4 | "课堂收件码"模式完全未设计 | 列入第二阶段；无注册 3 步完成上交 |
| P2-5 | MD5 秒传在大文件上耗时过长 | 配置 `app.upload.instant-threshold-bytes`（默认 200MB）以上用抽样指纹 |
| P2-6 | 公共素材库缺失 | 同一课件 40 人各传一遍，浪费 40 倍带宽 |
| P2-7 | 浏览器兼容未说明 | 明确 Chrome/Edge ≥ 100，不支持 IE |
| P2-8 | 前端并发上传数未限定 | 单机并发建议 ≤ 2，避免把机房交换机打满 |
| P2-9 | 无教师/机房管理员角色 | 已补 `role=2`（教师），对应 Sa-Token 角色 `teacher` |
| P2-10 | 回收站容量占用未提示 | `/api/user/quota` 已返回 `recycleUsed` |
| P2-11 | 无降级方案 | 已在文档 §9.3 明确各依赖故障时的表现 |

---

## 4. 🟡 P2 非功能与文档结构缺口

| 编号 | 缺口 | 说明 |
|------|------|------|
| P2-12 | 无数据备份与恢复方案 | MySQL 每日全量 + binlog；OSS 建议开版本控制；**备份必须演练** |
| P2-13 | 无监控与告警 | OSS 5xx、STS 失败率、commit 失败率、孤儿对象增长、Redis 连接数 |
| P2-14 | 无容量与成本估算 | 800 人 × 2GB 配额，实际占用约 480GB ≈ 58 元/月；v1.1 的 10GB/人 让成本凭空增 5 倍 |
| P2-15 | 无压测目标 | 建议 60 并发上传、`commit` P95 < 300ms |
| P2-16 | 无测试方案 | 现已有 39 个单元测试；集成测试用 MockMvc + 参数化权限矩阵 |
| P2-17 | 无验收标准 | 已在文档 §10.3 补齐 |
| P2-18 | 审计日志只有承诺没有表 | 已补 `operation_log` DDL + 异步落库 + `AuditService` |
| P2-19 | 无 CI/CD | 建议 `mvn verify` + gitleaks + 前端 build |
| P2-20 | 无优雅降级 | Redis 是强依赖，无降级；邮件故障不影响名单登录（这正是注册降级为辅通道的收益） |
| P2-21 | `HELP.md` 是脚手架模板残留 | 已替换为项目 `README.md` |
| P2-22 | DDL 只在文档里 | 已落地 `src/main/resources/init_sql/init.sql`（含建库语句，一键执行） |

---

## 5. ⚪ P3 工程一致性清单

### 5.1 `pom.xml` 问题

| 行 | 问题 | 修正 |
|----|------|------|
| 77 | `mybatis-plus-spring-boot3-starter` 与 Boot 4 不匹配 | ✅ 已改 `spring-boot4-starter` |
| 115-119 / 127-136 | **`spring-boot-starter-data-redis` 声明了两次** | ✅ 已去重 |
| 130-135 | 排除 `lettuce-core`，但 yaml 配的是 `lettuce.pool` | ✅ 已改 `jedis.pool`（配置才会真正生效） |
| — | 缺 `aliyun-java-sdk-core` | ✅ 已补（用 CommonRequest 调 STS） |
| — | 缺 `spring-boot-starter-validation` | ⚠️ 本地无 hibernate-validator，改为 Service 层手写校验 |
| — | 缺 Guava（文档用 `Lists.partition`） | ✅ 用 Hutool `ListUtil` / 手写分批替代 |
| 101-106 | `poi-ooxml` 引入但无使用场景 | ✅ 已移除（名单导入用 CSV） |
| 144-147 | 显式引入 Jackson 2 `jackson-databind` | ✅ 已移除，改用 `spring-boot-starter-jackson` |

### 5.2 `application.yaml` 问题

| 行 | 问题 | 修正 |
|----|------|------|
| 26 | 库名 `solo-helper` | ✅ 改 `shwork_cloud`（可配置） |
| 26 | `serverTimezone=UTC` | ✅ 改 `Asia/Shanghai` |
| 28/36/56/57 | 真实密钥明文 | ✅ 三层配置拆分 + `.gitignore` + 全仓扫描 |
| 34-46 | 邮件 587 STARTTLS vs 文档 465 SSL | ✅ 统一 587 + STARTTLS |
| 51 | `context-path: /api` 未在文档说明 | ✅ 已在文档 §0.5 与 README 明确路径约定 |
| 11-16 | `lettuce.pool` 与 pom 的 Jedis 冲突 | ✅ 改 `jedis.pool` |
| 61-68 | `timeout: 2592000`（30 天） | ✅ 改 4 小时 + `active-timeout` 30 分钟 |
| — | 缺 `region` / `key-prefix` / `base-url` / `app.*` 全部配置 | ✅ 已补全 `OssProperties`/`StsProperties`/`AppProperties` |

### 5.3 代码问题

| 文件 | 问题 | 修正 |
|------|------|------|
| `config/RedisConfig.java` | 空类 | ✅ 已删除（统一用 Boot 自动配置的 `StringRedisTemplate`） |
| `config/OSSConfig.java` | 字段不全 | ✅ 已重写为 `OssProperties` |
| `config/SaTokenConfigure.java` | 见 P0-3/P0-4 | ✅ 已重写 |
| 包名 | 工程与文档不一致 | ✅ 文档已统一为 `com.leaqutra.shworkcloud` |
| `src/main/resources/standard/` | 开发文档放在 resources 下会被打进 jar | ✅ 已移到 `docs/` |

### 5.4 Git 与仓库问题

| 问题 | 修正 |
|------|------|
| 无 commit | ⚠️ **仍未提交**。**建议立即 `git init` 后首次提交**，否则任何误删都不可恢复（本项目已发生过一次文档误删） |
| `.gitignore` 不完善 | ✅ 已补 `application-local*.yaml`、`.env`、`*.log`、`logs/` 等 |
| `HELP.md` 是模板残留 | ✅ 已改用 `README.md` |
| 无 README | ✅ 已补 |

---

## 6. 文档自身质量问题

| 编号 | 问题 | 建议 |
|------|------|------|
| D-1 | 无目录（TOC） | ✅ v2.0 已补 |
| D-2 | 无变更记录 | ✅ v2.0 已补 |
| D-3 | 无"约束与假设"章节 | ✅ v2.0 §0.4 已补 |
| D-4 | 包名与工程不符 | ✅ 已统一 |
| D-5 | 技术栈整章过时 | ✅ v2.0 已改为 Boot 4.1.1 + Sa-Token |
| D-6 | 错误码表不完整 | ✅ v2.0 §14.1 已补全，且与 `ErrorCode` 枚举逐条对齐 |
| D-7 | 无接口请求/响应示例 | ✅ v2.0 §14.3 已补 |
| D-8 | 无配置项全表 | ✅ v2.0 §14.2 已补 |
| D-9 | 无验收标准 | ✅ v2.0 §10.3 已补 |
| D-10 | §3.6 与 §6.3.11 的 passToken 流程描述不一致 | ✅ v2.0 已统一为"注册时校验 `cap:pass:used:{email}` 标记" |
| D-11 | `email_verified` 字段无意义 | ✅ v2.0 已删除该字段 |
| D-12 | `new GenericBucket(...)` 不是 SDK 的 API | ✅ v2.0 已改用 `getObjectMetadata` |

---

## 7. 建议的修复节奏

| 阶段 | 内容 | 状态 |
|------|------|------|
| **S0** | P0-1 密钥轮换 + 配置分层 + `.gitignore` + pre-commit 扫描 | ✅ 配置侧已完成（**凭证轮换需你在控制台执行**） |
| **S1** | P0-2 换 starter、P0-3 补 `StpInterface`、P0-4 清残留、P0-6 时区、P0-7 路径约定 | ✅ 已完成 |
| **S2** | DDL 修正版 + 账号体系 + 名单导入 + 登录 | ✅ 已完成 |
| **S3** | P1-1 ~ P1-15 修正 | ✅ 已完成 |
| **S4** | 机房专项（服务端部分） | ✅ 已完成（前端适配待做） |
| **S5** | 非功能补齐 | ⚠️ 部分完成（监控/备份/压测需运维环境） |

---

## 8. 评审结论

1. **设计骨架优秀，实现细节不可照抄**：主线保持，但 §6.3 的关键代码至少 10 处会直接出问题。
2. **最紧急的是 P0-1 密钥泄露** —— 配置已在代码侧脱敏，但**凭证轮换必须你在阿里云/QQ 邮箱控制台执行**。
3. **文档最大的结构性缺口是"机房场景"与"账号体系"**：P1-12（IP 限流封杀全班）与
   P2-2（共用电脑隐私事故）是**上线即会暴露**的问题，现已在实现中修正。
4. **三条必须在建表前改掉的数据契约**：`uk_sibling_name`（P1-1）、`object_key` 唯一 + commit 幂等（P1-2）、
   服务端签发 objectKey（P1-3）—— 已在 `init_sql/init.sql` 中落地。
5. **"作业"闭环按已确认范围放到第二阶段**（v2.0 §13），表结构已预留 `class_name` / `student_no` 字段。

优化后的完整文档见 **`SHWordCloud_Standard_v2.0.md`**。
