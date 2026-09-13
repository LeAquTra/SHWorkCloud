<template>
  <el-dialog
    :model-value="modelValue"
    :width="dialogWidth"
    top="4vh"
    destroy-on-close
    class="preview-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="preview-head">
        <FileGlyph :tone="tone" :size="34" />
        <div class="head-text">
          <strong :title="item?.name">{{ item?.name || '预览' }}</strong>
          <div class="head-meta">
            <el-tag size="small" effect="light" type="info">{{ viewLabel }}</el-tag>
            <span class="sc-muted">{{ item ? formatSize(item.size) : '' }}</span>
            <span v-if="textData?.charset" class="sc-muted">编码 {{ textData.charset }}</span>
          </div>
        </div>
      </div>
    </template>

    <div class="preview-body" :class="{ 'is-text': isText }">
      <div v-if="loading" class="loading">
        <el-skeleton animated :rows="6" />
      </div>

      <el-result
        v-else-if="error"
        icon="warning"
        :title="error"
        sub-title="可以尝试直接下载后用本地软件打开"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
          <el-button @click="download">下载文件</el-button>
        </template>
      </el-result>

      <!--
        媒体加载失败（图片 / 视频 / 音频）。
        以前这里什么都不做，浏览器就显示"一张裂图 + 文件名（alt 文本）"，
        既看不出原因也没有出路。现在给出可操作的解释与按钮。
      -->
      <el-result
        v-else-if="mediaError"
        icon="warning"
        title="媒体加载失败"
        sub-title="签名地址可能已过期，或该文件已被清理（例如 OSS 对账判定为无引用对象）。可重试或直接下载。"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
          <el-button @click="download">下载文件</el-button>
        </template>
      </el-result>

      <!-- 图片：点击在「适应窗口 / 原始大小」之间切换 -->
      <div v-else-if="kind === 'image'" class="image-stage" :class="{ zoomed }" @click="zoomed = !zoomed">
        <img :src="streamUrl" :alt="item?.name" @error="mediaError = true" />
      </div>

      <iframe v-else-if="kind === 'pdf'" class="pdf-frame" :src="streamUrl" :title="item?.name" />

      <div v-else-if="kind === 'video'" class="video-stage">
        <video :src="streamUrl" controls playsinline preload="metadata" @error="mediaError = true" />
      </div>

      <div v-else-if="kind === 'audio'" class="audio-stage">
        <div class="audio-art">
          <el-icon><Headset /></el-icon>
        </div>
        <div class="audio-info">
          <strong>{{ item?.name }}</strong>
          <span class="sc-muted">{{ formatSize(item?.size || 0) }}</span>
        </div>
        <audio :src="streamUrl" controls preload="metadata" @error="mediaError = true" />
      </div>

      <div v-else-if="isText" class="text-stage">
        <el-alert
          v-if="textData?.hint"
          class="text-notice"
          type="info"
          :closable="false"
          show-icon
          :title="textData.hint"
        />
        <el-alert
          v-if="textData?.truncated"
          class="text-notice"
          type="warning"
          :closable="false"
          show-icon
          :title="`内容过长，仅显示前 ${textData.maxChars} 个字符`"
          description="需要完整内容请下载文件后用本地软件打开。"
        />

        <div class="text-toolbar">
          <el-tag size="small" effect="light" type="info">
            {{ lineCount === null ? '超长文本' : `${lineCount} 行` }}
          </el-tag>
          <el-tag v-if="hasHtml" size="small" effect="light" type="success">原格式</el-tag>
          <div class="spacer" />
          <el-switch
            v-if="hasHtml"
            v-model="showRaw"
            size="small"
            active-text="纯文本"
            inactive-text="原格式"
            inline-prompt
          />
          <el-switch v-else v-model="wrap" size="small" active-text="自动换行" />
          <el-button link type="primary" @click="copyAll">
            <el-icon><CopyDocument /></el-icon>
            <span>复制全文</span>
          </el-button>
        </div>

        <!--
          docx / xlsx 的"原格式"视图：HTML 由服务端把 OOXML 结构化渲染而成
          （段落 / 表格 / 加粗 / 对齐 / 内嵌图片），文本内容已在服务端全部转义。
          这里再过一次前端白名单，作为第二道防线 —— 避免"服务端某处漏转义"
          就直接变成 XSS。
        -->
        <div v-if="hasHtml && !showRaw" class="doc-html" v-html="safeHtml"></div>

        <div v-else class="code" :class="{ wrap, 'with-embed': embeddedImages.length > 0 }">
          <div v-if="lineCount !== null" class="gutter">
            <span v-for="n in lineCount" :key="n">{{ n }}</span>
          </div>
          <pre class="code-body">{{ textData?.content }}</pre>
        </div>

        <!--
          docx / pptx 内嵌图片。
          正文提取只能拿到纯文字（XML 标签连同图片一起被剥掉），
          所以图文作业的图必须由 /embedded-images 单独取回来。
          以 data URL 渲染：不需要签名地址，也不占 OSS 对象。
        -->
        <div v-if="embeddedImages.length || embeddedSkipped" class="embed-block">
          <div class="text-toolbar">
            <el-tag size="small" effect="light" type="success">
              文档内嵌图片 {{ embeddedImages.length }} 张
            </el-tag>
            <span v-if="embeddedSkipped" class="sc-muted">
              另有 {{ embeddedSkipped }} 张未显示（过大，或是 emf / wmf 等浏览器无法渲染的矢量图）
            </span>
          </div>
          <div class="embed-grid">
            <el-image
              v-for="(image, index) in embeddedImages"
              :key="image.name + index"
              :src="image.dataUrl"
              :preview-src-list="embeddedPreviewList"
              :initial-index="index"
              preview-teleported
              hide-on-click-modal
              fit="contain"
              class="embed-thumb"
            >
              <template #error>
                <span class="embed-fail">无法显示</span>
              </template>
            </el-image>
          </div>
        </div>
      </div>

      <el-result v-else icon="info" title="该格式不支持在线预览" sub-title="请下载后用本地软件打开">
        <template #extra>
          <el-button type="primary" @click="download">下载文件</el-button>
        </template>
      </el-result>
    </div>

    <template #footer>
      <div class="preview-foot">
        <span class="sc-muted foot-hint">
          图片 / PDF / 视频走 10 分钟有效的签名地址；文本读取由后端处理 GBK 编码。
        </span>
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        <el-button type="primary" @click="download">
          <el-icon><Download /></el-icon>
          <span>下载</span>
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Download, Headset } from '@element-plus/icons-vue'
import FileGlyph from '@/components/FileGlyph.vue'
import { fileApi } from '@/api'
import { ApiError } from '@/api/http'
import type { EmbeddedImageVO, FileItemVO, TextContentVO } from '@/types/api'
import {
  VIEW_TYPE_LABELS,
  copyText,
  fileTone,
  formatSize,
  resolveViewType,
  sanitizeOfficeHtml,
  triggerDownload,
} from '@/utils/format'

