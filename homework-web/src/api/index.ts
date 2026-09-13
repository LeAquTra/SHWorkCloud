import axios from 'axios'
import { get, post, put, del, getBlob } from './http'
import type {
  AdminUserVO,
  BreadcrumbVO,
  CaptchaImageVO,
  CaptchaVO,
  CommitVO,
  FileItemVO,
  FolderNodeVO,
  ImageItemVO,
  ImportResultVO,
  LoginVO,
  OrphanVO,
  PageVO,
  PartUrlsVO,
  ProfileUpdateVO,
  PutUrlVO,
  QuotaVO,
  ReconcileVO,
  RegisterConfigVO,
  ResetPasswordVO,
  SessionFlushVO,
  TextContentVO,
  UploadTicketVO,
  UploadedPartVO,
  UrlVO,
  UserProfileVO,
} from '@/types/api'

// ---------------------------------------------------------------- 认证

export const authApi = {
  login: (login: string, password: string) => post<LoginVO>('/auth/login', { login, password }),
  logout: () => post<void>('/auth/logout'),
  changePassword: (oldPassword: string, newPassword: string, confirmPassword: string) =>
    post<void>('/auth/password', { oldPassword, newPassword, confirmPassword }),

  /** 公开接口：先问清楚要不要显示注册入口、要不要图片验证码 */
  registerConfig: () => get<RegisterConfigVO>('/auth/register-config'),

  /**
   * 取一道图片验证码。
   *
   * 对接指南写的是 `POST /auth/captcha`，而后端手册的 curl 示例是 GET。
   * 这里先按 POST 发，遇到 404/405 再退回 GET —— 免得因为一个方法不一致，
   * 把整条注册通道堵死。
   */
  captcha: async (): Promise<CaptchaVO> => {
    try {
      return await post<CaptchaVO>('/auth/captcha', {})
    } catch (error) {
      if (axios.isAxiosError(error)) {
        const status = error.response?.status
        if (status === 404 || status === 405 || status === 400) {
          return await get<CaptchaVO>('/auth/captcha')
        }
      }
      throw error
    }
  },

  /** type=3 点选时用 clicks，坐标必须是**原图像素** */
  verifyCaptcha: (body: {
    captchaId: string
    answer?: string
    clicks?: { x: number; y: number }[]
  }) => post<{ captchaPassToken: string }>('/auth/captcha/verify', body),

  /** 发邮件码；服务端会一次性消费 captchaPassToken（未开启图片验证码时不传） */
  sendEmailCode: (email: string, captchaPassToken?: string) =>
    post<void>('/auth/email-code', captchaPassToken ? { email, captchaPassToken } : { email }),

  /** 注册提交**不需要**带 captchaPassToken（已在发邮件码时消费掉） */
  register: (body: { email: string; emailCode: string; password: string; username?: string }) =>
    post<void>('/auth/register', body),
}

// ---------------------------------------------------------------- 用户

export const userApi = {
  profile: () => get<UserProfileVO>('/user/profile'),

  /** 部分更新个性属性：不传的键不会被改动；传 null 表示清空 */
  updateProfile: (body: ProfileUpdateVO) => put<UserProfileVO>('/user/profile', body),

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

  /** 10 分钟签名地址：浏览器无法给 <a download> 带 Authorization，只能用签名地址 */
  downloadUrl: (id: number) => get<UrlVO>(`/files/${id}/download-url`),
  /** 10 分钟签名地址：image / pdf / video / audio 走它 */
  previewUrl: (id: number) => get<UrlVO>(`/files/${id}/preview-url`),
  /** text / office 走它拿正文（服务端已处理 GBK / BOM） */
  text: (id: number) => get<TextContentVO>(`/files/${id}/text`),

  recycleList: (page = 1, size = 50) => get<PageVO<FileItemVO>>('/recycle', { page, size }),
  restore: (ids: number[]) => post<number>('/recycle/restore', { ids }),
  purge: (ids: number[]) => del<number>('/recycle/purge', { ids }),
  emptyRecycle: () => del<number>('/recycle/empty'),
}

// ---------------------------------------------------------------- 相册

export const imageApi = {
  /** 跨目录摊平本人网盘里所有可在线预览的图片，按上传时间倒序；每项已带签名 previewUrl */
  list: (page = 1, size = 60) => get<PageVO<ImageItemVO>>('/images', { page, size }),
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

  completeMultipart: (
    uploadToken: string,
    uploadId: string,
    parts: { partNumber: number; etag: string }[],
  ) => post<{ objectKey: string }>('/oss/multipart/complete', { uploadToken, uploadId, parts }),

  abortMultipart: (uploadToken: string, uploadId: string) =>
    post<void>('/oss/multipart/abort', { uploadToken, uploadId }),

  uploadedParts: (uploadToken: string, uploadId: string) =>
    get<UploadedPartVO[]>('/oss/multipart/parts', { uploadToken, uploadId }),

  /** 秒传尝试；hit=false 表示未命中，需要正常上传 */
  instantUpload: (body: {
    md5: string
    parentId: number
    name: string
    size: number
    contentType?: string
  }) => post<CommitVO>('/files/instant-upload', body),

  /** 直传完成后建立索引（幂等）—— 这一步成功才算"保存到网盘" */
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
  /**
   * 班级列表（用于用户管理的下拉筛选）。
   * 接口手册里没有单列这一条，后端若未实现会返回非 0 码 —— 调用处已做降级。
   */
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
