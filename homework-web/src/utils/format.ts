/** 展示格式化工具 */

import type { FileItemVO, ViewType } from '@/types/api'

export function formatSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || bytes < 0) {
    return '-'
  }
  if (bytes === 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / Math.pow(1024, index)
  return `${value >= 100 || index === 0 ? Math.round(value) : value.toFixed(1)} ${units[index]}`
}

/** 后端返回的是 "yyyy-MM-dd HH:mm:ss" 形式的字符串 */
export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ')
}

/** 只保留日期部分，用于"最后登录"这类窄列 */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 10)
}

/**
 * 解析后端下发的时间字符串。
 *
 * 后端是 `LocalDateTime`（无时区），JSON 里可能是
 * `2026-02-14T10:30:00` 或 `2026-02-14 10:30:00`。
 * 后者在部分浏览器里 `new Date()` 直接得到 Invalid Date —— 所以先归一成 ISO。
 * 解析不出来返回 null，由调用方决定怎么降级（例如"冷却中"就直接放开按钮，
 * 真正的拦截在服务端，前端判断不出来时不该假装用户不能操作）。
 */
export function parseBackendTime(value: string | null | undefined): Date | null {
  if (!value) {
    return null
  }
  const time = new Date(value.trim().replace(' ', 'T'))
  return Number.isNaN(time.getTime()) ? null : time
}

/** 把毫秒差写成"X 小时 Y 分钟"，用于头像冷却倒计时 */
export function formatRemain(ms: number): string {
  if (ms <= 0) {
    return '已可修改'
  }
  const minutes = Math.ceil(ms / 60000)
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours <= 0) {
    return `${rest} 分钟`
  }
  return rest > 0 ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`
}

export function formatPercent(value: number): string {
  return `${Math.max(0, Math.min(100, Math.round(value)))}%`
}

export function bytesToGb(bytes: number): number {
  return Math.round((bytes / 1024 / 1024 / 1024) * 10) / 10
}

export function gbToBytes(gb: number): number {
  return Math.round(gb * 1024 * 1024 * 1024)
}

// ---------------------------------------------------------------- viewType

const IMAGE_SUFFIX = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp']
const VIDEO_SUFFIX = ['mp4', 'webm', 'mov', 'mkv', 'avi', 'wmv', 'flv', 'm4v']
const AUDIO_SUFFIX = ['mp3', 'wav', 'm4a', 'flac', 'aac', 'ogg', 'wma']
const TEXT_SUFFIX = ['txt', 'md', 'csv', 'json', 'log', 'xml', 'yml', 'yaml', 'ini', 'java', 'ts', 'js', 'py', 'c', 'cpp', 'html', 'css', 'sql']
const OFFICE_SUFFIX = ['doc', 'docx', 'pptx']

/**
 * 该用哪种方式渲染这个文件。
 *
 * <p>**以服务端下发的 viewType 为准**（接口手册 §0.4：前端不要自己维护一份
 * 后缀白名单，两边各写一份迟早不一致）。下面的后缀推断只是兜底：万一后端
 * 某个接口没带 viewType，界面至少不会退化成"全部只能下载"。
 */
export function resolveViewType(item: Pick<FileItemVO, 'viewType' | 'suffix' | 'contentType'>): ViewType {
  if (item.viewType) {
    return item.viewType
  }
  const suffix = (item.suffix || '').toLowerCase()
  if (IMAGE_SUFFIX.includes(suffix)) return 'image'
  if (suffix === 'pdf') return 'pdf'
  if (VIDEO_SUFFIX.includes(suffix)) return 'video'
  if (AUDIO_SUFFIX.includes(suffix)) return 'audio'
  if (TEXT_SUFFIX.includes(suffix)) return 'text'
  if (OFFICE_SUFFIX.includes(suffix)) return 'office'
  return 'none'
}

/** 能否在线阅览（有内容可看，而不是只能下载） */
export function canPreview(item: FileItemVO): boolean {
  if (item.folder) {
    return false
  }
  // 后端若明确给了 previewable 就听它的
  if (typeof item.previewable === 'boolean') {
    return item.previewable
  }
  // 兜底：后端没下发 previewable（旧 jar）时按后缀推断。
  // ⚠️ pdf 必须排除 —— 需求是"PDF 不支持在线预览"（点击整行不弹窗、
  // 下拉框里不出现"在线预览"）。若这里放行，遇到旧 jar 就会出现一个点了必报 40073 的入口。
  const kind = resolveViewType(item)
  return kind !== 'none' && kind !== 'pdf'
}

export const VIEW_TYPE_LABELS: Record<ViewType, string> = {
  image: '图片',
  pdf: 'PDF',
  video: '视频',
  audio: '音频',
  text: '文本',
  office: 'Office 文档',
  none: '仅可下载',
}

// ---------------------------------------------------------------- 图标

export type FileTone = 'folder' | 'image' | 'video' | 'audio' | 'pdf' | 'doc' | 'sheet' | 'slide' | 'archive' | 'text' | 'other'

/** 与 FileGlyph.vue 的配色表对应；在这里归类，方便列表与相册复用同一套视觉 */
export function fileTone(item: { folder: boolean; suffix: string | null }): FileTone {
  if (item.folder) return 'folder'
  const suffix = (item.suffix || '').toLowerCase()
  if (IMAGE_SUFFIX.includes(suffix)) return 'image'
  if (VIDEO_SUFFIX.includes(suffix)) return 'video'
  if (AUDIO_SUFFIX.includes(suffix)) return 'audio'
  if (suffix === 'pdf') return 'pdf'
  if (['doc', 'docx'].includes(suffix)) return 'doc'
  if (['xls', 'xlsx', 'csv'].includes(suffix)) return 'sheet'
  if (['ppt', 'pptx'].includes(suffix)) return 'slide'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(suffix)) return 'archive'
  if (TEXT_SUFFIX.includes(suffix)) return 'text'
  return 'other'
}

/**
 * 触发浏览器下载（走已签名的 URL）。
 *
 * ⚠️ 这里**必须校验 url**，别删。真实踩过的坑：
 * 后端 `download-url` 契约是 `{ url }` 对象，一旦实现成裸字符串，前端解构出的
 * `url` 就是 `undefined`；而 `a.href = undefined` **不抛任何异常** ——
 * DOM 会把它转成字面量字符串 `"undefined"`，浏览器按相对路径打开
 * `https://站点/undefined`，用户只看到一个莫名其妙的「页面不存在」404，
 * 控制台还干干净净，极难定位。所以在这里挡住，把静默失败变成明确报错。
 */
