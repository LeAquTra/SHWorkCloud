<template>
  <div class="upload-panel">
    <header class="panel-head">
      <div class="head-line">
        <span class="title">
          <el-icon><UploadFilled /></el-icon>
          <span>传输列表</span>
        </span>
        <el-button
          v-if="store.tasks.length"
          link
          type="primary"
          :disabled="store.unfinishedCount > 0"
          @click="store.clearFinished()"
        >
          清空已完成
        </el-button>
      </div>

      <!-- 总体进度：文件级串行，所以"还剩几件"比单文件百分比更重要 -->
      <div v-if="store.tasks.length" class="summary">
        <el-progress
          :percentage="overallPercent"
          :stroke-width="6"
          :show-text="false"
          :status="store.failedCount > 0 && store.unfinishedCount === 0 ? 'exception' : undefined"
        />
        <div class="summary-text sc-tabular">
          <span>{{ store.finishedCount }}/{{ store.tasks.length }} 已完成</span>
          <span v-if="store.failedCount" class="danger">{{ store.failedCount }} 个失败</span>
          <span v-else-if="store.unfinishedCount" class="sc-muted">{{ store.unfinishedCount }} 个进行中</span>
        </div>
      </div>
    </header>

    <div v-if="store.tasks.length === 0" class="empty">
      <div class="empty-art"><el-icon><Upload /></el-icon></div>
      <strong>暂无传输任务</strong>
      <p class="sc-muted">把文件拖到左侧列表区域，或点「上传文件」开始</p>
    </div>

    <div v-else class="task-list sc-scroll-y">
      <article v-for="task in store.tasks" :key="task.id" class="task" :class="`p-${task.phase}`">
        <div class="task-line">
          <FileGlyph :tone="toneOf(task)" :size="28" />
          <div class="task-name" :title="task.name">
            <strong>{{ task.name }}</strong>
            <small class="sc-tabular">
              {{ formatSize(task.size) }}
              <template v-if="task.phase === 'uploading' && task.speed > 0">
                · {{ formatSize(task.speed) }}/s
              </template>
            </small>
          </div>
          <el-tag :type="tagType(task)" size="small" effect="light">{{ phaseLabel(task) }}</el-tag>
        </div>

        <el-progress
          :percentage="task.phase === 'done' ? 100 : task.percent"
          :status="progressStatus(task)"
          :stroke-width="6"
          :show-text="false"
        />

        <div class="task-status">
          <span class="status-text">{{ statusText(task) }}</span>
          <span class="sc-tabular sc-muted">{{ task.phase === 'done' ? 100 : task.percent }}%</span>
        </div>

        <!-- 提交凭证：机房下课前用来确认"真的存上去了" -->
        <div v-if="task.phase === 'done' && task.receipt" class="receipt">
          <el-icon><CircleCheckFilled /></el-icon>
          <span>提交凭证</span>
          <code>{{ task.receipt }}</code>
          <el-button link type="primary" @click="copyReceipt(task.receipt)">复制</el-button>
        </div>

        <div v-if="task.phase === 'failed'" class="error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ task.error }}</span>
        </div>

        <div class="task-actions">
          <el-button
            v-if="task.phase === 'uploading' || task.phase === 'hashing'"
            link
            type="primary"
            @click="store.pause(task.id)"
          >
            暂停
          </el-button>
          <el-button
            v-if="task.phase === 'paused' || task.phase === 'failed'"
            link
            type="primary"
            @click="store.resume(task.id)"
          >
            {{ task.phase === 'failed' ? '重试' : '继续' }}
          </el-button>
          <el-button v-if="task.phase !== 'done'" link type="danger" @click="store.cancel(task.id)">
            取消
          </el-button>
          <el-button v-else link @click="store.remove(task.id)">移除</el-button>
        </div>
      </article>
    </div>

    <footer v-if="store.tasks.length" class="panel-foot">
      <el-icon><InfoFilled /></el-icon>
      <span>进度到 100% 只是传完，看到「已保存到网盘」才算真的存好。</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CircleCheckFilled,
  InfoFilled,
  Upload,
  UploadFilled,
  WarningFilled,
} from '@element-plus/icons-vue'
import FileGlyph from '@/components/FileGlyph.vue'
import { useUploadStore, type UploadTask } from '@/stores/uploader'
import { copyText, fileTone, formatSize } from '@/utils/format'

const store = useUploadStore()

