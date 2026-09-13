/**
 * 模板标识符自检。
 *
 * 构建（esbuild / rollup）只做语法层面的转换，**不会**发现模板里写错的变量名 ——
 * 那类错误要等运行时渲染才炸。这个脚本用正则把 `<script setup>` 里声明的名字
 * 与 `<template>` 里引用的名字对一遍，把"引用了但没声明"的标识符报出来。
 *
 * 用法：node scripts/check-templates.mjs
 * 说明：纯启发式，宁可有少量误报，也不要漏掉真实拼写错误。误报请加进 ALLOW。
 */

import { readFileSync } from 'node:fs'
import { readdir } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const SRC = join(ROOT, 'src')

/** 模板里合法出现、但不由 <script setup> 声明的名字 */
const ALLOW = new Set([
  // JS 全局
  'true', 'false', 'null', 'undefined', 'NaN', 'Infinity',
  'Math', 'Date', 'Number', 'String', 'Boolean', 'Array', 'Object', 'JSON',
  'Promise', 'Set', 'Map', 'WeakMap', 'Error', 'RegExp', 'Symbol', 'BigInt',
  'parseInt', 'parseFloat', 'isNaN', 'isFinite', 'encodeURIComponent', 'decodeURIComponent',
  'console', 'window', 'document', 'location', 'navigator', 'history', 'localStorage',
  'sessionStorage', 'URL', 'Blob', 'File', 'FileReader', 'FormData', 'XMLHttpRequest',
  'AbortController', 'DOMException', 'Worker', 'Intl', 'structuredClone',
  // 模板语法关键字 / 修饰符
  'in', 'of', 'as', 'new', 'typeof', 'instanceof', 'return', 'if', 'else', 'void', 'delete',
  'this', 'key', 'ref', 'value', 'type', 'class', 'style', 'id', 'name', 'slot', 'props',
  '$event', '$slots', '$attrs', '$refs', 'string', 'number', 'boolean', 'any', 'unknown',
  'prevent', 'stop', 'once', 'capture', 'passive', 'self', 'exact', 'native', 'trim', 'number',
  'lazy', 'enter', 'blur', 'change', 'submit', 'click', 'input', 'focus',
  // 常见 HTML/SVG 属性名被误当标识符
  'width', 'height', 'min', 'max', 'step', 'rows', 'size', 'label', 'title', 'placeholder',
  'disabled', 'readonly', 'checked', 'selected', 'multiple', 'accept', 'columns', 'border',
  'total', 'page', 'layout', 'status', 'color', 'offset', 'fill', 'stroke', 'd', 'r', 'x', 'y',
  'cx', 'cy', 'viewBox', 'preserveAspectRatio', 'xmlns', 'role', 'aria', 'alt', 'src', 'href',
  'target', 'rel', 'loading', 'decoding', 'controls', 'playsinline', 'preload', 'loop', 'muted',
  'autoplay', 'poster', 'draggable', 'hidden', 'multiple', 'modal', 'gutter', 'shadow', 'zIndex',
  'pattern', 'format', 'trigger', 'message', 'required', 'validator', 'rule', 'callback',
  'effect', 'plain', 'round', 'circle', 'link', 'text', 'success', 'warning', 'danger', 'info',
  'primary', 'small', 'large', 'default', 'light', 'dark', 'auto', 'horizontal', 'vertical',
  'inside', 'outside', 'top', 'bottom', 'left', 'right', 'center', 'middle', 'start', 'end',
  'solid', 'dashed', 'none', 'always', 'never', 'hover', 'active', 'current', 'currentPage',
  'pageSize', 'pageSizes', 'showText', 'strokeWidth', 'strokeLinecap', 'strokeDasharray',
  'strokeDashoffset', 'objectFit', 'aspectRatio', 'backdropFilter', 'linearGradient',
  'stopColor', 'startColor', 'endColor', 'preserve', 'ariaLabel', 'ariaPressed', 'role',
  'autocomplete', 'maxlength', 'showPassword', 'showWordLimit', 'resize', 'clearable',
  'filterable', 'allowCreate', 'defaultFirst', 'defaultExpandAll', 'expandOnClickNode',
  'nodeKey', 'highlightCurrent', 'indent', 'rowKey', 'showOverflowTooltip', 'fixed',
  'align', 'selectable', 'prop', 'width', 'minWidth', 'maxHeight', 'top', 'destroyOnClose',
  'closeOnClickModal', 'modelValue', 'confirmButtonText', 'cancelButtonText', 'inputValue',
  'inputPlaceholder', 'showAfter', 'content', 'placement', 'trigger', 'divided', 'command',
  'elementLoadingText', 'loadingText', 'valueFormat', 'disabledDate', 'type',
  'accept', 'webkitdirectory',
])

/** 从 v-for / 插槽作用域里收集循环变量名 */
function collectScopedNames(template) {
  const names = new Set()
  // v-for="item in items" / v-for="(a, b) in items" / v-for="n in 5"
  const forRe = /v-for\s*=\s*"([^"]*)"/g
  let m
  while ((m = forRe.exec(template))) {
    const head = m[1].split(/\bin\b|\bof\b/)[0]
    head.replace(/[()]/g, '')
      .split(',')
      .map((s) => s.trim().split(/[=:]/)[0].trim())
      .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
      .forEach((s) => names.add(s))
  }
  // #default="{ row }" / v-slot:default="{ data }"
  const slotRe = /#(?:default|[a-z-]+)\s*=\s*"\{([^}]*)\}"/g
  while ((m = slotRe.exec(template))) {
    m[1]
      .split(',')
      .map((s) => s.trim().split(':').pop().split('=')[0].trim())
      .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
      .forEach((s) => names.add(s))
  }
  // <template #default="{ data }"> 之外，还有 v-slot 的字符串形式
  const slotRe2 = /v-slot(?::[\w-]+)?\s*=\s*"\{([^}]*)\}"/g
  while ((m = slotRe2.exec(template))) {
    m[1]
      .split(',')
      .map((s) => s.trim().split(':').pop().split('=')[0].trim())
      .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
      .forEach((s) => names.add(s))
  }
  return names
}