export function triggerDownload(url: string): void {
  if (!url || typeof url !== 'string') {
    throw new Error('没有拿到有效的下载地址（响应格式不正确）')
  }
  const a = document.createElement('a')
  a.href = url
  // 后端已在签名 URL 上带 response-content-disposition，这里不再指定 download 属性
  a.rel = 'noopener'
  a.target = '_blank'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/** 生成简短随机 id（不依赖 Web Crypto，内网 http 下 crypto.randomUUID 不存在） */
export function randomId(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4)
}

/**
 * 过滤服务端渲染的 Office HTML（docx / xlsx 的"原格式"视图）。
 *
 * ⚠️ 这是**第二道防线**，不是唯一防线：
 * 服务端 {@code OfficeHtmlService} 已经把文档里的全部文本转义、标签只按白名单生成；
 * 这里再摘掉脚本类标签、`on*` 事件属性与 `javascript:` 协议，
 * 是为了避免"服务端某处漏转义"就直接变成 XSS —— 渲染不可信文档成 HTML
 * 本身就是一个高风险点，值得多一道。
 *
 * 用正则而不是 DOM 解析：这些 HTML 完全由我们自己生成，
 * 结构可预期，不需要完整的 HTML 解析器（也就没有额外的解析差异风险）。
 */
export function sanitizeOfficeHtml(raw: string): string {
  if (!raw) {
    return ''
  }
  return (
    raw
      // 整块危险标签（含内容）
      .replace(/<\s*(script|style|iframe|object|embed|link|meta|form)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
      // 自闭合/未闭合的危险标签
      .replace(/<\s*\/?\s*(script|style|iframe|object|embed|link|meta|form)\b[^>]*>/gi, '')
      // 事件属性：onclick=、onerror= …
      .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
      // javascript: / data:text/html 之类的协议
      .replace(/\s(href|src|xlink:href)\s*=\s*("|')?\s*(javascript|vbscript|data:text\/html)[^"'>]*/gi, '')
  )
}

/** 复制文本：非安全上下文下 navigator.clipboard 不可用，降级到 execCommand */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
    const input = document.createElement('textarea')
    input.value = text
    input.style.position = 'fixed'
    input.style.opacity = '0'
    document.body.appendChild(input)
    input.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(input)
    return ok
  } catch {
    return false
  }
}
