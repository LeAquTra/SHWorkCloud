import { get, post, put, del, getBlob } from './http'
import type {
  AdminUpdateProfileReq,
  AdminUserVO,
  AnnouncementActiveVO,
  AnnouncementManageVO,
  AnnouncementUpsertReq,
  BreadcrumbVO,
  CaptchaImageVO,
  CaptchaVO,
  CommitVO,
  EmbeddedImagesVO,
  FileItemVO,
  FolderNodeVO,
  HumanCheckVO,
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
  UploadConfigVO,
  UploadTicketVO,
  UploadedPartVO,
  UrlVO,
  UserProfileVO,
} from '@/types/api'

// ---------------------------------------------------------------- 认证

export const authApi = {
  /**
   * 登录。
   *
   * @param captchaPassToken 人机验证凭证。是否需要见 {@link authApi.humanCheck}：
   *   需要而没带（或凭证已失效）时服务端返回 40105，前端应弹出验证码窗口后重试。
   */
  login: (login: string, password: string, captchaPassToken?: string) =>
    post<LoginVO>('/auth/login',
      captchaPassToken ? { login, password, captchaPassToken } : { login, password }),
  logout: () => post<void>('/auth/logout'),
  changePassword: (oldPassword: string, newPassword: string, confirmPassword: string) =>
    post<void>('/auth/password', { oldPassword, newPassword, confirmPassword }),

  /** 公开接口：先问清楚要不要显示注册入口、要不要图片验证码 */
  registerConfig: () => get<RegisterConfigVO>('/auth/register-config'),

  /**
   * 公开接口：登录 / 注册 / 上传三个场景**此刻**是否需要人机验证。
   * 返回的是生效值（后台题库为空时自动为 false），前端可以直接照着弹窗。
   * 登录页在"还没有 token"时就要用它，所以它必须是公开接口。
   */
  humanCheck: () => get<HumanCheckVO>('/auth/human-check'),

  /**
   * 取一道图片验证码（**GET**，后端只映射了 GET）。
   *
   * <p>⚠️ 这里以前是"先按 POST 发，遇到 404/405 再退回 GET"的兜底写法，已经删掉：
   * 后端对方法不匹配回的是 500（历史缺陷，现已改成 405），而兜底只认 404/405，
   * 于是请求直接失败，用户看到的是"验证码加载失败"。
   * 契约就是 `GET /auth/captcha`，不要再猜。
   */
  captcha: () => get<CaptchaVO>('/auth/captcha'),

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
  /**
   * docx / pptx 内嵌图片（图文作业在线阅览用）。
   * 正文提取会把图片连标签一起剥掉，所以图文作业必须再调这个接口拿图。
   * 非 Office 文件返回空列表，不会报错。
   */
  embeddedImages: (id: number) => get<EmbeddedImagesVO>(`/files/${id}/embedded-images`),

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
  /**
   * 上传预检参数：全局上限 / 分类上限（video、archive）/ 文件夹总大小上限。
   * 集中从后端取，前端不硬编码数字 —— 否则改配置就会出现两边不一致。
   */
  config: () => get<UploadConfigVO>('/oss/upload-config'),

  /** 申请上传凭证：服务端确定 ObjectKey 并签发一次性 uploadToken */
  ticket: (body: {
    name: string
    size: number
    contentType?: string
    /** 人机验证凭证；需要时缺失会返回 40105（见 authApi.humanCheck） */
    captchaPassToken?: string
  }) => post<UploadTicketVO>('/oss/ticket', body),

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

// ---------------------------------------------------------------- 公告

export const announcementApi = {
  /**
   * 当前生效的公告（已发布、已到时间、未过期），按注意力分级降序。
   * 任何已登录用户都能拿 —— 公告就是发给所有人的。
   */
  active: () => get<AnnouncementActiveVO[]>('/announcements/active'),
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
  /**
   * 代改用户资料（**部分更新**：只提交要改的键，不传的键不动）。
   *
   * 允许的键：realName / studentNo / className / email / nickname。
   * 传空串表示**清空**该字段（例如把学生的邮箱清掉）。
   * 登录名/角色/状态/配额/密码/头像不能走这里，后端会返回明确提示。
   */
  updateProfile: (id: number, body: AdminUpdateProfileReq) =>
    put<void>(`/admin/users/${id}`, body),
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

  // ------------------------------------------------ 公告（仅超级管理员）

  announcements: (query: Record<string, unknown>) =>
    get<PageVO<AnnouncementManageVO>>('/admin/announcements', query),
  announcement: (id: number) => get<AnnouncementManageVO>(`/admin/announcements/${id}`),
  /** 新建后是**草稿**，还要再调 publish 才对用户可见 */
  createAnnouncement: (body: AnnouncementUpsertReq) => post<number>('/admin/announcements', body),
  /**
   * 修改公告。
   * ⚠️ 后端只允许改**草稿/已撤回**的公告；已发布的必须先撤回（避免用户看到的公告被静默改内容）。
   */
  updateAnnouncement: (id: number, body: AnnouncementUpsertReq) =>
    put<void>(`/admin/announcements/${id}`, body),
  publishAnnouncement: (id: number) => put<void>(`/admin/announcements/${id}/publish`),
  recallAnnouncement: (id: number) => put<void>(`/admin/announcements/${id}/recall`),
  deleteAnnouncement: (id: number) => del<void>(`/admin/announcements/${id}`),
}
