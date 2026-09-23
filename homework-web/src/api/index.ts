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
  ChatMessageVO,
  ChatThread,
  ChatUnreadVO,
  CommitVO,
  ConversationVO,
  EmbeddedImagesVO,
  FileItemVO,
  FolderNodeVO,
  FriendOverview,
  HumanCheckVO,
  ImageItemVO,
  ImportResultVO,
  LoginVO,
  MyPostsSummaryVO,
  OrphanVO,
  PageVO,
  PartUrlsVO,
  PostActionResultVO,
  PostBatchAction,
  PostBatchResultVO,
  PostFeedVO,
  PostReviewPageVO,
  PostStatusCountsVO,
  PostVO,
  ProfileUpdateVO,
  PublicProfileVO,
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
  UserCard,
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

  /**
   * 查看**别人**的公开主页（点好友头像、点社区作者头像走它）。
   *
   * 返回字段是服务端收窄过的名片：只有昵称/姓名/班级/头像/签名/角色，
   * 以及"我与 TA 的关系"——**没有**邮箱、生日、性别、容量。
   * 想拿自己的完整资料请用 {@link userApi.profile}。
   */
  publicProfile: (userId: number) => get<PublicProfileVO>(`/user/${userId}/profile`),
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

// ---------------------------------------------------------------- 好友

export const friendApi = {
  /** 好友页汇总：好友 + 待处理申请（收/发）+ 名额。进页面一次拿全 */
  overview: () => get<FriendOverview>('/friends'),

  /** 只要好友列表（聊天左侧栏用） */
  list: () => get<UserCard[]>('/friends/list'),

  /**
   * 按**用户 ID** 找人（不是按姓名 / 学号）。
   *
   * 服务端只接受 ID：不给"按姓名模糊搜"的入口，避免把"翻一遍全校人"变成一项功能。
   * ID 是精确值，得先从别处拿到（老师给的名单，或对方主页地址 `/user/1002`
   * 里的那个数字）。
   *
   * 搜到自己时返回自己的名片（`relation: 'SELF'`），界面应显示"这是你自己"；
   * ID 不存在或该用户已禁用时返回空数组，不报错。
   */
  search: (userId: number) => get<UserCard[]>('/friends/search', { userId }),

  /** 发起好友申请；若对方此前已申请过我，则双方直接成为好友 */
  request: (userId: number) => post<FriendOverview>('/friends/requests', { userId }),

  /** 同意 / 拒绝收到的申请 */
  handle: (userId: number, accept: boolean) =>
    post<FriendOverview>('/friends/requests/handle', { userId, accept }),

  /** 删除好友（双向删除，对方的列表里也会消失） */
  remove: (friendId: number) => del<FriendOverview>(`/friends/${friendId}`),
}

// ---------------------------------------------------------------- 私聊

export const chatApi = {
  /** 会话列表：好友 + 最后一条消息 + 未读数 */
  conversations: () => get<ConversationVO[]>('/chat/conversations'),

  /**
   * 拉消息（三种用法见 `ChatThread` 的注释）：
   * - `{ peerId }` 取最新一页
   * - `{ peerId, afterId }` 增量拉取（轮询用）
   * - `{ peerId, beforeId, size }` 向上翻历史
   */
  messages: (query: { peerId: number; afterId?: number; beforeId?: number; size?: number }) =>
    get<ChatThread>('/chat/messages', query as Record<string, unknown>),

  /** 发消息。发送者取自登录会话，请求体里没有发送者字段 */
  send: (toUserId: number, content: string) =>
    post<ChatMessageVO>('/chat/messages', { toUserId, content }),

  /** 把与某人的会话标记为已读，返回实际置位的条数 */
  markRead: (peerId: number) =>
    post<{ updated: number }>(`/chat/read/${peerId}`, {}),

  /** 未读汇总（顶栏红点） */
  unread: () => get<ChatUnreadVO>('/chat/unread'),
}

// ---------------------------------------------------------------- 社区

