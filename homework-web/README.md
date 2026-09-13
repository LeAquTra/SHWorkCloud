# 作业云盘 · 前端

学生机房文件保存系统的前端，按 [`docs/后端接口手册.md`](docs/后端接口手册.md) 与
[`docs/前端对接指南.md`](docs/前端对接指南.md) 实现。

技术栈：**Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus**（离线安装，无外网依赖）。

---

## 快速开始

```powershell
npm install          # 依赖已随仓库提供，可跳过
npm run dev          # http://localhost:5173，/api 由 Vite 代理到 http://localhost:8081
npm run build        # 产物在 dist/，交给 Nginx 同源托管
npm run preview      # 本地预览构建产物
npx tsc --noEmit     # 只检查 .ts 模块（模板类型由 vue-tsc 负责，本项目未装）
```

`.env` 里的可调项：

| 变量 | 说明 |
|------|------|
| `VITE_API_BASE` | API 前缀，开发期保持 `/api` 走 Vite 代理 |
| `VITE_UPLOAD_PARALLEL` | 单机分片并发数，机房建议 `2`（共享出口带宽） |
| `VITE_INSTANT_THRESHOLD_MB` | 超过该大小改用抽样指纹，需与后端 `app.upload.instant-threshold-bytes` 一致 |
| `VITE_CLASS_END_TIME` | 本节课下课时间（`HH:mm`），配置后会在下课前弹提醒确认文件已保存 |

---

## 页面与接口

| 路由 | 页面 | 主要接口 |
|------|------|----------|
| `/login` | 登录 + 自助注册弹窗 | `GET /auth/register-config`、`POST /auth/login`、`/auth/captcha*`、`/auth/email-code`、`/auth/register` |
| `/change-password` | 修改密码（首登强制） | `POST /auth/password` |
| `/` | 我的网盘 | `/files`、`/files/tree`、`/files/breadcrumb`、`/folders`、`/files/rename|move|copy`、`DELETE /files`、`/files/{id}/download-url` |
| `/recycle` | 回收站 | `GET /recycle`、`/recycle/restore`、`/recycle/purge`、`/recycle/empty` |
| `/album` | 相册 | `GET /images` |
| `/profile` | 个人信息 | `GET|PUT /user/profile`、`POST|DELETE /user/avatar`、`GET /user/quota` |
| `/admin/*` | 管理后台 | `/admin/students/*`、`/admin/users/*`、`/admin/captchas/*`、`/admin/ops/*` |
| 在线阅览弹窗 | 按 `viewType` 分流 | `GET /files/{id}/preview-url`（image/pdf/video/audio）、`GET /files/{id}/text`（text/office） |

---

## 主题：明暗两套 + 跟随系统

- 所有界面只引用 `src/styles/tokens.css` 里的 `--sc-*` 语义变量；
  亮色定义在 `:root`，暗色定义在 `html.dark`，组件里**没有任何主题分支**。
- 同一份文件把 Element Plus 的 `--el-*` 变量桥接到 `--sc-*` 上，
  因此表格、弹窗、表单、消息等内置组件自动跟随主题，无需逐个覆盖。
- `src/stores/theme.ts` 提供 `light / dark / auto` 三态，偏好存 `localStorage`；
  `index.html` 里有一小段内联脚本在首屏渲染前就打好 `html.dark`，避免暗色用户看到白屏闪烁。
- 顶栏的 `ThemeToggle` 是三段式滑动开关；`auto` 会监听系统配色变化。

> 为什么 token 存 `sessionStorage` 而主题存 `localStorage`：
> token 属于"本次上机的会话状态"，共用电脑上必须随标签页关闭失效；
> 主题只是这台机器的显示偏好，不属于敏感信息。

配色：品牌主色 indigo → violet 渐变，中性面取自同一套灰度，
背景带一层极光渐变；阴影、圆角、动效曲线统一收敛在 token 里。

---

## 几处刻意的实现选择

| 位置 | 选择 | 原因 |
|------|------|------|
| `src/api/http.ts` | token 存 `sessionStorage`，请求头字段名 `Authorization`，**不加 Bearer** | 共用电脑 + 后端约定；`40100/40117/40119/40300` 分别做跳转与提示 |
| `src/api/index.ts` | `download-url` / `preview-url` 的类型是 `{ url }` 而不是 `string` | 与接口手册一致 |
| `src/api/index.ts` | `/auth/captcha` 先 POST，遇 404/405 退回 GET | 手册写 GET、对接指南写 POST，两边都兼容 |
| `src/utils/format.ts` | `resolveViewType()` 优先用服务端下发的 `viewType` | 手册 §0.4 明确要求前端不要自己维护后缀白名单；本地推断只作兜底 |
| `src/components/PreviewDialog.vue` | 图片/PDF/视频用**签名地址** | `<img>` / `<video>` 无法携带 `Authorization` 头 |
| `src/utils/uploader.ts` | 指纹在 Web Worker 里算，用抽样指纹；不用 `crypto.subtle` / `crypto.randomUUID` | 内网 http 不是安全上下文，这些 API 不存在 |
| `src/utils/uploader.ts` | 分片 PUT 的 `Content-Type` 用响应里返回的值，`ETag` 去掉双引号且按 `partNumber` 升序提交 | OSS V1 签名把 `Content-Type` 计入待签串，不一致会 `SignatureDoesNotMatch` |
| `src/stores/uploader.ts` | `markRaw(file)` | 被 Vue 代理后的 `File` 调 `slice()` 会抛 `Illegal invocation` |
| `src/types/api.ts` | 角色判断用集合（`STAFF_ROLES` / `ADMIN_ROLES`）而非大小比较 | 后端角色编号不是有序等级：管理员 `1` 比教师 `2` 小，但权限更高 |
| `src/composables/useIdleLogout.ts` | 提前 1 分钟提醒，60 秒后主动登出**并跳登录页** | 只清 token 不跳转，用户会停在"看起来还登录着"的页面，这最危险 |
| `src/views/FileManagerView.vue` | 有未完成任务时 `beforeunload` 拦截；两阶段进度（上传中 / 正在登记）分开显示 | 下课前最容易漏掉；OSS 传完 ≠ 保存成功 |
| `src/views/ProfileView.vue` | 只提交真正改动过的字段，清空签名传 `null` | `PUT /user/profile` 按"请求体里有没有这个 key"判定语义 |

---

## 后端尚未提供、暂未实现的能力

见接口手册 §8：缩略图、图片宽高、Word/PPT 完整版面、旧版 `.ppt` 预览、xlsx 结构化读取、
打包下载。相册网格用固定比例 + `object-fit: cover` + `loading="lazy"` 自行处理缩放；
批量下载需要逐个调 `download-url`，前端未做（浏览器会拦截连续下载）。
