import { get, post, put, del, getBlob } from './http'
import type {
  AdminUserVO,
  BreadcrumbVO,
  CaptchaImageVO,
  CommitVO,
  FileItemVO,
  FolderNodeVO,
  ImportResultVO,
  LoginVO,
  OrphanVO,
  PageVO,
  PartUrlsVO,
  PutUrlVO,
  QuotaVO,
  ReconcileVO,
  ResetPasswordVO,
  SessionFlushVO,
  UploadTicketVO,
  UploadedPartVO,
  UserProfileVO,
} from '@/types/api'

// ---------------------------------------------------------------- 认证

export const authApi = {
  login: (login: string, password: string) => post<LoginVO>('/auth/login', { login, password }),
  logout: () => post<void>('/auth/logout'),
  changePassword: (oldPassword: string, newPassword: string, confirmPassword: string) =>
    post<void>('/auth/password', { oldPassword, newPassword, confirmPassword }),
  /** 可选通道：自助注册（后端 app.register.enabled 为 false 时返回 40122） */
  captcha: () =>
    post<{
      captchaId: string
      type: number
      imageUrl: string
      width: number
      height: number
      prompts: string[]
    }>('/auth/captcha', {}),
  verifyCaptcha: (body: { captchaId: string; answer?: string; clicks?: { x: number; y: number }[] }) =>
    post<{ captchaPassToken: string }>('/auth/captcha/verify', body),
  sendEmailCode: (email: string, captchaPassToken: string) =>
    post<void>('/auth/email-code', { email, captchaPassToken }),
  register: (body: { email: string; emailCode: string; password: string; username?: string }) =>
    post<void>('/auth/register', body),
}

// ---------------------------------------------------------------- 用户

export const userApi = {
  profile: () => get<UserProfileVO>('/user/profile'),
  /** 部分更新个性属性：不传的键不会被改动；传 null 表示清空 */
  updateProfile: (body: {
    nickname?: string
    signature?: string | null
    gender?: number | null
    birthday?: string | null
  }) => put<UserProfileVO>('/user/profile', body),

  /** 上传/更换头像（仅 JPG/PNG，≤5MB）；服务端会自动删掉旧头像对象 */
  uploadAvatar: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post<UserProfileVO>('/user/avatar', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },

  /** 清除头像（同时删除 OSS 对象） */
  clearAvatar: () => del<UserProfileVO>('/user/avatar'),
  quota: () => get<QuotaVO>('/user/quota'),
}

// ---------------------------------------------------------------- 文件

export interface FileQuery {
  parentId?: number
  category?: string
  keyword?: string
  page?: number
  size?: number
}

export const fileApi = {
  list: (query: FileQuery) => get<PageVO<FileItemVO>>('/files', query as Record<string, unknown>),
  tree: () => get<FolderNodeVO[]>('/files/tree'),
  breadcrumb: (id: number) => get<BreadcrumbVO[]>('/files/breadcrumb', { id }),
  createFolder: (parentId: number, name: string) => post<number>('/folders', { parentId, name }),
  rename: (id: number, name: string) => put<void>('/files/rename', { id, name }),
  move: (id: number, targetParentId: number) => post<void>('/files/move', { id, targetParentId }),
  copy: (id: number, targetParentId: number, name?: string) =>
    post<void>('/files/copy', { id, targetParentId, name }),
  softDelete: (ids: number[]) => del<number>('/files', { ids }),
  downloadUrl: (id: number) => get<string>(`/files/${id}/download-url`),
  previewUrl: (id: number) => get<string>(`/files/${id}/preview-url`),

  recycleList: (page = 1, size = 50) => get<PageVO<FileItemVO>>('/recycle', { page, size }),
  restore: (ids: number[]) => post<number>('/recycle/restore', { ids }),
  purge: (ids: number[]) => del<number>('/recycle/purge', { ids }),
  emptyRecycle: () => del<number>('/recycle/empty'),
}

// ---------------------------------------------------------------- 上传

