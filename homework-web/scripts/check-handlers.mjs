/**
 * 事件处理器签名自检 —— 拦住"把方法名直接当事件处理器，而它的第一个参数不是事件"。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 背景（用户报的真实故障，v1 修复）
 *
 *   后台「社区审核」点右上角**刷新**按钮 → 页面提示"加载失败"。
 *
 *   模板写的是 `@click="reload"`，而方法签名是 `reload(toPage?: number)`。
 *   Vue 对 `@click="fn"` 的语义是"把事件对象作为第一个实参调用 fn"，
 *   所以 `reload(PointerEvent)` 被调用；函数体里只写了 `if (toPage)`，
 *   事件对象是真值 → `page.value` 被赋成一个 PointerEvent →
 *   axios 序列化成 `page=[object PointerEvent]` → 服务端 `Long page` 绑不上 →
 *   返回 40000「参数类型错误: page」→ 用户看到的就是"点刷新就加载失败"。
 *
 *   这个错误 **esbuild / rollup 不会报**（语法完全合法），`tsc --noEmit` 也不会报
 *   （`.vue` 模板不在它的检查范围里，本机也没有 vue-tsc 可装）。所以只能靠这个脚本。
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 判定规则（宁少报不误报，避免被当成噪声而整体忽略）：
 *   - 只看 `@事件="名字"` 这种**裸方法名**形式（`@click="assist(row)"` 这类带调用的不查）；
 *   - 只看**原生 DOM 事件名**（`click` / `change` / `input` …）。组件自定义事件
 *     （`@node-click` / `@confirm` / `@select`）传的是子组件 `emit` 出来的值，
 *     常常正好就是个 id 或布尔 —— 那些写法是对的，报了就是噪声；
 *   - 该名字必须是 `<script setup>` 里声明的函数；
 *   - 只有当它能接收到的第一个参数**装不下事件对象**时才报：
 *       · `() => …` / `(e: MouseEvent) => …` / `(row: Row) => …`  → 安全，不报；
 *       · `(page: number) => …` / `(flag: boolean) => …`          → 报错；
 *       · `(toPage?: number) => …`                                 → 报错
 *         （这正是本次故障：可选只说明"不传也行"，不说明"传事件对象也对"）。
 *
 * 用法：node scripts/check-handlers.mjs
 * 误报请改脚本的判定规则，**不要**往白名单里塞新的例外 —— 例外会让它失去意义。
 */

import { readFileSync } from 'node:fs'
import { readdir } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const SRC = join(ROOT, 'src')

/**
 * 原生 DOM 事件名（全站常用的一批）。
 *
 * <p>为什么必须靠名字而不是靠标签名区分：`<el-button @click="reload">` 里的
 * el-button 是组件，但它的 `click` 是**原生事件透传**（`emits: { click: evt =>
 * evt instanceof MouseEvent }`），运行期收到的**确实是** PointerEvent ——
 * 本次故障正是这么发生的。反过来 `@node-click` / `@confirm` 这类名字根本不是
 * DOM 事件，只可能来自 emit。
 */
const DOM_EVENTS = new Set([
  'click', 'dblclick', 'auxclick', 'contextmenu', 'mousedown', 'mouseup', 'mousemove',
  'mouseenter', 'mouseleave', 'mouseover', 'mouseout', 'wheel',
  'pointerdown', 'pointerup', 'pointermove', 'pointerenter', 'pointerleave',
  'pointerover', 'pointerout', 'pointercancel', 'gotpointercapture', 'lostpointercapture',
  'keydown', 'keyup', 'keypress',
  'touchstart', 'touchend', 'touchmove', 'touchcancel',
  'input', 'change', 'focus', 'blur', 'focusin', 'focusout', 'submit', 'reset',
  'scroll', 'scrollend', 'select', 'invalid',
  'drag', 'dragstart', 'dragend', 'dragenter', 'dragleave', 'dragover', 'drop',
  'copy', 'cut', 'paste', 'animationstart', 'animationend', 'animationiteration',
  'transitionstart', 'transitionend', 'transitionrun', 'transitioncancel',
])

/**
 * 事件处理器允许"吃下事件对象"的类型。
 * 只列真正与 DOM 事件兼容的：这些类型的变量接收一个 Event 是合法的。
 */
const EVENT_TYPES = new Set([
  'Event', 'UIEvent', 'MouseEvent', 'PointerEvent', 'KeyboardEvent', 'TouchEvent',
  'FocusEvent', 'InputEvent', 'WheelEvent', 'DragEvent', 'ClipboardEvent', 'SubmitEvent',
  'EventTarget', 'unknown', 'any',
])

/** 明确"装不下事件对象"的原始类型：传进去只会在运行期变成一坨错数据 */
const INCOMPATIBLE_TYPES = new Set(['number', 'bigint', 'boolean'])

