<template>
  <div class="upload-panel">
    <div class="panel-header">
      <span class="title">
        上传任务
        <el-tag v-if="store.unfinishedCount > 0" size="small" type="warning">
          {{ store.unfinishedCount }} 进行中
        </el-tag>
        <el-tag v-if="store.failedCount > 0" size="small" type="danger">
          {{ store.failedCount }} 失败
        </el-tag>
      </span>
      <el-button link type="primary" @click="store.clearFinished()">清空已完成</el-button>
    </div>

    <div v-if="store.tasks.length === 0" class="empty">暂无上传任务</div>

    <div v-else class="task-list">
      <div v-for="task in store.tasks" :key="task.id" class="task">
        <div class="task-line">
          <span class="task-name" :title="task.name">{{ task.name }}</span>
          <span class="task-size">{{ formatSize(task.size) }}</span>
          <span class="task-actions">
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
              继续
            </el-button>
            <el-button
              v-if="task.phase !== 'done'"
              link
              type="danger"
              @click="store.cancel(task.id)"
            >
              取消
            </el-button>
            <el-button v-else link @click="store.remove(task.id)">移除</el-button>
          </span>
        </div>

        <el-progress
          :percentage="task.phase === 'done' ? 100 : task.percent"
          :status="progressStatus(task)"
          :stroke-width="8"
        />

        <div class="task-status">
          <span>{{ statusText(task) }}</span>
          <span v-if="task.speed > 0 && task.phase === 'uploading'" class="speed">
            {{ formatSize(task.speed) }}/s
          </span>
        </div>

        <!-- 提交凭证：机房下课前用来确认"真的存上去了" -->
        <div v-if="task.phase === 'done' && task.receipt" class="receipt">
          提交凭证
          <code>{{ task.receipt }}</code>
          <el-button link type="primary" @click="copyReceipt(task.receipt)">复制</el-button>
        </div>
        <div v-if="task.phase === 'failed'" class="error">{{ task.error }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { useUploadStore, type UploadTask } from '@/stores/uploader'
import { formatSize } from '@/utils/format'

const store = useUploadStore()

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
      // OSS 上传完成 ≠ 已保存：这一阶段必须单独提示
      return task.message || '正在登记到网盘…'
    case 'done':
      return task.message || '已保存到网盘'
    case 'paused':
      return task.message || '已暂停'
    case 'failed':
      return task.error || '上传失败'
    default:
      return ''
  }
}

async function copyReceipt(receipt: string) {
  try {
    // navigator.clipboard 在非安全上下文不可用，降级到 execCommand
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(receipt)
    } else {
      const input = document.createElement('textarea')
      input.value = receipt
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    ElMessage.success('凭证已复制')
  } catch {
    ElMessage.warning('复制失败，请手动记录：' + receipt)
  }
}
</script>

<style scoped>
.upload-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 8px 12px;
  overflow: auto;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.empty {
  color: #909399;
  text-align: center;
  padding: 24px 0;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task {
  border-bottom: 1px solid #f0f0f0;
  padding-bottom: 8px;
}

.task-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-size,
.speed {
  color: #909399;
  font-size: 12px;
}

.task-status {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
  margin-top: 2px;
}

.receipt {
  margin-top: 4px;
  font-size: 12px;
  color: #67c23a;
}

.receipt code {
  background: #f0f9eb;
  padding: 1px 6px;
  border-radius: 3px;
  margin: 0 4px;
}

.error {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}
</style>