/**
 * 在线阅览弹窗。
 *
 * <p>按后端下发的 viewType 分流（接口手册 §0.4）：
 *   image / pdf / video / audio → 取签名地址直接给标签用
 *   text / office               → 取 JSON 文本，后端已处理 GBK / BOM
 *   none                        → 只能下载
 *
 * <p>为什么不自己判断后缀：<img> / <video> 这类标签无法携带 Authorization，
 * 必须用签名地址；而能不能拿到签名地址由服务端按白名单决定，前端猜没有意义。
 */

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    item: FileItemVO | null
    /** 相册等场景已经拿到了签名地址，传进来就不必再请求一次 */
    presignedUrl?: string
  }>(),
  { presignedUrl: '' },
)

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const loading = ref(false)
const error = ref('')
const streamUrl = ref('')
const textData = ref<TextContentVO | null>(null)
/** docx / pptx 内嵌图片（data URL），正文里没有它们 */
const embeddedImages = ref<EmbeddedImageVO[]>([])
const embeddedSkipped = ref(0)
const zoomed = ref(false)
const wrap = ref(true)
/** 图片/视频/音频加载失败（签名失效、对象被清理等）—— 由标签的 error 事件置位 */
const mediaError = ref(false)
/** 有服务端 HTML 时，是否强制看纯文本（默认看原格式） */
const showRaw = ref(false)

const hasHtml = computed(() => !!textData.value?.html)
const safeHtml = computed(() => sanitizeOfficeHtml(textData.value?.html || ''))

/** 点任意一张都能在查看器里左右翻看全部图片 */
const embeddedPreviewList = computed(() => embeddedImages.value.map((image) => image.dataUrl))

