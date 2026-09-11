/**
 * 纯 TypeScript 实现的 MD5。
 *
 * 为什么自己实现：
 * 1. `spark-md5` 在本机离线环境的 npm 缓存里没有，装不上；
 * 2. `crypto.subtle` 在**内网 http（非安全上下文）下不存在** ——
 *    机房正是这种环境，所以不能用 Web Crypto。
 *
 * 已用权威测试向量验证（见 README：`node scripts/verify-md5.mjs`）。
 * 支持增量 update，便于在 Web Worker 里按 4MB 分块读取大文件，避免一次性载入内存。
 */

/** 每轮的左移位数 */
const S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]

/** K[i] = floor(abs(sin(i + 1)) * 2^32) */
const K = [
  0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
  0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
  0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
  0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
  0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
  0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
  0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
  0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
  0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
  0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
  0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
  0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
  0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
  0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
  0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
  0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
]

function rotl(value: number, bits: number): number {
  return ((value << bits) | (value >>> (32 - bits))) | 0
}

export class Md5 {
  private a = 0x67452301
  private b = 0xefcdab89
  private c = 0x98badcfe
  private d = 0x10325476

  private readonly buffer = new Uint8Array(64)
  private bufferLen = 0
  private totalLen = 0

  /** 增量喂入数据（可多次调用） */
  update(data: Uint8Array): this {
    this.totalLen += data.length
    let offset = 0

    // 先把上次残留的半个 block 填满
    if (this.bufferLen > 0) {
      const need = 64 - this.bufferLen
      const take = Math.min(need, data.length)
      this.buffer.set(data.subarray(0, take), this.bufferLen)
      this.bufferLen += take
      offset = take
      if (this.bufferLen === 64) {
        this.processBlock(this.buffer, 0)
        this.bufferLen = 0
      }
    }

    // 整块处理
    while (offset + 64 <= data.length) {
      this.processBlock(data, offset)
      offset += 64
    }

    // 剩下的留到下次
    if (offset < data.length) {
      this.buffer.set(data.subarray(offset), 0)
      this.bufferLen = data.length - offset
    }
    return this
  }

  /** 取小写十六进制摘要（调用后本实例不应再 update） */
  hex(): string {
    // 补齐：0x80 + 若干 0，使长度 ≡ 56 (mod 64)，再放 64 位比特长度（小端）
    const bits = this.totalLen * 8
    const lo = bits % 0x100000000
    const hi = Math.floor(bits / 0x100000000)

    const padLen = this.bufferLen < 56 ? 56 - this.bufferLen : 120 - this.bufferLen
    const pad = new Uint8Array(padLen + 8)
    pad[0] = 0x80
    pad[padLen] = lo & 0xff
    pad[padLen + 1] = (lo >>> 8) & 0xff
    pad[padLen + 2] = (lo >>> 16) & 0xff
    pad[padLen + 3] = (lo >>> 24) & 0xff
    pad[padLen + 4] = hi & 0xff
    pad[padLen + 5] = (hi >>> 8) & 0xff
    pad[padLen + 6] = (hi >>> 16) & 0xff
    pad[padLen + 7] = (hi >>> 24) & 0xff
    this.update(pad)

    return (
      toHexLE(this.a) + toHexLE(this.b) + toHexLE(this.c) + toHexLE(this.d)
    )
  }

  private processBlock(bytes: Uint8Array, offset: number): void {
    const m = new Int32Array(16)
    for (let i = 0; i < 16; i++) {
      const p = offset + i * 4
      m[i] = (bytes[p] | (bytes[p + 1] << 8) | (bytes[p + 2] << 16) | (bytes[p + 3] << 24)) | 0
    }

    let a = this.a
    let b = this.b
    let c = this.c
    let d = this.d

    for (let i = 0; i < 64; i++) {
      // F 必须在旋转赋值之前算好（它依赖旧的 b/c/d）
      let f: number
      let g: number
      if (i < 16) {
        f = (b & c) | (~b & d)
        g = i
      } else if (i < 32) {
        f = (d & b) | (~d & c)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        f = b ^ c ^ d
        g = (3 * i + 5) % 16
      } else {
        f = c ^ (b | ~d)
        g = (7 * i) % 16
      }

      const tmp = d
      d = c
      c = b
      b = (b + rotl((a + f + K[i] + m[g]) | 0, S[i])) | 0
      a = tmp
    }

    this.a = (this.a + a) | 0
    this.b = (this.b + b) | 0
    this.c = (this.c + c) | 0
    this.d = (this.d + d) | 0
  }
}

function toHexLE(value: number): string {
  let out = ''
  for (let i = 0; i < 4; i++) {
    out += (((value >>> (i * 8)) & 0xff) + 0x100).toString(16).slice(1)
  }
  return out
}

/** 一次性计算 MD5 */
export function md5Hex(data: Uint8Array): string {
  return new Md5().update(data).hex()
}

/** 一次性计算字符串的 MD5（按 UTF-8 编码） */
export function md5HexOfText(text: string): string {
  return md5Hex(new TextEncoder().encode(text))
}
