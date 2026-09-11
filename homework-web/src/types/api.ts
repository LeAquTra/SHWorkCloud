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

// ---------------------------------------------------------------- 文件

export interface FileItemVO {
  id: number
  name: string
  /** 后端 record 组件名为 folder，故 JSON 字段是 folder 而不是 isFolder */
  folder: boolean
  size: number
  suffix: string | null
  contentType: string | null
  createTime: string
  updateTime: string
  previewable: boolean
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