const overallPercent = computed(() => {
  if (!store.tasks.length) {
    return 0
  }
  const total = store.tasks.reduce((sum, task) => sum + (task.phase === 'done' ? 100 : task.percent), 0)
  return Math.round(total / store.tasks.length)
})

function toneOf(task: UploadTask) {
  const dot = task.name.lastIndexOf('.')
  const suffix = dot > 0 ? task.name.slice(dot + 1) : ''
  return fileTone({ folder: false, suffix })
}

function phaseLabel(task: UploadTask): string {
  switch (task.phase) {
    case 'pending':
      return '排队'
    case 'hashing':
      return '校验'
    case 'uploading':
      return '上传中'
    // OSS 传完 ≠ 保存成功，这一阶段必须单独显示
    case 'committing':
      return '登记中'
    case 'done':
      return '已保存'
    case 'paused':
      return '已暂停'
    default:
      return '失败'
  }
}

function tagType(task: UploadTask): 'success' | 'warning' | 'danger' | 'info' {
  if (task.phase === 'done') return 'success'
  if (task.phase === 'failed') return 'danger'
  if (task.phase === 'paused') return 'warning'
  return 'info'
}

function progressStatus(task: UploadTask): '' | 'success' | 'exception' | 'warning' {
  if (task.phase === 'done') return 'success'
  if (task.phase === 'failed') return 'exception'
  if (task.phase === 'paused') return 'warning'
  return ''
}

function statusText(task: UploadTask): string {
  switch (task.phase) {
    case 'pending':
      return '排队中…'
    case 'hashing':
      return task.message || '正在计算文件指纹…'
    case 'uploading':
      return task.message || '上传中…'
    case 'committing':
      return task.message || '正在登记到网盘…'
    case 'done':
      return task.message || '已保存到网盘'
    case 'paused':
      return task.message || '已暂停，可继续上传'
    case 'failed':
      return task.error || '上传失败'
    default:
      return ''
  }
}

async function copyReceipt(receipt: string) {
  const ok = await copyText(receipt)
  if (ok) {
    ElMessage.success('凭证已复制')
  } else {
    ElMessage.warning(`复制失败，请手动记录：${receipt}`)
  }
}
</script>

<style scoped>
.upload-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel-head {
  padding: 16px 18px 12px;
  border-bottom: 1px solid var(--sc-border);
  flex: 0 0 auto;
}

.head-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.summary {
  margin-top: 12px;
}

.summary-text {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--sc-text-3);
  margin-top: 5px;
}

.danger {
  color: var(--sc-danger);
}

/* ---------------- 空态 ---------------- */

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 24px;
  text-align: center;
}

.empty-art {
  width: 62px;
  height: 62px;
  border-radius: var(--sc-radius-lg);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  color: var(--sc-brand);
  background: var(--sc-brand-softer);
  border: 1px dashed var(--sc-brand-ring);
  margin-bottom: 10px;
}

.empty strong {
  font-size: 13.5px;
}

.empty p {
  margin: 2px 0 0;
  font-size: 12px;
  max-width: 220px;
  line-height: 1.6;
}

/* ---------------- 任务 ---------------- */

.task-list {
  flex: 1;
  min-height: 0;
  padding: 12px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task {
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  padding: 11px 12px;
  background: var(--sc-surface-2);
  transition: border-color var(--sc-dur) var(--sc-ease);
  animation: sc-fade-up var(--sc-dur) var(--sc-ease) both;
}

.task.p-done {
  border-color: color-mix(in srgb, var(--sc-success) 32%, transparent);
}

.task.p-failed {
  border-color: color-mix(in srgb, var(--sc-danger) 34%, transparent);
}

.task-line {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 9px;
}

.task-name {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.task-name strong {
  font-size: 13px;
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-name small {
  font-size: 11px;
  color: var(--sc-text-3);
}

.task-status {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--sc-text-2);
  margin-top: 5px;
  gap: 8px;
}

.status-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.receipt,
.error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 11.5px;
}

.receipt {
  color: var(--sc-success);
  flex-wrap: wrap;
}

.receipt code {
  background: color-mix(in srgb, var(--sc-success) 12%, transparent);
  padding: 1px 6px;
  border-radius: var(--sc-radius-xs);
  color: var(--sc-text);
}

.error {
  color: var(--sc-danger);
  align-items: flex-start;
}

.task-actions {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
  margin-top: 6px;
}

.panel-foot {
  flex: 0 0 auto;
  display: flex;
  gap: 7px;
  align-items: flex-start;
  padding: 11px 18px;
  border-top: 1px solid var(--sc-border);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--sc-text-3);
}
</style>
