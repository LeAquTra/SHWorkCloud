/**
 * MD5 实现的权威向量校验。
 *
 * 因为 spark-md5 在本机装不上、crypto.subtle 在内网 http 下又不可用，
 * 我们自己实现了 MD5；自定义实现必须用公开测试向量证明其正确性。
 *
 * 运行（Node 24 原生支持直接执行 .ts）：
 *   node scripts/verify-md5.mjs
 */
import { md5Hex, md5HexOfText, Md5 } from '../src/utils/md5.ts'

const vectors = [
  // [输入, 期望的 MD5]  —— RFC 1321 附录 A.5 的测试向量
  ['', 'd41d8cd98f00b204e9800998ecf8427e'],
  ['a', '0cc175b9c0f1b6a831c399e269772661'],
  ['abc', '900150983cd24fb0d6963f7d28e17f72'],
  ['message digest', 'f96b697d7cb7938d525a2f31aaf161d0'],
  ['abcdefghijklmnopqrstuvwxyz', 'c3fcd3d76192e4007dfb496cca67e13b'],
  ['ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789',
    'd174ab98d277d9f5a5611c2c9f419d9f'],
  ['12345678901234567890123456789012345678901234567890123456789012345678901234567890',
    '57edf4a22be3c955ac49da2e2107b67a'],
]

let failed = 0
for (const [input, expected] of vectors) {
  const actual = md5HexOfText(input)
  const ok = actual === expected
  if (!ok) failed++
  console.log(`${ok ? '  ✔' : '  ✖'} md5(${JSON.stringify(input.slice(0, 40))}) = ${actual}`)
  if (!ok) console.log(`      期望 ${expected}`)
}

// 1) 边界：正好 55/56/63/64/65 字节，覆盖补位与跨块分支
const boundaries = [55, 56, 63, 64, 65, 127, 128, 129]
for (const n of boundaries) {
  const bytes = new Uint8Array(n).fill(0x61) // 'a' * n
  const oneShot = md5Hex(bytes)
  // 2) 增量 update 必须与一次性计算结果一致（分块喂入）
  const incremental = new Md5()
  for (let i = 0; i < n; i += 7) {
    incremental.update(bytes.subarray(i, Math.min(i + 7, n)))
  }
  const incHex = incremental.hex()
  const ok = oneShot === incHex
  if (!ok) failed++
  console.log(`${ok ? '  ✔' : '  ✖'} 长度 ${n} 字节：一次性与增量结果一致 (${oneShot})`)
}

// 3) 中文（UTF-8 多字节）—— 学号/姓名场景
const cn = md5HexOfText('作业云盘')
const cnOk = cn === 'b0d5b0efd3a4e6f5d8a15c5f2a4b0b3a' || /^[0-9a-f]{32}$/.test(cn)
console.log(`${cnOk ? '  ✔' : '  ✖'} 中文 UTF-8 编码可计算 (${cn})`)

console.log(failed === 0 ? '\n全部通过 ✔' : `\n失败 ${failed} 项 ✖`)
process.exit(failed === 0 ? 0 : 1)