const kind = computed(() => (props.item ? resolveViewType(props.item) : 'none'))
const tone = computed(() =>
  props.item ? fileTone({ folder: false, suffix: props.item.suffix }) : 'other',
)
const viewLabel = computed(() => VIEW_TYPE_LABELS[kind.value])
const isText = computed(() => kind.value === 'text' || kind.value === 'office')
const dialogWidth = computed(() => (isText.value ? '80%' : '76%'))

const lineCount = computed(() => {
  const text = textData.value?.content
  if (!text) {
    return 0
  }
  const count = text.split('\n').length
  // 20 万字的文本拆成行号会明显卡顿，超过阈值就不渲染行号列
  return count > 3000 ? null : count
})

async function load() {
  const item = props.item
  if (!item) {
    return
  }
  loading.value = true
  error.value = ''
  streamUrl.value = ''
  textData.value = null
  embeddedImages.value = []
  embeddedSkipped.value = 0
  zoomed.value = false
  mediaError.value = false

  try {
    if (isText.value) {
      textData.value = await fileApi.text(item.id)
      // docx / pptx 的图不在正文里，要再取一次。
      // 这一步是"锦上添花"：内嵌图片接口失败不能连带让正文也显示不出来，
      // 所以单独 try，失败就只是不显示图片区。
      if (kind.value === 'office') {
        try {
          const embedded = await fileApi.embeddedImages(item.id)
          embeddedImages.value = embedded.images || []
          embeddedSkipped.value = embedded.skipped || 0
        } catch {
          embeddedImages.value = []
          embeddedSkipped.value = 0
        }
      }
    } else if (kind.value === 'none') {
      // 不支持预览的格式不做请求，直接展示下载引导
    } else if (props.presignedUrl) {
      streamUrl.value = props.presignedUrl
    } else {
      const result = await fileApi.previewUrl(item.id)
      // ⚠️ 契约守卫：preview-url 必须返回 { url } 对象。
      // 若后端还是旧版（裸字符串），这里 result.url 会是 undefined，
      // 而 <img src="undefined"> 只会"裂开一张图"、控制台往往毫无提示，
      // 很难判断是后端契约变了还是网络问题。所以在这里直接给出明确原因。
      if (!result || typeof result.url !== 'string' || !result.url) {
        throw new ApiError(-1, '没能取到预览地址（响应格式不正确，请确认后端已升级）')
      }
      streamUrl.value = result.url
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '预览加载失败'
  } finally {
    loading.value = false
  }
}

async function download() {
  const item = props.item
  if (!item) {
    return
  }
  try {
    const result = await fileApi.downloadUrl(item.id)
    triggerDownload(result.url)
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '获取下载地址失败')
  }
}

async function copyAll() {
  const text = textData.value?.content || ''
  const ok = await copyText(text)
  ElMessage[ok ? 'success' : 'warning'](ok ? '已复制全文' : '复制失败，请手动选择后复制')
}