export const communityApi = {
  /**
   * 时间线（**只含已通过的帖子**），按时间倒序，游标翻页。
   *
   * - 首屏：`{}`
   * - 下一页：`{ beforeId: 上一页的 nextBeforeId }`
   * - 某人的帖子：`{ authorId }`
   * - 我的（含待审与被拒）：`{ mine: true }`
   */
  feed: (query: { beforeId?: number; authorId?: number; mine?: boolean; size?: number } = {}) =>
    get<PostFeedVO>('/community/posts', query as Record<string, unknown>),

  /** 我的帖子概览：待审 / 已通过 / 已拒绝各多少 */
  mySummary: () => get<MyPostsSummaryVO>('/community/posts/mine/summary'),

  detail: (id: number) => get<PostVO>(`/community/posts/${id}`),

  /**
   * 发帖。
   * ⚠️ 请求体里**只有 content**：状态与作者都由服务端决定。
   * 返回的 `post.status` 恒为 0（待审核），界面上要提示"已提交，等待审核"，
   * **不要**把这条直接插进时间线（那会让人以为已经发出去了）。
   */
  create: (content: string) => post<PostActionResultVO>('/community/posts', { content }),

  /**
   * 编辑自己的帖子。
   * ⚠️ 编辑后**一定回到待审核**（含已通过的帖子），所以改完会从时间线暂时消失。
   */
  update: (id: number, content: string) =>
    put<PostActionResultVO>(`/community/posts/${id}`, { content }),

  /** 删除自己的帖子（待审核中的不能删） */
  remove: (id: number) => del<void>(`/community/posts/${id}`),
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

  // ------------------------------ 社区审核（教师及以上）/ 社区管理（管理员及以上）

  /**
   * 后台帖子列表。所有筛选都可选：
   * - `status`：0 待审 / 1 已通过 / 2 已拒绝；**不传表示全部**
   * - `postId`：精确查一条（举报/工单里拿到的 ID）
   * - `ids`：按 ID 批量取（核对一份举报清单）
   * - `authorId`：某个学生发过的全部内容
   * - `keyword`：正文关键字
   *
   * ⚠️ `status` 必须是 number 或 undefined。传 `NaN`（例如 `Number('all')` 的结果）
   * 会序列化成 `status=NaN`，服务端 Integer 绑不上 → 40000「参数类型错误: status」。
   */
  postQueue: (query: {
    status?: number
    postId?: number
    ids?: number[]
    authorId?: number
    keyword?: string
    page?: number
    size?: number
  } = {}) =>
    get<PostReviewPageVO>('/admin/posts', query as Record<string, unknown>),

  /** 待审核数量（后台导航角标） */
  postPendingCount: () => get<number>('/admin/posts/pending-count'),

  /**
   * 社区内容的全局状态分布（管理台统计卡片）。
   * 与列表分开：这三个数字不受筛选影响，翻页/改条件时没必要重算。
   */
  postStatusCounts: () => get<PostStatusCountsVO>('/admin/posts/summary'),

  /**
   * 批量操作（仅管理员及以上，教师调用会拿到 40302）。
   *
   * - `unpublish`：下架回待审核（广场立刻不可见，内容还在，可重新审）
   * - `reject`：待审核的内容判不通过（可带 `reason`，作者可见）
   * - `delete`：**物理删除，不可恢复**
   *
   * 一次最多 200 条（后端 `PostRules.MAX_BATCH_IDS`），超出会报 40000。
   */
  postBatch: (ids: number[], action: PostBatchAction, reason?: string) =>
    post<PostBatchResultVO>('/admin/posts/batch', { ids, action, reason }),

  /** 彻底删除单条（管理台行内）。不看作者、不限状态，仅管理员及以上 */
  postDelete: (postId: number) => del<PostBatchResultVO>(`/admin/posts/${postId}`),

  /**
   * 通过 / 拒绝。
   * `rejectReason` 建议填但不强制；作者能看到它。
   */
  reviewPost: (postId: number, approve: boolean, rejectReason?: string) =>
    post<PostActionResultVO>('/admin/posts/review', { postId, approve, rejectReason }),

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
