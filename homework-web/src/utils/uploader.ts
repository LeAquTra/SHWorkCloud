/**
 * 浏览器直传 OSS（预签名 URL 方案）。
 *
 * 设计要点：
 * 1. **浏览器不持有任何 AK/SK**：分片上传需要的签名 URL 全部由服务端生成，
 *    因此在内网 http（非安全上下文）下也能工作，不需要 ali-oss SDK；
 * 2. **ObjectKey 由服务端签发**：ojectKey 在申请凭证时就定了，前端无从伪造；
 * 3. **续传是真的**：断点信息（uploadId + 已完成分片）存 localStorage，
 *    刷新页面后由服务端 `listParts` 校对，而不是依赖 SDK 的内存 checkpoint
 *    （v1.1 文档在这里说错了）；
 * 4. **并发受限**：机房共享出口带宽，单机并发默认 2，避免全班一起变慢。
 */
import { uploadApi } from '@/api'
import { ApiError, CODE } from '@/api/http'
import type { CommitVO } from '@/types/api'

export type UploadPhase = 'hashing' | 'uploading' | 'committing' | 'done' | 'failed' | 'paused'

// ---------------------------------------------------------------- 人机验证闸门

/**
 * 上传前的人机验证闸门。
 *
 * <p>服务端在申请上传凭证时返回 `40105`（需要人机验证）就会调用它；
 * 由**界面层**注入实现（弹一次验证码窗口 → 返回 `captchaPassToken`）。
 * 这里刻意不直接依赖 UI 或 Pinia：uploader 是纯逻辑模块，
 * 既能被批量上传队列复用，也能被将来的其它入口复用。
 *
 * @returns 凭证；`null` 表示用户取消（本次上传以 40105 失败，由调用方提示）
 */
export type UploadCaptchaGate = () => Promise<string | null>

let captchaGate: UploadCaptchaGate | null = null

export function setUploadCaptchaGate(gate: UploadCaptchaGate | null): void {
  captchaGate = gate
}

/** 申请凭证；遇到「需要人机验证」时弹窗，拿到凭证后自动重试一次 */
async function requestTicket(body: {
  name: string
  size: number
  contentType?: string
}): Promise<Awaited<ReturnType<typeof uploadApi.ticket>>> {
  try {
    return await uploadApi.ticket(body)
  } catch (error) {
    const needCaptcha = error instanceof ApiError
      && (error.code === CODE.CAPTCHA_REQUIRED || error.code === CODE.CAPTCHA_PASS_INVALID)
    if (!needCaptcha || !captchaGate) {
      throw error
    }
    const passToken = await captchaGate()
    if (!passToken) {
      throw error
    }
    return await uploadApi.ticket({ ...body, captchaPassToken: passToken })
  }
}

export interface UploadProgress {
  phase: UploadPhase
  percent: number
  uploadedBytes: number
  totalBytes: number
  /** 字节/秒，用于给学生"还要多久"的直观感受 */
  speed: number
  message?: string
}

export interface UploadResult {
  commit: CommitVO
  /** 秒传命中时为 true */
  instant: boolean
}

/** 超过该大小改用抽样指纹（需与后端 app.upload.instant-threshold-bytes 保持一致） */
const SAMPLE_THRESHOLD = Number(import.meta.env.VITE_INSTANT_THRESHOLD_MB || 200) * 1024 * 1024
/** 单机并发分片数：机房共享带宽，不宜过大 */
const MAX_PARALLEL = Math.max(1, Number(import.meta.env.VITE_UPLOAD_PARALLEL || 2))
/** 单次向后端申请多少个分片的 URL */
const URL_BATCH = 100
/** 单个分片的最大重试次数 */
const PART_RETRY = 3

const CKPT_PREFIX = 'sc_ckpt'

interface Checkpoint {
  fileKey: string
  uploadToken: string
  uploadId: string
  partSize: number
  parts: { partNumber: number; etag: string }[]
}

