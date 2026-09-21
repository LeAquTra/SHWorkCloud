/**
 * 用编译器的公开 API 全量编译一遍所有 SFC 的模板。
 * `vite build` 在本机沙箱里跑不起来（esbuild 需要 spawn 子进程 → EPERM），
 * 这个脚本走 @vue/compiler-sfc 的进程内编译，用来补上"模板语法确实过编译"的证明。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { parse, compileTemplate, compileScript } from '@vue/compiler-sfc'

const SRC = join(process.cwd(), 'src')

function walk(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (name.endsWith('.vue')) out.push(full)
  }
  return out
}

const files = walk(SRC)
const problems = []
let templates = 0
let scripts = 0

for (const file of files) {
  const source = readFileSync(file, 'utf8')
  const rel = relative(process.cwd(), file)
  const { descriptor, errors } = parse(source, { filename: file })
  for (const e of errors) {
    problems.push(`${rel}: SFC 解析错误 ${e.message}`)
  }

  if (descriptor.scriptSetup || descriptor.script) {
    try {
      compileScript(descriptor, { id: rel })
      scripts++
    } catch (e) {
      problems.push(`${rel}: <script> 编译失败 ${e.message}`)
    }
  }

  for (const t of descriptor.template ? [descriptor.template] : []) {
    const result = compileTemplate({
      source: t.content,
      filename: rel,
      id: rel,
      compilerOptions: { bindingMetadata: { __sc: true } },
    })
    templates++
    for (const e of result.errors) {
      problems.push(`${rel}: 模板编译错误 ${typeof e === 'string' ? e : e.message}`)
    }
  }
}

console.log(`已编译 ${files.length} 个 SFC（模板 ${templates} 个，脚本 ${scripts} 个）`)
if (problems.length) {
  console.log('\n发现问题：')
  for (const p of problems) console.log('  ' + p)
  process.exit(1)
}
console.log('✔ 全部通过 Vue 编译器（模板 + <script setup>）')
