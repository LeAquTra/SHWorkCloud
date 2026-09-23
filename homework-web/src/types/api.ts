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
  /**
   * 注册「发送邮箱验证码」之前是否需要图片验证码。
   * ⚠️ 这是**生效值**：配置开着但题库为空时是 `false`。
   * 新代码更推荐直接用 `humanCheck()` 的 `register` 字段 —— 三个场景口径统一。
   */
  requireImageCaptcha: boolean
  mailEnabled: boolean
  /** 后端的 Java 正则，前端拿来做即时校验；用 try/catch 包住，避免个别语法不兼容 */
  emailPattern: string
  mailHint: string | null
}

/**
 * `GET /auth/human-check`：人机验证（后台图片验证码题库）在三个场景下是否**生效**。
 *
 * ⚠️ 这三个值是**生效值**，不只是配置值：后台题库为空（全新部署 / 题目被删光）时
 * 服务端会自动降级为 false —— 否则所有人都会被「请先完成人机验证」挡在门外，
 * 而用户根本没法完成验证。前端直接按它决定要不要弹验证码窗口即可。
 */
export interface HumanCheckVO {
  /** 登录时是否需要人机验证 */
  login: boolean
  /** 注册「发送邮箱验证码」之前是否需要 */
  register: boolean
  /** 申请上传凭证（上传文件）之前是否需要 */
  upload: boolean
  /** 通过验证后凭证的有效秒数 */
  passTtlSeconds: number
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
  /**
   * 头像**下次可修改时间**；为 null 表示现在就能改。
   *
   * 服务端限制「每 24 小时只能改一次头像」（防止拿头像当图床刷）。
   * 前端据此把按钮置灰并显示倒计时 —— 但真正的拦截始终在服务端，
   * 这里只是为了不让用户白点一次才报错。
   */
  avatarChangeableAt: string | null
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
  /**
   * 仅图片有值：1 小时有效的签名缩略图地址。
   * 列表里直接用它当缩略图显示（像头像那样），不必再逐张请求预览接口。
   */
  previewUrl?: string | null
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
  /**
   * docx / xlsx 的**结构化 HTML**（服务端渲染，已转义），用于"原格式"阅览；
   * 其它格式或渲染失败时为 null，前端回退到纯文本视图。
   */
  html: string | null
}

/** `GET /oss/upload-config`：前端上传预检参数，避免把上限硬编码在前端 */
export interface UploadConfigVO {
  maxFileSizeBytes: number
  /** 分类上限，键为 video / archive */
  transferLimits: Record<string, number>
  /** 上传整个文件夹时的总大小上限 */
  folderMaxTotalBytes: number
}

/**
 * docx / pptx 里内嵌的一张图片（`GET /files/{id}/embedded-images`）。
 *
 * `dataUrl` 形如 `data:image/png;base64,...`，可直接给 `<img src>` ——
 * 不需要签名地址，也不会在 OSS 里多出对象。
 */
export interface EmbeddedImageVO {
  /** 文档内部的媒体文件名，如 image1.png */
  name: string
  contentType: string
  size: number
  dataUrl: string
}