function fileKeyOf(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`
}

function ckptKey(userId: number, fileKey: string): string {
  return `${CKPT_PREFIX}:${userId}:${fileKey}`
}

function readCheckpoint(userId: number, file: File): Checkpoint | null {
  try {
    const raw = localStorage.getItem(ckptKey(userId, fileKeyOf(file)))
    return raw ? (JSON.parse(raw) as Checkpoint) : null
  } catch {
    return null
  }
}

function writeCheckpoint(userId: number, file: File, ckpt: Checkpoint): void {
  try {
    localStorage.setItem(ckptKey(userId, fileKeyOf(file)), JSON.stringify(ckpt))
  } catch {
    // 隐私模式或配额满：续传能力降级，不影响本次上传
  }
}

function clearCheckpoint(userId: number, file: File): void {
  localStorage.removeItem(ckptKey(userId, fileKeyOf(file)))
}

/** 进入网盘时清掉其它账号的断点，避免下一位学生看到上一位的续传任务 */
export function purgeOtherUsersCheckpoints(currentUserId: number): void {
  const myPrefix = `${CKPT_PREFIX}:${currentUserId}:`
  const stale: string[] = []
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i)
    if (key && key.startsWith(CKPT_PREFIX) && !key.startsWith(myPrefix)) {
      stale.push(key)
    }
  }
  stale.forEach((key) => localStorage.removeItem(key))
}

// ---------------------------------------------------------------- 指纹

function computeFingerprint(file: File, onPercent: (percent: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('../workers/md5.worker.ts', import.meta.url), {
      type: 'module',
    })
    const cleanup = () => worker.terminate()
    worker.onmessage = (event: MessageEvent) => {
      const data = event.data || {}
      if (data.type === 'progress') {
        onPercent(typeof data.percent === 'number' ? data.percent : 0)
        return
      }
      cleanup()
      if (data.ok) {
        resolve(data.hex as string)
      } else {
        reject(new Error(data.error || '计算文件指纹失败'))
      }
    }
    worker.onerror = (event) => {
      cleanup()
      reject(new Error(event.message || '计算文件指纹失败'))
    }
    worker.postMessage({
      file,
      fileName: file.name,
      sampleThreshold: SAMPLE_THRESHOLD,
    })
  })
}

// ---------------------------------------------------------------- XHR PUT

interface PutOptions {
  signal: AbortSignal
  onProgress?: (loaded: number) => void
  contentType?: string
}

/** 用 XHR 而不是 axios：只有 XHR 能拿到上传进度事件 */
function putBlob(url: string, blob: Blob, options: PutOptions): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url, true)
    if (options.contentType) {
      xhr.setRequestHeader('Content-Type', options.contentType)
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && options.onProgress) {
        options.onProgress(event.loaded)
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        const etag = xhr.getResponseHeader('ETag') || ''
        // OSS 返回的 ETag 带双引号，提交 part 时需要去掉
        resolve(etag.replace(/^"|"$/g, ''))
      } else {
        reject(
          new ApiError(
            xhr.status,
            `上传分片失败（HTTP ${xhr.status}）${xhr.getResponseHeader('x-oss-request-id') || ''}`,
          ),
        )
      }
    }
    // ⚠️ onerror 只在"网络层失败"时触发；HTTP 403/404 会走上面的 onload。
    // 直传 OSS 是跨域请求，而 PUT 不属于 CORS 简单方法（只有 GET/HEAD/POST 是），
    // 浏览器必然先发 OPTIONS 预检；桶上没有匹配的跨域规则时预检就被拦，
    // 表现就是这个 onerror —— 所以这里必须把"去查 CORS"写进提示，
    // 否则只能看到一句无从下手的"网络错误"。
    xhr.onerror = () =>
      reject(
        new ApiError(
          -1,
          '无法连接 OSS（请求被浏览器中断）。请检查 OSS 桶的跨域(CORS)规则：' +
            '来源需包含本站地址，允许的方法需含 PUT，且需暴露 ETag',
        ),
      )
    xhr.ontimeout = () => reject(new ApiError(-1, '上传超时'))
    xhr.onabort = () => reject(new DOMException('aborted', 'AbortError'))
    const onAbort = () => xhr.abort()
    options.signal.addEventListener('abort', onAbort, { once: true })
    xhr.send(blob)
  })
}

async function putWithRetry(
  url: string,
  blob: Blob,
  options: PutOptions,
  retries = PART_RETRY,
): Promise<string> {
  let lastError: unknown
  for (let attempt = 1; attempt <= retries; attempt++) {
    if (options.signal.aborted) {
      throw new DOMException('aborted', 'AbortError')
    }
    try {
      return await putBlob(url, blob, options)
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw error
      }
      lastError = error
      // 指数退避，弱网下给交换机一点喘息时间
      await new Promise((r) => setTimeout(r, 300 * attempt))
    }
  }
  throw lastError instanceof Error ? lastError : new ApiError(-1, '分片上传失败')
}

// ---------------------------------------------------------------- 主流程

export interface UploadArgs {
  file: File
  parentId: number
  userId: number
  signal: AbortSignal
  onProgress: (progress: UploadProgress) => void
  /** 是否尝试秒传，默认 true */
  tryInstant?: boolean
}

export async function uploadFile(args: UploadArgs): Promise<UploadResult> {
  const { file, parentId, userId, signal, onProgress } = args
  const total = file.size
  const startedAt = Date.now()
  const contentType = file.type || 'application/octet-stream'

  let uploadedBytes = 0
  const report = (phase: UploadPhase, message?: string) => {
    const elapsed = (Date.now() - startedAt) / 1000
    onProgress({
      phase,
      percent: total === 0 ? 100 : Math.min(100, Math.round((uploadedBytes / total) * 100)),
      uploadedBytes,
      totalBytes: total,
      speed: elapsed > 1 ? Math.round(uploadedBytes / elapsed) : 0,
      message,
    })
  }

  // ---- 1) 文件指纹 ----
  report('hashing', '正在计算文件指纹…')
  const fingerprint = await computeFingerprint(file, (percent) => {
    onProgress({
      phase: 'hashing',
      percent: Math.round(percent * 0.1), // 指纹阶段最多占 10% 进度
      uploadedBytes: 0,
      totalBytes: total,
      speed: 0,
      message: `正在计算文件指纹… ${percent}%`,
    })
  })
  if (signal.aborted) {
    throw new DOMException('aborted', 'AbortError')
  }

  // ---- 2) 尝试秒传 ----
  if (args.tryInstant !== false) {
    const instant = await uploadApi.instantUpload({
      md5: fingerprint,
      parentId,
      name: file.name,
      size: total,
      contentType,
    })
    if (instant.hit && instant.fileId) {
      uploadedBytes = total
      report('done', '秒传成功')
      return { commit: instant, instant: true }
    }
  }

  // ---- 3) 申请上传凭证（服务端确定 ObjectKey；需要时先过人机验证） ----
  const ticket = await requestTicket({ name: file.name, size: total, contentType })
  if (ticket.maxFileSizeBytes > 0 && total > ticket.maxFileSizeBytes) {
    throw new ApiError(
      CODE.BAD_PARAM,
      `文件超过单文件上限 ${Math.round(ticket.maxFileSizeBytes / 1024 / 1024)}MB`,
    )
  }

  const partSize = ticket.partSize
  const partCount = Math.max(1, Math.ceil(total / partSize))

  // ---- 4) 上传（小文件单次 PUT，大文件分片） ----
  if (partCount <= 1) {
    // contentType 由服务端在签名时确定，必须原样发送
    const put = await uploadApi.putUrl(ticket.uploadToken, contentType)
    uploadedBytes = 0
    report('uploading', '正在上传…')
    await putWithRetry(put.url, file, {
      signal,
      contentType: put.contentType,
      onProgress: (loaded) => {
        uploadedBytes = loaded
        report('uploading')
      },
    })
    uploadedBytes = total
  } else {
    uploadedBytes = await uploadMultipart({
      file,
      ticket,
      partSize,
      partCount,
      userId,
      signal,
      report,
      onBytes: (bytes) => {
        uploadedBytes = bytes
      },
    })
  }

  // ---- 5) 建立索引（幂等） ----
  report('committing', '正在登记到网盘…')
  const commit = await uploadApi.commit({
    uploadToken: ticket.uploadToken,
    parentId,
    name: file.name,
    contentType,
    md5: fingerprint,
  })
  clearCheckpoint(userId, file)
  uploadedBytes = total
  report('done')
  return { commit, instant: false }
}

interface MultipartArgs {
  file: File
  ticket: { uploadToken: string; objectKey: string; partSize: number }
  partSize: number
  partCount: number
  userId: number
  signal: AbortSignal
  report: (phase: UploadPhase, message?: string) => void
  onBytes: (bytes: number) => void
}

async function uploadMultipart(args: MultipartArgs): Promise<number> {
  const { file, ticket, partSize, partCount, userId, signal, report, onBytes } = args

  // 已上传分片：partNumber -> etag 与已计入的字节
  const doneParts = new Map<number, string>()
  let uploadId = ''
  let completedBytes = 0

  // ---- 尝试续传：校验本地断点是否仍然有效 ----
  const checkpoint = readCheckpoint(userId, file)
  if (checkpoint && checkpoint.uploadId) {
    try {
      const remote = await uploadApi.uploadedParts(checkpoint.uploadToken, checkpoint.uploadId)
      if (remote.length > 0) {
        uploadId = checkpoint.uploadId
        for (const part of remote) {
          doneParts.set(part.partNumber, part.etag)
          completedBytes += part.size
        }
        report('uploading', `已恢复 ${remote.length} 个分片，继续上传…`)
      }
    } catch {
      // 凭证过期或分片已被清理：从头开始
      clearCheckpoint(userId, file)
    }
  }

  if (!uploadId) {
    const init = await uploadApi.initMultipart(ticket.uploadToken, file.type || undefined)
    uploadId = init.uploadId
    writeCheckpoint(userId, file, {
      fileKey: fileKeyOf(file),
      uploadToken: ticket.uploadToken,
      uploadId,
      partSize,
      parts: [],
    })
  }
  onBytes(completedBytes)

  // ---- 计算待上传分片 ----
  const pending: number[] = []
  for (let partNumber = 1; partNumber <= partCount; partNumber++) {
    if (!doneParts.has(partNumber)) {
      pending.push(partNumber)
    }
  }

  // ---- 并发上传 ----
  /** 各分片当前的进度（字节），让进度条在分片内部也能平滑推进 */
  const partial = new Map<number, number>()
  const recompute = () => {
    let sum = completedBytes
    partial.forEach((value) => {
      sum += value
    })
    onBytes(sum)
  }

  // 先把所有待上传分片的 URL 批量取回：
  // 逐个分片请求会让业务服务器成为瓶颈（2GB / 5MB = 400 个分片）。
  const allUrls = new Map<number, string>()
  let partContentType = 'application/octet-stream'
  for (let i = 0; i < pending.length; i += URL_BATCH) {
    const batch = pending.slice(i, i + URL_BATCH)
    const resp = await uploadApi.partUrls(ticket.uploadToken, uploadId, batch)
    // 分片 PUT 必须携带服务端签名时用的 Content-Type，否则签名不匹配
    partContentType = resp.contentType
    resp.urls.forEach((item) => allUrls.set(item.partNumber, item.url))
  }

  let index = 0
  const pool = async () => {
    while (true) {
      if (signal.aborted) {
        throw new DOMException('aborted', 'AbortError')
      }
      const current = index++
      if (current >= pending.length) {
        return
      }
      const partNumber = pending[current]
      const start = (partNumber - 1) * partSize
      const end = Math.min(start + partSize, file.size)
      const blob = file.slice(start, end)
      const url = allUrls.get(partNumber)
      if (!url) {
        throw new ApiError(CODE.BAD_PARAM, `分片 ${partNumber} 未取到上传地址`)
      }
      const etag = await putWithRetry(url, blob, {
        signal,
        // 必须与服务端签名时的 Content-Type 一致
        contentType: partContentType,
        onProgress: (loaded) => {
          partial.set(partNumber, loaded)
          recompute()
        },
      })
      // 成功的分片 PUT，OSS 一定会返回 ETag。
      // 取不到只可能是跨域规则没暴露它（缺 Access-Control-Expose-Headers: ETag）。
      // 这种情况若继续往下走，会在 complete 阶段报一个完全看不懂的 OSS 错误，
      // 所以在这里就拦下来，直接告诉运维该改哪里。
      if (!etag) {
        throw new ApiError(
          -1,
          'OSS 未返回 ETag：请在 OSS 桶的跨域规则里把 ETag 加入「暴露 Headers（Expose-Headers）」',
        )
      }
      partial.delete(partNumber)
      doneParts.set(partNumber, etag)
      completedBytes += end - start
      recompute()
      report('uploading')
      writeCheckpoint(userId, file, {
        fileKey: fileKeyOf(file),
        uploadToken: ticket.uploadToken,
        uploadId,
        partSize,
        parts: Array.from(doneParts.entries()).map(([pn, tag]) => ({ partNumber: pn, etag: tag })),
      })
    }
  }

  const runners: Promise<void>[] = []
  const parallel = Math.min(MAX_PARALLEL, Math.max(1, pending.length))
  for (let i = 0; i < parallel; i++) {
    runners.push(pool())
  }
  await Promise.all(runners)

  // ---- 完成分片上传 ----
  const parts = Array.from(doneParts.entries())
    .map(([partNumber, etag]) => ({ partNumber, etag }))
    .sort((a, b) => a.partNumber - b.partNumber)
  if (parts.length !== partCount) {
    throw new ApiError(-1, `分片数量不完整（${parts.length}/${partCount}），请重试`)
  }
  await uploadApi.completeMultipart(ticket.uploadToken, uploadId, parts)
  return completedBytes
}

/** 放弃上传：通知服务端清理未完成分片，避免 OSS 持续计费 */
export async function abortUpload(userId: number, file: File): Promise<void> {
  const checkpoint = readCheckpoint(userId, file)
  if (!checkpoint) {
    return
  }
  try {
    await uploadApi.abortMultipart(checkpoint.uploadToken, checkpoint.uploadId)
  } catch {
    // 凭证过期时服务端无法定位分片，交给 OSS 生命周期规则兜底
  }
  clearCheckpoint(userId, file)
}
