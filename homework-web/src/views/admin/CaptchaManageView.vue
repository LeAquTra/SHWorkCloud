<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>验证码题库</span>
          <div>
            <el-tag :type="summary.enabled > 0 ? 'success' : 'danger'" class="mr8">
              启用 {{ summary.enabled }} / 共 {{ summary.total }}
            </el-tag>
            <el-button type="primary" @click="openUpload">上传题目</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="summary.enabled === 0"
        class="tip"
        type="error"
        :closable="false"
        show-icon
        title="题库为空，自助注册暂不可用"
        description="这不影响学生用学号登录（机房主路径）。若需要开放 QQ 邮箱自助注册，请先上传题目，并把后端 app.register.enabled 设为 true。"
      />

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="题型" width="110">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="answer" label="答案" width="130" />
        <el-table-column label="标注数据" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.dataJson || '-' }}</template>
        </el-table-column>
        <el-table-column label="尺寸" width="110">
          <template #default="{ row }">{{ row.width }}×{{ row.height }}</template>
        </el-table-column>
        <el-table-column prop="weight" label="权重" width="80" />
        <el-table-column prop="usedCount" label="出题数" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="() => reload()"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑题目' : '上传题目'" width="560px">
      <el-form label-width="90px">
        <el-form-item v-if="!editing" label="图片">
          <input ref="fileInput" type="file" accept="image/*" hidden @change="onPicked" />
          <el-button @click="fileInput?.click()">选择图片</el-button>
          <span v-if="file" class="file-name">{{ file.name }}</span>
        </el-form-item>
        <el-form-item label="题型">
          <el-radio-group v-model="form.type">
            <el-radio :value="1">字符输入</el-radio>
            <el-radio :value="2">单选</el-radio>
            <el-radio :value="3">点选</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标准答案">
          <el-input
            v-model="form.answer"
            :placeholder="form.type === 3 ? '点选点的 ID 顺序，如 3,1,2' : form.type === 2 ? '正确选项的 k，如 B' : '图中字符，如 K7P2'"
          />
        </el-form-item>
        <el-form-item v-if="form.type !== 1" label="标注数据">
          <el-input
            v-model="form.dataJson"
            type="textarea"
            :rows="4"
            :placeholder="dataPlaceholder"
          />
          <div class="hint">{{ dataHint }}</div>
        </el-form-item>
        <el-form-item label="图片尺寸">
          <el-input-number v-model="form.width" :min="0" placeholder="宽" />
          <span class="times">×</span>
          <el-input-number v-model="form.height" :min="0" placeholder="高" />
        </el-form-item>
        <el-form-item label="权重">
          <el-input-number v-model="form.weight" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="仅后台可见" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import type { CaptchaImageVO } from '@/types/api'

const rows = ref<CaptchaImageVO[]>([])
const total = ref(0)
const loading = ref(false)
const summary = reactive({ enabled: 0, total: 0 })

const query = reactive({ page: 1, size: 20 })

const dialogVisible = ref(false)
const editing = ref<CaptchaImageVO | null>(null)
const saving = ref(false)
const fileInput = ref<HTMLInputElement>()
const file = ref<File | null>(null)

const form = reactive({
  type: 1,
  answer: '',
  dataJson: '',
  width: 0,
  height: 0,
  weight: 100,
  remark: '',
})

const dataPlaceholder = computed(() =>
  form.type === 2
    ? '[{"k":"A","label":"汽车"},{"k":"B","label":"红绿灯"}]'
    : '[{"id":1,"x":120,"y":88,"label":"山"},{"id":2,"x":205,"y":140,"label":"水"}]',
)

const dataHint = computed(() =>
  form.type === 2
    ? '选项数组：k 为选项标识（答案填对应的 k），label 为展示文字。'
    : '标注点数组：id 为点编号，x/y 为原图像素坐标，label 为文字标签；答案填点击顺序的 id。',
)

function typeLabel(type: number) {
  return type === 1 ? '字符输入' : type === 2 ? '单选' : '点选'
}

async function reload() {
  loading.value = true
  try {
    const result = await adminApi.captchas({ page: query.page, size: query.size })
    rows.value = result.records
    total.value = result.total
    const s = await adminApi.captchaSummary()
    summary.enabled = s.enabled
    summary.total = s.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.type = 1
  form.answer = ''
  form.dataJson = ''
  form.width = 0
  form.height = 0
  form.weight = 100
  form.remark = ''
  file.value = null
}

function openUpload() {
  editing.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: CaptchaImageVO) {
  editing.value = row
  form.type = row.type
  form.answer = row.answer
  form.dataJson = row.dataJson || ''
  form.width = row.width
  form.height = row.height
  form.weight = row.weight
  form.remark = row.remark || ''
  file.value = null
  dialogVisible.value = true
}

function onPicked(event: Event) {
  const input = event.target as HTMLInputElement
  file.value = input.files?.[0] || null
  input.value = ''
}

async function save() {
  if (!form.answer.trim()) {
    ElMessage.warning('请填写标准答案')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await adminApi.updateCaptcha(editing.value.id, { ...form })
      ElMessage.success('已保存')
    } else {
      if (!file.value) {
        ElMessage.warning('请选择图片')
        saving.value = false
        return
      }
      await adminApi.uploadCaptcha(file.value, { ...form })
      ElMessage.success('已上传')
    }
    dialogVisible.value = false
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(row: CaptchaImageVO) {
  try {
    await adminApi.toggleCaptcha(row.id, row.status === 1 ? 0 : 1)
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function remove(row: CaptchaImageVO) {
  try {
    await ElMessageBox.confirm('删除题目会同时删除 OSS 上的图片，确定吗？', '删除题目', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteCaptcha(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(reload)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.mr8 {
  margin-right: 8px;
}

.tip {
  margin-bottom: 12px;
}

.pager {
  justify-content: flex-end;
  margin-top: 8px;
}

.file-name {
  margin-left: 12px;
  color: var(--sc-text-2);
}

.hint {
  font-size: 12px;
  color: var(--sc-text-3);
  line-height: 1.5;
}

.times {
  margin: 0 8px;
}
</style>