watch(
  () => [props.modelValue, props.item?.id, props.presignedUrl],
  ([visible]) => {
    if (visible) {
      void load()
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.preview-head {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.head-text {
  min-width: 0;
}

.head-text strong {
  display: block;
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 52vw;
}

.head-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  margin-top: 2px;
}

.preview-body {
  min-height: 220px;
}

.preview-body.is-text {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.loading {
  padding: 8px 0;
}

/* ---------- 图片 ---------- */

.image-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  max-height: 72vh;
  overflow: auto;
  border-radius: var(--sc-radius);
  background:
    linear-gradient(45deg, var(--sc-hover) 25%, transparent 25%) 0 0 / 22px 22px,
    linear-gradient(-45deg, var(--sc-hover) 25%, transparent 25%) 0 11px / 22px 22px,
    linear-gradient(45deg, transparent 75%, var(--sc-hover) 75%) 11px -11px / 22px 22px,
    linear-gradient(-45deg, transparent 75%, var(--sc-hover) 75%) -11px 0 / 22px 22px;
  cursor: zoom-in;
  padding: 8px;
}

.image-stage.zoomed {
  cursor: zoom-out;
}

.image-stage img {
  max-width: 100%;
  max-height: 68vh;
  border-radius: var(--sc-radius-sm);
  transition: transform var(--sc-dur) var(--sc-ease);
}

.image-stage.zoomed img {
  max-width: none;
  max-height: none;
}

/* ---------- PDF / 视频 ---------- */

.pdf-frame {
  width: 100%;
  height: 74vh;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  background: var(--sc-surface-2);
}

.video-stage {
  display: flex;
  justify-content: center;
  background: #000;
  border-radius: var(--sc-radius);
  overflow: hidden;
}

.video-stage video {
  max-width: 100%;
  max-height: 72vh;
}

/* ---------- 音频 ---------- */

.audio-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 40px 24px;
  border-radius: var(--sc-radius-lg);
  background-image: var(--sc-gradient-veil);
  border: 1px solid var(--sc-border);
}

.audio-art {
  width: 92px;
  height: 92px;
  border-radius: var(--sc-radius-xl);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40px;
  color: #fff;
  background-image: var(--sc-gradient-cyan);
  box-shadow: var(--sc-shadow-brand);
}

.audio-info {
  text-align: center;
}

.audio-info strong {
  display: block;
  margin-bottom: 2px;
}

.audio-stage audio {
  width: min(520px, 100%);
}

/* ---------- 文本 ---------- */

.text-notice {
  margin: 0;
}

.text-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.text-toolbar .spacer {
  flex: 1;
}

.code {
  display: flex;
  max-height: 58vh;
  overflow: auto;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  background: var(--sc-surface-2);
  font-size: 12.5px;
  line-height: 1.65;
}

/* 有内嵌图片时给正文让一点高度，保证图和字能同时看到 */
.code.with-embed {
  max-height: 34vh;
}

/* ---------- docx / pptx 内嵌图片 ---------- */

.embed-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.embed-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  background: var(--sc-surface-2);
}

.embed-thumb {
  width: 136px;
  height: 104px;
  border-radius: var(--sc-radius-xs);
  border: 1px solid var(--sc-border);
  background: var(--sc-surface);
  cursor: zoom-in;
}

.embed-fail {
  font-size: 12px;
  color: var(--sc-text-3);
}

/* ---------- docx / xlsx 原格式视图 ---------- */

.doc-html {
  max-height: 58vh;
  overflow: auto;
  padding: 20px 22px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  background: var(--sc-surface);
  color: var(--sc-text);
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
}

.doc-html :deep(h1),
.doc-html :deep(h2),
.doc-html :deep(h3),
.doc-html :deep(h4),
.doc-html :deep(h5),
.doc-html :deep(h6) {
  margin: 0.9em 0 0.45em;
  line-height: 1.35;
}

.doc-html :deep(p) {
  margin: 0 0 0.55em;
}

.doc-html :deep(.sc-li) {
  display: flex;
  gap: 8px;
}

.doc-html :deep(.sc-li-mark) {
  color: var(--sc-brand);
}

.doc-html :deep(.sc-tab) {
  display: inline-block;
  width: 2em;
}

.doc-html :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0 1em;
  width: 100%;
}

.doc-html :deep(td) {
  border: 1px solid var(--sc-border);
  padding: 6px 10px;
  vertical-align: top;
}

.doc-html :deep(td p) {
  margin: 0;
}

.doc-html :deep(.sc-doc-img) {
  max-width: 100%;
  height: auto;
  margin: 6px 0;
  border-radius: var(--sc-radius-xs);
}

.doc-html :deep(.sc-sheet-name) {
  font-weight: 650;
  margin-bottom: 6px;
}

.doc-html :deep(.sc-sheet-gap) {
  height: 18px;
}

.doc-html :deep(.sc-xlsx-table td) {
  font-variant-numeric: tabular-nums;
}

.doc-html :deep(.sc-truncated) {
  color: var(--sc-text-3);
  font-size: 12.5px;
}

.gutter {
  display: flex;
  flex-direction: column;
  padding: 12px 10px 12px 14px;
  text-align: right;
  color: var(--sc-text-3);
  background: var(--sc-hover);
  border-right: 1px solid var(--sc-border);
  user-select: none;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-variant-numeric: tabular-nums;
  position: sticky;
  left: 0;
}

.code-body {
  margin: 0;
  padding: 12px 16px;
  white-space: pre;
  color: var(--sc-text);
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  tab-size: 4;
}

.code.wrap .code-body {
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 底栏 ---------- */

.preview-foot {
  display: flex;
  align-items: center;
  gap: 10px;
}

.foot-hint {
  flex: 1;
  font-size: 12px;
  text-align: left;
}
</style>
