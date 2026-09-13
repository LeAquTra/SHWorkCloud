/**
 * 与后端对齐的类型定义。
 * 后端统一返回 { code, message, data }，HTTP 200 承载业务码；
 * 仅未登录 401、无权限 403 走 HTTP 状态码。
 */

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface PageVO<T> {
  page: number
  size: number
  total: number
  records: T[]
}

/** 后端下发的渲染类型，见接口手册 §0.4 */
export type ViewType = 'image' | 'pdf' | 'video' | 'audio' | 'text' | 'office' | 'none'

// ---------------------------------------------------------------- 认证

export interface LoginVO {
  token: string
  tokenName: string
  userId: number
  username: string
  realName: string | null
  role: number
  mustChangePassword: boolean
}

/** `GET /auth/register-config`：决定登录页要不要显示注册入口、要不要图片验证码 */
export interface RegisterConfigVO {
  registerEnabled: boolean
  requireImageCaptcha: boolean
  mailEnabled: boolean
  /** 后端的 Java 正则，前端拿来做即时校验；用 try/catch 包住，避免个别语法不兼容 */
  emailPattern: string
  mailHint: string | null
}

export interface CaptchaVO {
  captchaId: string
  /** 1 字符输入 / 2 单选 / 3 点选 */
  type: number
  imageUrl: string
  width: number
  height: number
  prompts: string[]
}

// ---------------------------------------------------------------- 用户

export interface UserProfileVO {
  userId: number
  username: string
  studentNo: string | null
  realName: string | null
  className: string | null
  nickname: string | null
  /** 头像在 OSS 的 ObjectKey */
  avatarKey: string | null
  /** 1 小时有效的签名地址，可直接放进 <img src>（私有 Bucket 必须签名） */
  avatarUrl: string | null
  /** 头像版本串，用于换头像后绕过缓存 */
  avatarVersion: string | null
  signature: string | null
  gender: number
  birthday: string | null
  email: string | null
  role: number
  quota: number
  used: number
  free: number
  recycleUsed: number
  idleLogoutMinutes: number
  checkoutWarnMinutes: number
}

export interface QuotaVO {
  quota: number
  used: number
  free: number
  recycleUsed: number
}

/** 部分更新个性属性：不传的键不会被改动，传 null 表示清空 */
export interface ProfileUpdateVO {
  nickname?: string
  signature?: string | null
  gender?: number | null
  birthday?: string | null
}

// ---------------------------------------------------------------- 文件

export interface FileItemVO {
  id: number
  name: string
  /** 后端 record 组件名为 folder，故 JSON 字段是 folder 而不是 isFolder */
  folder: boolean
  size: number
  suffix: string | null
  contentType: string | null
  parentId?: number
  /** 决定用哪种方式渲染，**优先用它**，不要自己维护后缀白名单 */
  viewType?: ViewType
  previewable?: boolean
  createTime: string
  updateTime: string
}

export interface FolderNodeVO {
  id: number
  name: string
  children: FolderNodeVO[]
}

export interface BreadcrumbVO {
  id: number
  name: string
}

/** 签名地址类响应：download-url / preview-url 都返回 { url } */
export interface UrlVO {
  url: string
}

/** `GET /files/{id}/text`：文本 / Office 正文 */
export interface TextContentVO {
  id: number
  name: string
  suffix: string | null
  viewType: ViewType
  size: number
  /** 服务端实际使用的编码（UTF-8 / GBK），用于提示而不是用来解码 */
  charset: string | null
  content: string
  /** true 表示超过 maxChars 已截断，界面要提示下载查看完整文件 */
  truncated: boolean
  maxChars: number
  hint: string | null
}

/** `GET /images`：相册项，已带 1 小时签名地址 */
export interface ImageItemVO {
  id: number
  name: string
  suffix: string | null
  size: number
  parentId: number
  viewType: ViewType
  /** 1 小时签名地址，可直接给 <img src> */
  previewUrl: string
  /** 需要自行带 Authorization 头时的流式地址 */
  previewApiUrl: string
  createTime: string
  updateTime: string
}

// ---------------------------------------------------------------- 上传