export interface EmbeddedImagesVO {
  images: EmbeddedImageVO[]
  /** 被跳过的张数：过大 / 超总量 / 超数量 / 格式不可渲染（emf、wmf、svg 等） */
  skipped: number
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

/** 验证码题库列表项（`GET /admin/captchas`），每项带签名图片地址供在线阅览 */
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
  /**
   * 服务端签发的 OSS 签名地址（1 小时有效）。
   * 图片在私有 Bucket 且 `captcha/` 前缀不在用户 STS Policy 内，
   * 前端拼不出可用地址，必须由接口下发 —— 否则只能看到 dataJson 文本。
   */
  imageUrl: string | null
  createTime: string
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

// ---------------------------------------------------------------- 公告

/**
 * 公告的**注意力分级** —— 决定"怎么打扰用户"，是公告功能的核心设计。
 *
 * 1 普通：顶部横幅，可关；
 * 2 重要：顶部警示色横幅，可关；
 * 3 紧急：**强制弹窗**，必须点「我已知晓」才能继续操作。
 *
 * 分级由超管在后台设置，前端不自行推断。
 */
export const ANNOUNCEMENT_LEVEL_NORMAL = 1
export const ANNOUNCEMENT_LEVEL_IMPORTANT = 2
export const ANNOUNCEMENT_LEVEL_URGENT = 3

/** 公告状态：草稿 / 已发布 / 已撤回（撤回只改状态、不删数据） */
export const ANNOUNCEMENT_STATUS_DRAFT = 0
export const ANNOUNCEMENT_STATUS_PUBLISHED = 1
export const ANNOUNCEMENT_STATUS_RECALLED = 2

export const ANNOUNCEMENT_LEVEL_LABELS: Record<number, string> = {
  [ANNOUNCEMENT_LEVEL_NORMAL]: '普通',
  [ANNOUNCEMENT_LEVEL_IMPORTANT]: '重要',
  [ANNOUNCEMENT_LEVEL_URGENT]: '紧急',
}

export const ANNOUNCEMENT_STATUS_LABELS: Record<number, string> = {
  [ANNOUNCEMENT_STATUS_DRAFT]: '草稿',
  [ANNOUNCEMENT_STATUS_PUBLISHED]: '已发布',
  [ANNOUNCEMENT_STATUS_RECALLED]: '已撤回',
}

/** 用户端 `GET /announcements/active`：只含当前生效的公告 */
export interface AnnouncementActiveVO {
  id: number
  title: string
  /** 纯文本正文，按换行渲染（后端不接受 HTML） */
  content: string
  /** 1 普通 / 2 重要 / 3 紧急 */
  level: number
  publishTime: string | null
  expireTime: string | null
}

/** 后台 `GET /admin/announcements`：带状态与审计字段 */
export interface AnnouncementManageVO {
  id: number
  title: string
  content: string
  level: number
  /** 0 草稿 / 1 已发布 / 2 已撤回 */
  status: number
  publishTime: string | null
  expireTime: string | null
  createdBy: number | null
  createTime: string
  updateTime: string
  /** 此刻是否真的对用户可见（已发布 + 已到时间 + 未过期） */
  effective: boolean
}

/** 新建 / 修改公告的请求体 */
export interface AnnouncementUpsertReq {
  title: string
  content: string
  level: number
  /** 为空表示不过期；最多一年后 */
  expireTime?: string | null
}

// ---------------------------------------------------------------- 好友与私聊

/**
 * 与我某人的关系。
 *
 * ⚠️ 取值由服务端下发（`FriendVo.UserCard.relation`），**前端不要自己推算**：
 * 判断关系要同时看两个方向的边（他申请我 / 我申请他 / 互为好友），
 * 前端只拿得到自己这一侧的数据，自己算必然算错。
 */
export const RELATION_SELF = 'SELF'
export const RELATION_FRIEND = 'FRIEND'
/** 我已发出申请，等对方接受 */
export const RELATION_OUTGOING = 'OUTGOING'
/** 对方申请加我，等我处理 */
export const RELATION_INCOMING = 'INCOMING'
/** 陌生人 */
export const RELATION_NONE = 'NONE'

export type Relation =
  | typeof RELATION_SELF
  | typeof RELATION_FRIEND
  | typeof RELATION_OUTGOING
  | typeof RELATION_INCOMING
  | typeof RELATION_NONE

/**
 * 用户名片：好友列表 / 搜索结果 / 他人主页 / 聊天窗顶栏共用。
 *
 * 字段是**服务端刻意收窄过的**（见 `FriendVo.UserCard` 的注释）：
 * 没有邮箱、生日、性别、容量、最后登录 IP。前端也不要指望能拿到 ——
 * 看别人的主页不需要这些。
 */
export interface UserCard {
  userId: number
  /** 登录名（学生即学号） */
  username: string
  /**
   * 展示名：昵称 → 真实姓名 → 登录名，**由服务端算好**。
   * 前端直接用这个，不要自己写一遍优先级（写漏一处就会显示空白名字）。
   */
  displayName: string
  nickname: string | null
  realName: string | null
  className: string | null
  /** 1 小时有效的 OSS 签名地址，可直接给 <img src>；没设头像时为 null */
  avatarUrl: string | null
  /** 头像版本串，换头像后用于绕过浏览器缓存 */
  avatarVersion: string | null
  signature: string | null
  role: number
  relation: Relation
  /** 待处理申请的时间；只在 OUTGOING / INCOMING 下有值 */
  requestedAt: string | null
}

/**
 * 好友页汇总（`GET /friends`）。
 *
 * 名额口径：`usedSlots` = 已确认好友 + 待处理申请（收发的都算）。
 * 服务端把"待处理申请"也计入名额，是为了避免"49 个好友 + 一堆申请同时被接受"超限。
 */
export interface FriendOverview {
  friends: UserCard[]
  /** 别人发给我、待我处理的申请 */
  incoming: UserCard[]
  /** 我发出、对方还没处理的申请 */
  outgoing: UserCard[]
  /** 上限（当前 50）。**不要在前端写死这个数字** */
  maxFriends: number
  usedSlots: number
  remaining: number
}

/**
 * 一条私聊消息。
 *
 * ⚠️ `content` 是**纯文本**：必须用文本插值 `{{ }}` 渲染，
 * **绝不能 `v-html`** —— 聊天正文是用户输入，直接插 HTML 等于把 XSS 送到对方会话里。
 */
export interface ChatMessageVO {
  id: number
  fromUserId: number
  /** 服务端算好的"是不是我发的"，前端不必比较 fromUserId */
  mine: boolean
  content: string
  createTime: string
}

/**
 * 一次会话拉取的结果（`GET /chat/messages`）。
 *
 * `maxId` 是**下一次请求的游标**：把它作为 `afterId` 传回去即可增量拉取。
 * 没有新消息时服务端会把入参原样带回，所以不会被误清零。
 */
export interface ChatThread {
  peer: UserCard
  /** 按时间升序 */
  messages: ChatMessageVO[]
  maxId: number
  /** 对方已读到的最大消息 id：`id <= 该值` 的己方消息显示"已读" */
  lastReadIdByPeer: number
  /** 仅在向上翻历史时有意义 */
  hasMore: boolean
}

/** 会话列表项（`GET /chat/conversations`） */
export interface ConversationVO {
  peer: UserCard
  lastMessage: string | null
  lastMessageTime: string | null
  lastFromMe: boolean
  unread: number
}

/**
 * 未读汇总（`GET /chat/unread`）。
 * `total` = 未读消息 + 待处理好友申请 —— 两类都是"有人在等你"，
 * 合成一个红点比拆成两个更容易被注意到。
 */
export interface ChatUnreadVO {
  total: number
  friends: number
  requests: number
}

/** 个人信息页（`GET /user/{id}/profile`）返回的就是 UserCard */
export type PublicProfileVO = UserCard

// ---------------------------------------------------------------- 社区

/**
 * 帖子状态。
 *
 * ⚠️ **用户永远看不到自己的状态被直接改成"已通过"** —— 服务端没有"直接发布"的接口，
 * 通过的唯一路径是管理员审核。
 */
export const POST_STATUS_PENDING = 0
export const POST_STATUS_APPROVED = 1
export const POST_STATUS_REJECTED = 2

export const POST_STATUS_LABELS: Record<number, string> = {
  [POST_STATUS_PENDING]: '待审核',
  [POST_STATUS_APPROVED]: '已通过',
  [POST_STATUS_REJECTED]: '已拒绝',
}

/** 正文分段：`text` 直接插值，`link` 渲染成 <a href> */
export interface PostSegmentVO {
  type: 'text' | 'link'
  text: string
  /** 仅 link 段有值，且一定以 http(s):// 开头；直接放进 href，不要再拼接 */
  href: string | null
}

/**
 * 一条帖子。
 *
 * `segments` 是「链接特殊显示」的实现：**服务端已经切好了**
 * （`LinkSegmenter`，有 17 个单测守着边界）。前端只做一件事 ——
 * 把 `type === 'link'` 的段渲染成 `<a>`，其余按纯文本插值。
 *
 * ⚠️ 绝对不要 `v-html`：正文是用户输入。也不要自己做链接正则 ——
 * 那样"什么算链接"就有两份实现，迟早不一致。
 */
export interface PostVO {
  id: number
  author: UserCard
  /** 原文纯文本（复制、编辑回填用） */
  content: string
  segments: PostSegmentVO[]
  /** 正文里的链接数，界面可提示"含 N 个外部链接" */
  linkCount: number
  status: number
  reviewedBy: number | null
  reviewTime: string | null
  /** 拒绝理由，仅 status=2 时有值（只有作者看得到） */
  rejectReason: string | null
  createTime: string
  updateTime: string
  /** 能否编辑：由服务端算（待审核中不能改），前端不要自己推状态机 */
  canEdit: boolean
  canDelete: boolean
}

/** 时间线一页 */
export interface PostFeedVO {
  posts: PostVO[]
  /** 下一页游标；null 表示没有更多 */
  nextBeforeId: number | null
  hasMore: boolean
}

/** 我的帖子概览（页签角标） */
export interface MyPostsSummaryVO {
  pending: number
  approved: number
  rejected: number
}

/**
 * 后台帖子列表一页（`GET /admin/posts`，审核页与管理台共用同一个接口）。
 *
 * ⚠️ 这个类型**比后端的 `ReviewPage` 多一个 `statusCounts`** —— 接口实际返回的是
 * `CommunityVo.AdminPostPage`（= `ReviewPage` + 统计）。审核页只读 `pendingTotal`，
 * 管理台才用 `statusCounts`；两者共用一个类型是因为它们打的是同一个接口。
 */
export interface PostReviewPageVO {
  records: PostVO[]
  total: number
  page: number
  size: number
  /** 全站待审核总数，审核员随时知道还剩多少 */
  pendingTotal: number
  /**
   * 三个状态各多少条（社区管理台的统计卡片）。
   * ⚠️ 这是**全局值，不受当前筛选条件影响** —— 管理员要知道"站上有多少条已通过的内容"，
   * 而不是"我这次筛出来多少条"（后者看 `total`）。
   */
  statusCounts: PostStatusCountsVO
}

/** 社区内容的全局状态分布，见 `PostReviewPageVO.statusCounts` */
export interface PostStatusCountsVO {
  pending: number
  approved: number
  rejected: number
}

/** 发帖 / 编辑 / 审核后的回执 */
export interface PostActionResultVO {
  post: PostVO
  message: string
}

/**
 * 批量操作的结果。
 *
 * ⚠️ `affected` 与 `skipped` 必须展示给管理员：批量操作最容易出的事是
 * "看起来成功了，其实一条都没匹配上"（ID 粘错、状态已经变了）。
 */
export interface PostBatchResultVO {
  /** 真正被改动的条数 */
  affected: number
  /** 因状态/存在性不满足而跳过的条数 */
  skipped: number
  /** 后端给的一句可直接展示的结论 */
  message: string
}

/** 社区管理台支持的批量动作（`action` 字段的取值） */
export const POST_BATCH_ACTIONS = ['unpublish', 'reject', 'delete'] as const
export type PostBatchAction = (typeof POST_BATCH_ACTIONS)[number]

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
