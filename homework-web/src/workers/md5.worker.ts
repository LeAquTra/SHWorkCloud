/**
 * MD5 / 指纹计算 Worker。
 *
 * 放在 Worker 里是为了不阻塞界面：机房电脑性能偏弱，
 * 一个大文件的全量 MD5 可能要几十秒，跑在主线程会让页面直接卡死。
 *
 * 策略（与文档 §3.7 一致）：
 * - ≤ 阈值：全量 MD5，秒传命中准确；
 * - > 阈值：抽样指纹 MD5(前 1MB + 后 1MB + size + name)，
 *   避免为了秒传先算几十秒哈希，收益抵不过等待成本。
 */
import { Md5 } from '@/utils/md5'

const CHUNK_SIZE = 4 * 1024 * 1024
const SAMPLE_SIZE = 1024 * 1024

interface HashRequest {
  /** 用 Blob 而不是 File：调用方可能需要按分片传入 */
  file: Blob
  fileName: string
  /** 超过该大小改用抽样指纹 */
  sampleThreshold: number
}

export interface HashProgress {
  percent: number
}

self.onmessage = async (event: MessageEvent<HashRequest>) => {
  const { file, fileName, sampleThreshold } = event.data
  try {
    const started = Date.now()
    const hex =
      file.size > sampleThreshold
        ? await sampleFingerprint(file, fileName)
        : await fullMd5(file)
    ;(self as unknown as Worker).postMessage({
      ok: true,
      hex,
      sampled: file.size > sampleThreshold,
      elapsedMs: Date.now() - started,
    })
  } catch (error) {
    ;(self as unknown as Worker).postMessage({
      ok: false,
      error: error instanceof Error ? error.message : '计算文件指纹失败',
    })
  }
}

/** 分块读取，避免把整个大文件读进内存 */
async function fullMd5(blob: Blob): Promise<string> {
  const md5 = new Md5()
  let offset = 0
  while (offset < blob.size) {
    const end = Math.min(offset + CHUNK_SIZE, blob.size)
    const buffer = await blob.slice(offset, end).arrayBuffer()
    md5.update(new Uint8Array(buffer))
    offset = end
    ;(self as unknown as Worker).postMessage({
      type: 'progress',
      percent: blob.size === 0 ? 100 : Math.round((offset / blob.size) * 100),
    })
  }
  return md5.hex()
}

/** 抽样指纹：首尾各 1MB + size + 文件名 */
async function sampleFingerprint(blob: Blob, fileName: string): Promise<string> {
  const md5 = new Md5()
  const head = await blob.slice(0, Math.min(SAMPLE_SIZE, blob.size)).arrayBuffer()
  md5.update(new Uint8Array(head))
  if (blob.size > SAMPLE_SIZE) {
    const tail = await blob.slice(Math.max(0, blob.size - SAMPLE_SIZE)).arrayBuffer()
    md5.update(new Uint8Array(tail))
  }
  md5.update(new TextEncoder().encode(`:${blob.size}:${fileName}`))
  return md5.hex()
}