export interface UploadTicketVO {
  uploadToken: string
  objectKey: string
  partSize: number
  partCount: number
  expireSeconds: number
  instantThresholdBytes: number
  maxFileSizeBytes: number
}

export interface PutUrlVO {
  url: string
  objectKey: string
  /** 签名时使用的 Content-Type，PUT 时必须原样携带，否则 OSS 报 SignatureDoesNotMatch */
  contentType: string
  expireSeconds: number
}

export interface PartUrlVO {
  partNumber: number
  url: string
}

export interface PartUrlsVO {
  /** 每个分片 PUT 必须携带的 Content-Type */
  contentType: string
  urls: PartUrlVO[]
}

export interface UploadedPartVO {
  partNumber: number
  etag: string
  size: number
}

export interface CommitVO {
  fileId: number | null
  name: string | null
  size: number
  receipt: string | null
  commitTime: string | null
  renamed: boolean
  duplicated: boolean
  /** false 表示秒传未命中，需要走正常上传 */
  hit: boolean
}

// ---------------------------------------------------------------- 后台

export interface AdminUserVO {
  id: number
  username: string
  studentNo: string | null
  realName: string | null
  className: string | null
  email: string | null
  role: number
  status: number
  quota: number
  used: number
  lastLoginTime: string | null
  lastLoginIp: string | null
  createTime: string
}

/**
 * 后台代改用户资料（`PUT /admin/users/{id}`）。
 *
 * **部分更新语义**：只有出现在对象里的键才会被修改，其余保持不变；
 * 传空串 `''` 表示**清空**该字段。所以不要图省事把整行对象直接扔进来 ——
 * 那会把没想改的字段也一起清掉。
 */
export interface AdminUpdateProfileReq {
  realName?: string
  studentNo?: string
  className?: string
  email?: string
  nickname?: string
}

export interface ResetPasswordVO {
  userId: number
  username: string
  initialPassword: string
}

export interface ImportFailureVO {
  row: number
  studentNo: string
  type: 'SKIP' | 'FAIL'
  reason: string
}

export interface ImportResultVO {
  total: number
  success: number
  skipped: number
  failed: number
  defaultPasswordUsed: boolean
  initialPassword: string | null
  failures: ImportFailureVO[]
}

export interface CaptchaImageVO {
  id: number
  type: number
  objectKey: string
  answer: string
  dataJson: string | null
  width: number
  height: number
  weight: number
  usedCount: number
  status: number
  remark: string | null
  createdBy: number
  createTime: string
  updateTime: string
}

export interface OrphanVO {
  sessionId: number
  userId: number
  objectKey: string
  createTime: string
}

export interface ReconcileVO {
  checkedUsers: number
  correctedUsers: number
  maxDiffBytes: number
}

export interface SessionFlushVO {
  kickedCount: number
}

// ---------------------------------------------------------------- 角色

/** 与后端 sys_user.role 一致：0 学生 / 1 管理员 / 2 教师 / 9 超管 */
export const ROLE_STUDENT = 0
export const ROLE_ADMIN = 1
export const ROLE_TEACHER = 2
export const ROLE_SUPER_ADMIN = 9

export const ROLE_LABELS: Record<number, string> = {
  [ROLE_STUDENT]: '学生',
  [ROLE_TEACHER]: '教师',
  [ROLE_ADMIN]: '管理员',
  [ROLE_SUPER_ADMIN]: '超级管理员',
}

/**
 * 角色集合。
 *
 * ⚠️ 后端的角色编号**不是有序等级**：0 学生 / 1 管理员 / 2 教师 / 9 超管。
 * 管理员(1) 的数值比教师(2) 小，却拥有更多权限。所以判断"能不能进"
 * 必须用集合包含，而不是 `role >= minRole` 这种大小比较。
 */
export const STAFF_ROLES: number[] = [ROLE_TEACHER, ROLE_ADMIN, ROLE_SUPER_ADMIN]
export const ADMIN_ROLES: number[] = [ROLE_ADMIN, ROLE_SUPER_ADMIN]
export const SUPER_ADMIN_ROLES: number[] = [ROLE_SUPER_ADMIN]

export const GENDER_LABELS: Record<number, string> = {
  0: '未设置',
  1: '男',
  2: '女',
}