export const uploadApi = {
  /** 申请上传凭证：服务端确定 ObjectKey 并签发一次性 uploadToken */
  ticket: (body: { name: string; size: number; contentType?: string }) =>
    post<UploadTicketVO>('/oss/ticket', body),

  /** 小文件单次 PUT 的预签名 URL；返回的 contentType 必须原样发送 */
  putUrl: (uploadToken: string, contentType?: string) =>
    post<PutUrlVO>('/oss/put-url', { uploadToken, contentType }),

  initMultipart: (uploadToken: string, contentType?: string) =>
    post<{ uploadId: string }>('/oss/multipart/init', { uploadToken, contentType }),

  /** 批量取分片 URL；响应里的 contentType 必须由每个分片 PUT 原样携带 */
  partUrls: (uploadToken: string, uploadId: string, partNumbers: number[]) =>
    post<PartUrlsVO>('/oss/multipart/part-urls', { uploadToken, uploadId, partNumbers }),

  completeMultipart: (uploadToken: string, uploadId: string, parts: { partNumber: number; etag: string }[]) =>
    post<{ objectKey: string }>('/oss/multipart/complete', { uploadToken, uploadId, parts }),

  abortMultipart: (uploadToken: string, uploadId: string) =>
    post<void>('/oss/multipart/abort', { uploadToken, uploadId }),

  uploadedParts: (uploadToken: string, uploadId: string) =>
    get<UploadedPartVO[]>('/oss/multipart/parts', { uploadToken, uploadId }),

  /** 秒传尝试；hit=false 表示未命中，需要正常上传 */
  instantUpload: (body: { md5: string; parentId: number; name: string; size: number; contentType?: string }) =>
    post<CommitVO>('/files/instant-upload', body),

  /** 直传完成后建立索引（幂等） */
  commit: (body: {
    uploadToken: string
    parentId: number
    name: string
    contentType?: string
    md5?: string
  }) => post<CommitVO>('/files/commit', body),
}

// ---------------------------------------------------------------- 后台

export const adminApi = {
  users: (query: Record<string, unknown>) => get<PageVO<AdminUserVO>>('/admin/users', query),
  classes: () => get<string[]>('/admin/users/classes'),
  changeStatus: (id: number, status: number) => put<void>(`/admin/users/${id}/status`, { status }),
  updateQuota: (id: number, quotaBytes: number) =>
    put<void>(`/admin/users/${id}/quota`, { quotaBytes }),
  resetPassword: (id: number) => put<ResetPasswordVO>(`/admin/users/${id}/reset-password`),
  resetPasswordBatch: (ids: number[]) => put<number>('/admin/users/reset-password-batch', { ids }),
  recalcStorage: (id: number) => put<number>(`/admin/users/${id}/recalc-storage`),
  changeRole: (id: number, role: number) => put<void>(`/admin/users/${id}/role`, { role }),
  deleteUser: (id: number, purgeFiles = false) =>
    del<void>(`/admin/users/${id}`, undefined, { purgeFiles }),

  importTemplate: () => getBlob('/admin/students/import-template'),
  importStudents: (file: File, defaultClass?: string) => {
    const form = new FormData()
    form.append('file', file)
    if (defaultClass) {
      form.append('defaultClass', defaultClass)
    }
    return post<ImportResultVO>('/admin/students/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },

  captchas: (query: Record<string, unknown>) =>
    get<PageVO<CaptchaImageVO>>('/admin/captchas', query),
  captchaSummary: () => get<{ enabled: number; total: number }>('/admin/captchas/summary'),
  uploadCaptcha: (file: File, fields: Record<string, string | number | undefined>) => {
    const form = new FormData()
    form.append('file', file)
    Object.entries(fields).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') {
        form.append(k, String(v))
      }
    })
    return post<number>('/admin/captchas/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  updateCaptcha: (id: number, body: Record<string, unknown>) =>
    put<void>(`/admin/captchas/${id}`, body),
  toggleCaptcha: (id: number, status: number) =>
    put<void>(`/admin/captchas/${id}/status`, { status }),
  deleteCaptcha: (id: number) => del<void>(`/admin/captchas/${id}`),

  orphans: (limit = 200) => get<OrphanVO[]>('/admin/ops/orphan-objects', { limit }),
  orphanCount: () => get<number>('/admin/ops/orphan-objects/count'),
  cleanOrphans: (limit = 200) =>
    post<number>(`/admin/ops/orphan-objects/clean?limit=${limit}`, {}),
  rebuildPaths: () => post<number>('/admin/ops/rebuild-paths', {}),
  flushSessions: (ipPrefix: string) =>
    post<SessionFlushVO>('/admin/ops/sessions/flush', { ipPrefix }),
  reconcileStorage: () => post<ReconcileVO>('/admin/ops/reconcile-storage', {}),
  configSummary: () => get<Record<string, unknown>>('/admin/ops/config-summary'),
}