// ---------------------------------------------------------------- 组件 emit 解析
//
// 光看标签名分不出"这个 @select 是 DOM 事件还是组件 emit 出来的值"：
//   <FolderTree @select="switchFolder">   → FolderTree 声明了 emits.select: [id: number]
//                                            （普通组件**不**透传同名原生事件）
//   <el-button @click="reload">           → el-button 声明的是 click: evt => evt instanceof MouseEvent
//                                            （组件库有 emit 声明，但那就是透传的原生事件）
// 所以要看**组件声明的 emit 载荷类型**：载荷是 number/boolean 之类就是组件事件（正确写法），
// 载荷是事件对象或压根没声明，才是"原生事件会被当成第一个实参"的那种情形。

const COMPONENTS_DIR = join(SRC, 'components')
const ELEMENT_PLUS_ES = join(ROOT, 'node_modules', 'element-plus', 'es', 'components')

/** 组件名 → (事件名 → 载荷类型 | null)，null 表示解析不出载荷类型 */
const componentEmits = new Map()

/** 把 `<script>` / `<script setup>` 里的 `defineEmits` 解析成 事件 → 载荷类型 */
function parseEmits(source) {
  const events = new Map()

  // ① 类型式：defineEmits<{ select: [id: number] }>()
  const typeForm = source.match(/defineEmits\s*<([\s\S]*?)>\s*\(\s*\)/)
  if (typeForm) {
    const body = typeForm[1]
    const re = /([A-Za-z_$][\w$]*)\s*\??\s*:\s*\[([^\]]*)\]/g
    let m
    while ((m = re.exec(body))) {
      const payload = m[2].trim()
      // 取第一个载荷参数的类型；空元组 = 无载荷
      const first = splitTopLevel(payload)[0]
      events.set(m[1], first ? (parseParam(first).type ?? null) : null)
    }
    return events
  }

  // ② 运行时式：defineEmits(['select']) / defineEmits({ select: null })
  const runForm = source.match(/defineEmits\s*\(\s*(\[[^\]]*\]|\{[\s\S]*?\})\s*\)/)
  if (runForm) {
    const body = runForm[1]
    for (const m of body.matchAll(/['"]([\w:-]+)['"]/g)) {
      events.set(m[1], null)
    }
  }
  return events
}

/** 读一个 .vue 文件声明的 emit */
function emitsOfComponent(file) {
  const source = readFileSync(file, 'utf8')
  const events = new Map()
  const scriptRe = /<script[^>]*>([\s\S]*?)<\/script>/g
  let script
  while ((script = scriptRe.exec(source))) {
    for (const [event, type] of parseEmits(script[1])) {
      if (!events.has(event)) {
        events.set(event, type)
      }
    }
  }
  return events
}

/**
 * 读 element-plus 组件的 emit 声明。
 * <p>组件库是 **unplugin 按需引入**：同一个组件名可能落在
 * `es/components/<kebab>/<kebab>/src/index.mjs` 或 `es/components/<kebab>/src/index.mjs`，
 * 所以两种深度都试一次。读不到就返回 {@code null}（当作"没有 emit 声明"，
 * 于 `@click` 这类原生事件仍然会被检查 —— 这正是本次故障要守住的那条路径）。
 */
function emitsOfElementPlus(tag) {
  const kebab = tag.toLowerCase()
  const candidates = [
    join(ELEMENT_PLUS_ES, kebab, kebab, 'src', 'index.mjs'),
    join(ELEMENT_PLUS_ES, kebab, 'src', 'index.mjs'),
  ]
  for (const candidate of candidates) {
    let source
    try {
      source = readFileSync(candidate, 'utf8')
    } catch {
      continue
    }
    const events = new Map()
    for (const m of source.matchAll(/['"]([\w-]+)['"]\s*:\s*\(([^)]*)\)/g)) {
      const param = m[2].trim()
      events.set(m[1], param ? (parseParam(param).type ?? null) : null)
    }
    return events
  }
  return null
}

/** 组件名 → emit 表（缓存；null 表示这个标签不是已知组件） */
function emitsOf(tag) {
  const key = tag.toLowerCase()
  if (componentEmits.has(key)) {
    return componentEmits.get(key)
  }
  let events = null
  try {
    events = emitsOfComponent(join(COMPONENTS_DIR, `${tag}.vue`))
  } catch {
    events = null
  }
  if (events === null && key.startsWith('el-')) {
    events = emitsOfElementPlus(key)
  }
  componentEmits.set(key, events)
  return events
}

/**
 * 组件声明的这个事件的载荷类型；返回 {@code undefined} 表示"组件没声明这个事件"。
 *
 * <p>返回值的含义：
 * <ul>
 *   <li>{@code number} / {@code boolean} …（在 {@link INCOMPATIBLE_TYPES} 里）
 *       → 组件自己 emit 出来的值，写成裸方法名是对的，**不检查**；</li>
 *   <li>{@code MouseEvent} / {@code Event} … → 组件透传的就是原生事件，
 *       与原生标签同一条规则，**检查**；</li>
 *   <li>{@code null}（声明了但解析不出载荷）或 {@code undefined}（没声明这个事件）
 *       → 原生事件会落到处理器上，**检查**。</li>
 * </ul>
 *
 * <p>⚠️ 这里体现了一个容易搞反的事实：`<el-button @click="h">` 的 `click`
 * **确实**被 el-button 声明了（`click: evt => evt instanceof MouseEvent`），
 * 但它就是原生事件透传 —— 所以"声明过 emit 就跳过"是错的，
 * 必须看载荷类型。本次故障正好踩在这一点上。
 */
function declaredEmitType(tag, event) {
  const events = emitsOf(tag)
  return events === null ? undefined : events.get(event)
}

/** 解析 `<script setup>` 里所有 `function 名字(...)` 的形参表（含多行签名） */
function collectFunctions(script) {
  const functions = new Map()
  const re = /\bfunction\s+([A-Za-z_$][\w$]*)\s*(?:<[^>(]*>)?\s*\(([\s\S]*?)\)\s*(?::[^;{]*)?\{/g
  let m
  while ((m = re.exec(script))) {
    const name = m[1]
    const params = m[2].trim()
    // 顶层形参：按逗号切，但要跳过泛型/默认值里可能出现的括号与逗号
    const list = splitTopLevel(params)
    functions.set(name, list.map(parseParam))
  }
  return functions
}

/** 按顶层逗号切分形参表（忽略 `a = fn(x, y)` / `m: Map<string, number>` 里的逗号） */
function splitTopLevel(params) {
  const out = []
  let depth = 0
  let current = ''
  for (const ch of params) {
    if ('<([{'.includes(ch)) depth++
    if ('>)]}'.includes(ch)) depth--
    if (ch === ',' && depth === 0) {
      out.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  if (current.trim()) out.push(current)
  return out.map((s) => s.trim()).filter(Boolean)
}

/** 形参 → { type, optional, rest }；没写类型时 type 为 null（视为未知，不报） */
function parseParam(raw) {
  const rest = raw.startsWith('...')
  const body = raw.replace(/^\.\.\./, '')
  const optional = /\?\s*:/.test(body) || body.includes('=')
  // 取"最外层"的冒号：`arg: { a: number }` 的冒号是第一个
  const colon = body.indexOf(':')
  if (colon === -1) {
    return { type: null, optional, rest }
  }
  const name = body.slice(0, colon).trim().replace(/\?$/, '')
  // 默认值在类型后面：`page: number = 1`
  const type = body.slice(colon + 1).split('=')[0].trim().replace(/\?$/, '')
  return { type: type || null, optional, rest, name }
}

/**
 * 收集"裸方法名"形式的 DOM 事件绑定，返回 [{ tag, event, name, where }]。
 *
 * <p>只保留 {@link DOM_EVENTS} 里的事件名 —— 组件自定义事件（`@node-click` /
 * `@confirm`）传的是子组件 emit 的值，不是事件对象，那些写法是对的。
 * 同名但载荷不是事件的组件事件（如 FolderTree 的 `@select`）在扫描时再排除，
 * 见 {@link declaredEmitType}。
 */
function collectBareHandlers(template) {
  const out = []
  const cleaned = template.replace(/<!--[\s\S]*?-->/g, '')
  // 从每个起始标签里取标签名 + 属性串
  const tagRe = /<([A-Za-z][\w.-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g
  let tag
  while ((tag = tagRe.exec(cleaned))) {
    const tagName = tag[1]
    const attrs = tag[2]
    const attrRe = /(?:^|\s)[@#]([\w:.-]+)\s*=\s*"([^"]*)"/g
    let attr
    while ((attr = attrRe.exec(attrs))) {
      const event = attr[1]
      if (!DOM_EVENTS.has(event)) {
        continue
      }
      const raw = attr[2].trim()
      // 只认裸名字；带括号/点/运算符的一律跳过（那些是显式调用，参数由写代码的人负责）
      if (/^[A-Za-z_$][\w$]*$/.test(raw)) {
        out.push({ tag: tagName, event, name: raw, where: `<${tagName} @${event}="${raw}">` })
      }
    }
  }
  return out
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

/**
 * 自检：用真实故障的原始代码当样本，证明解析链路**确实**能报出这一类问题。
 * <p>没有这段自检的话，"扫描 0 个处理器"和"扫描了但都没问题"看起来一模一样 ——
 * 守卫会安静地空转通过，正是它最该拦住的那种失败方式。
 */
function selfCheck() {
  const failingTemplate = `
    <el-button :loading="loading" @click="reload">
      <span>刷新</span>
    </el-button>
  `
  const failingScript = `
    async function reload(toPage?: number) {
      if (toPage) { page.value = toPage }
    }
  `
  const functions = collectFunctions(failingScript)
  const handlers = collectBareHandlers(failingTemplate)
  const reload = functions.get('reload')
  const hit = handlers.find((h) => h.name === 'reload')
  const caught = Boolean(
    reload && hit && INCOMPATIBLE_TYPES.has(firstType(reload[0])),
  )
  if (!caught) {
    console.log('✖ 自检失败：解析器识别不出「@click="reload" + reload(toPage?: number)」'
      + '这一真实故障样本，本脚本已失效')
    process.exit(1)
  }

  // 反向样本 1：组件自定义事件传的是 emit 出来的值，必须**不**报
  const componentEvent = collectBareHandlers('<FolderTree @node-click="onNodeClick" />')
  if (componentEvent.length > 0) {
    console.log('✖ 自检失败：把组件自定义事件当成 DOM 事件了（会产生误报）')
    process.exit(1)
  }

  // 反向样本 2：显式调用（带括号）由写代码的人负责，不查
  const explicitCall = collectBareHandlers('<button @click="reload(1)">刷新</button>')
  if (explicitCall.length > 0) {
    console.log('✖ 自检失败：把带括号的显式调用也当成裸方法名了')
    process.exit(1)
  }

  // 反向样本 3：第一个参数是 Event 的处理器必须放行
  const eventParam = collectFunctions('function onPick(e: MouseEvent) {}').get('onPick')
  if (!EVENT_TYPES.has(firstType(eventParam[0]))) {
    console.log('✖ 自检失败：连 (e: MouseEvent) 都不认识')
    process.exit(1)
  }
}

/** 取形参的"基础类型名"（去掉 `| undefined` 与数组后缀） */
function firstType(param) {
  return String(param.type)
    .replace(/\s*\|\s*undefined$/, '')
    .replace(/\[\]$/, '')
}

selfCheck()

const files = await walk(SRC)
const problems = []
let checked = 0

for (const file of files) {
  const source = readFileSync(file, 'utf8')
  const templateMatch = source.match(/<template>([\s\S]*)<\/template>/)
  const scriptMatch = source.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)
  if (!templateMatch || !scriptMatch) {
    continue
  }

  const functions = collectFunctions(scriptMatch[1])
  for (const handler of collectBareHandlers(templateMatch[1])) {
    // 组件自己 emit 出来的值（载荷不是事件对象）→ 裸方法名是对的，跳过
    const declared = declaredEmitType(handler.tag, handler.event)
    if (declared !== undefined && INCOMPATIBLE_TYPES.has(String(declared))) {
      continue
    }
    const params = functions.get(handler.name)
    if (!params || params.length === 0) {
      continue // 不是本文件声明的函数（组件 / store action / 无参函数）→ 安全
    }
    const first = params[0]
    if (first.rest || first.type === null) {
      continue // 没写类型（或 rest 形参）→ 无法判定，不报
    }
    checked++
    const base = firstType(first)
    if (EVENT_TYPES.has(base)) {
      continue
    }
    if (INCOMPATIBLE_TYPES.has(base)) {
      problems.push({
        file: relative(ROOT, file),
        handler: handler.name,
        type: first.type,
        optional: first.optional,
        where: handler.where,
      })
    }
  }
}

const headline =
  `✔ 已检查 ${files.length} 个 SFC，其中 ${checked} 个「裸方法名」DOM 事件处理器签名与事件对象兼容`

if (!problems.length) {
  console.log(headline)
  process.exit(0)
}

console.log(`✖ ${problems.length} 个事件处理器的第一个参数装不下事件对象：\n`)
for (const p of problems) {
  const call = '@…="' + p.handler + '()"'
  const sig = '(' + p.handler + ': Event)'
  const fix = p.optional
    ? `把它改成 ${call}（或把签名改成 ${sig}）—— `
      + '可选参数只表示"不传也行"，Vue 仍然会把事件对象传进来并占据这个位置'
    : `把它改成 ${call}（或把第一个参数改成 Event 类型）`
  console.log(`  ${p.file}`)
  console.log(`      ${p.where}`)
  console.log(`      → ${p.handler}(第一参: ${p.type}${p.optional ? '，可选' : ''})  ${fix}`)
  console.log('')
}
console.log('说明：本脚本守的是"点一下按钮就报参数类型错误"这一类缺陷 ——')
console.log('      典型症状是接口收到 [object PointerEvent] 这种脏参数。\n')
process.exit(1)