/** 从 <script setup> 里收集声明过的顶层名字 */
function collectDeclaredNames(script) {
  const names = new Set()

  const patterns = [
    /\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g,
    /\bfunction\s+([A-Za-z_$][\w$]*)/g,
    /\bclass\s+([A-Za-z_$][\w$]*)/g,
    // import Foo from '...' / import { A, B as C } from '...'
    /\bimport\s+([A-Za-z_$][\w$]*)\s*(?:,|from)/g,
    /\bimport\s*\{([^}]*)\}/g,
    // defineProps<{ a: string; b?: number }>() 之类的字段名也算（模板里直接当变量用）
    /defineProps<\{([\s\S]*?)\}>\(\)/g,
  ]

  for (const re of patterns) {
    let m
    while ((m = re.exec(script))) {
      const body = m[1]
      if (body.includes(':') && !body.includes(',')) {
        // props 类型字面量：取每个字段名
        body.replace(/\/\*[\s\S]*?\*\//g, '')
          .split(/[\n;,]/)
          .map((line) => line.split(':')[0].trim().replace(/\?$/, ''))
          .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
          .forEach((s) => names.add(s))
        continue
      }
      body
        .split(',')
        .map((s) => s.trim().replace(/^type\s+/, '').split(/\s+as\s+/).pop().trim())
        .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
        .forEach((s) => names.add(s))
    }
  }

  // 解构声明：const { a, b } = ...
  let m
  const destructRe = /\b(?:const|let|var)\s*\{([^}]*)\}\s*=/g
  while ((m = destructRe.exec(script))) {
    m[1]
      .split(',')
      .map((s) => s.trim().split(':').pop().trim().split('=')[0].trim())
      .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
      .forEach((s) => names.add(s))
  }
  const destructArrRe = /\b(?:const|let|var)\s*\[([^\]]*)\]\s*=/g
  while ((m = destructArrRe.exec(script))) {
    m[1]
      .split(',')
      .map((s) => s.trim())
      .filter((s) => /^[A-Za-z_$][\w$]*$/.test(s))
      .forEach((s) => names.add(s))
  }

  return names
}

/** 收集模板里被引用的根标识符 */
function collectUsedNames(template) {
  const used = new Map()

  // 去掉 <style> 之类残留；注释也去掉，避免注释里的示例代码误报
  const cleaned = template
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<\/?[A-Za-z][\w.-]*/g, ' <tag')   // 标签名不算标识符

  const expressions = []
  // 插值
  for (const m of cleaned.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    expressions.push(m[1])
  }
  // 指令与绑定
  for (const m of cleaned.matchAll(/(?:^|\s)(?:v-[\w:.-]+|[:@#][\w:.-]*|v-bind|v-on)\s*=\s*"([^"]*)"/g)) {
    expressions.push(m[1])
  }
  for (const m of cleaned.matchAll(/(?:^|\s)(?:v-[\w:.-]+|[:@#][\w:.-]*)\s*=\s*'([^']*)'/g)) {
    expressions.push(m[1])
  }

  for (const expr of expressions) {
    // 去掉字符串字面量，去掉属性访问的后半段
    const bare = expr
      .replace(/'[^']*'/g, ' ')
      .replace(/"[^"]*"/g, ' ')
      .replace(/`[^`]*`/g, ' ')
    for (const m of bare.matchAll(/(^|[^.\w$])([A-Za-z_$][\w$]*)(?![:\w$])/g)) {
      const name = m[2]
      if (!used.has(name)) {
        used.set(name, expr.trim().slice(0, 70))
      }
    }
  }
  return used
}

async function walk(dir) {
  const out = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...(await walk(full)))
    } else if (entry.name.endsWith('.vue')) {
      out.push(full)
    }
  }
  return out
}

const files = await walk(SRC)
const problems = []

for (const file of files) {
  const source = readFileSync(file, 'utf8')
  const templateMatch = source.match(/<template>([\s\S]*)<\/template>/)
  const scriptMatch = source.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)
  if (!templateMatch || !scriptMatch) {
    continue
  }

  const template = templateMatch[1]
  const script = scriptMatch[1]

  const declared = collectDeclaredNames(script)
  const scoped = collectScopedNames(template)
  const used = collectUsedNames(template)

  const missing = []
  for (const [name, where] of used) {
    if (declared.has(name) || scoped.has(name) || ALLOW.has(name)) {
      continue
    }
    // 纯小写单词很可能是 HTML 属性被正则误抓，降噪
    if (/^[a-z]+$/.test(name) && name.length <= 4) {
      continue
    }
    missing.push(`${name}  ←  ${where}`)
  }

  if (missing.length) {
    problems.push({ file: relative(ROOT, file), missing })
  }
}

if (!problems.length) {
  console.log(`✔ 已检查 ${files.length} 个 SFC，未发现模板引用了未声明的标识符`)
  process.exit(0)
}

console.log(`✖ ${problems.length} 个文件可能存在模板引用问题：\n`)
for (const problem of problems) {
  console.log(`  ${problem.file}`)
  for (const line of problem.missing) {
    console.log(`      ${line}`)
  }
  console.log('')
}
process.exit(1)
