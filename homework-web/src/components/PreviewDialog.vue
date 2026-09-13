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

      <!-- 图片：点击在「适应窗口 / 原始大小」之间切换 -->
      <div v-else-if="kind === 'image'" class="image-stage" :class="{ zoomed }" @click="zoomed = !zoomed">
        <img :src="streamUrl" :alt="item?.name" />
      </div>

      <iframe v-else-if="kind === 'pdf'" class="pdf-frame" :src="streamUrl" :title="item?.name" />

      <div v-else-if="kind === 'video'" class="video-stage">
        <video :src="streamUrl" controls playsinline preload="metadata" />
      </div>

      <div v-else-if="kind === 'audio'" class="audio-stage">
        <div class="audio-art">
          <el-icon><Headset /></el-icon>
        </div>
        <div class="audio-info">
          <strong>{{ item?.name }}</strong>
          <span class="sc-muted">{{ formatSize(item?.size || 0) }}</span>
        </div>
        <audio :src="streamUrl" controls preload="metadata" />
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
          <div class="spacer" />
          <el-switch v-model="wrap" size="small" active-text="自动换行" />
          <el-button link type="primary" @click="copyAll">
            <el-icon><CopyDocument /></el-icon>
            <span>复制全文</span>
          </el-button>
        </div>

        <div class="code" :class="{ wrap }">
          <div v-if="lineCount !== null" class="gutter">
            <span v-for="n in lineCount" :key="n">{{ n }}</span>
          </div>
          <pre class="code-body">{{ textData?.content }}</pre>
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
import type { FileItemVO, TextContentVO } from '@/types/api'
import {
  VIEW_TYPE_LABELS,
  copyText,
  fileTone,
  formatSize,
  resolveViewType,
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
const zoomed = ref(false)
const wrap = ref(true)

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
  zoomed.value = false

  try {
    if (isText.value) {
      textData.value = await fileApi.text(item.id)
    } else if (kind.value === 'none') {
      // 不支持预览的格式不做请求，直接展示下载引导
    } else if (props.presignedUrl) {
      streamUrl.value = props.presignedUrl
    } else {
      const result = await fileApi.previewUrl(item.id)
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
