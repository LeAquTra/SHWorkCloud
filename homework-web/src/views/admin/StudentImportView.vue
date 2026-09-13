<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>学生名单批量导入</span>
          <el-button link type="primary" @click="downloadTemplate">
            <el-icon><Download /></el-icon>
            <span>下载 CSV 模板</span>
          </el-button>
        </div>
      </template>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="这是机房场景的主开户路径"
        description="导入后学生用「学号 + 初始密码」登录，首次登录会被强制修改密码。CSV 兼容 UTF-8 BOM 与 GBK（Excel 直接另存为 CSV 即可）。"
      />

      <el-form label-width="100px" class="form sc-form-grid">
        <el-form-item label="默认班级">
          <el-input
            v-model="defaultClass"
            placeholder="如 高一(3)班（CSV 中留空的班级用此值）"
            class="sc-field-full"
          />
        </el-form-item>
        <el-form-item label="CSV 文件" class="sc-span">
          <input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="onPicked" />
          <el-button @click="fileInput?.click()">选择文件</el-button>
          <span v-if="file" class="file-name">{{ file.name }}（{{ formatSize(file.size) }}）</span>
        </el-form-item>
        <el-form-item class="sc-span">
          <el-button type="primary" :loading="importing" :disabled="!file" @click="doImport">
            开始导入
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="关于重复学号"
        description="导入策略由后端 app.student-import.strategy 决定：skip 跳过已存在、update 只更新姓名/班级/配额、fail 则整批放弃。"
      />
    </el-card>

    <el-card v-if="result" shadow="never" class="result-card">
      <template #header>
        <span>导入结果</span>
      </template>

      <div class="summary">
        <el-statistic title="总行数" :value="result.total" />
        <el-statistic title="成功" :value="result.success" />
        <el-statistic title="跳过/更新" :value="result.skipped" />
        <el-statistic title="失败" :value="result.failed" />
      </div>

      <el-alert
        v-if="result.initialPassword"
        class="tip"
        type="success"
        :closable="false"
        show-icon
        :title="`本次统一初始密码：${result.initialPassword}`"
        description="请务必在课上告知学生，并提醒他们首次登录后修改。"
      />

      <el-table v-if="result.failures.length" :data="result.failures" max-height="320">
        <el-table-column prop="row" label="行号" width="80" />
        <el-table-column prop="studentNo" label="学号" width="150" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.type === 'FAIL' ? 'danger' : 'warning'" size="small">
              {{ row.type === 'FAIL' ? '失败' : '跳过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="240" />
      </el-table>
      <el-empty v-else description="没有失败或跳过的行" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import type { ImportResultVO } from '@/types/api'
import { formatSize } from '@/utils/format'

const fileInput = ref<HTMLInputElement>()
const file = ref<File | null>(null)
const defaultClass = ref('')
const importing = ref(false)
const result = ref<ImportResultVO | null>(null)

function onPicked(event: Event) {
  const input = event.target as HTMLInputElement
  file.value = input.files?.[0] || null
  result.value = null
  input.value = ''
}

async function downloadTemplate() {
  try {
    const blob = await adminApi.importTemplate()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'student-import-template.csv'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '下载模板失败')
  }
}

async function doImport() {
  if (!file.value) {
    return
  }
  importing.value = true
  try {
    result.value = await adminApi.importStudents(file.value, defaultClass.value || undefined)
    ElMessage.success(
      `导入完成：成功 ${result.value.success}，跳过/更新 ${result.value.skipped}，失败 ${result.value.failed}`,
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-header {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.tip {
  margin-bottom: 16px;
}

/* 表单宽度交给 .sc-form-grid 弹性决定，不再写死 620px */
.form {
  width: 100%;
}

.file-name {
  margin-left: 12px;
  color: var(--sc-text-2);
  font-size: 13px;
  word-break: break-all;
}

/* 窄屏换行显示，不再固定 48px 间距撑出横向滚动条 */
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 48px;
  margin-bottom: 16px;
}
</style>
