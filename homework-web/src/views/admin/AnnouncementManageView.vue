<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>公告管理</span>
          <div class="head-actions">
            <el-button :loading="loading" @click="reload">
              <el-icon><Refresh /></el-icon>
              <span>刷新</span>
            </el-button>
            <el-button type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon>
              <span>新建公告</span>
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="公告按「注意力分级」打扰用户"
        description="普通 / 重要：顶部横幅，用户可关闭；紧急：强制弹窗，必须点「我已知晓」。新建后是草稿，需要再点「发布」才会对用户可见；已发布的公告必须先撤回才能修改（避免用户正在看的公告被静默改掉）。"
      />

      <div class="sc-toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="标题或正文关键字"
          clearable
          class="kw"
          @keyup.enter="() => search()"
        />
        <el-select v-model="query.status" placeholder="全部状态" clearable class="sel">
          <el-option label="草稿" :value="0" />
          <el-option label="已发布" :value="1" />
          <el-option label="已撤回" :value="2" />
        </el-select>
        <el-select v-model="query.level" placeholder="全部分级" clearable class="sel">
          <el-option label="普通" :value="1" />
          <el-option label="重要" :value="2" />
          <el-option label="紧急" :value="3" />
        </el-select>
        <el-button type="primary" @click="() => search()">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <strong>{{ row.title }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="分级" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="levelTag(row.level)">
              {{ levelLabel(row.level) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusTag(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前生效" width="106">
          <template #default="{ row }">
            <!-- 已发布但没生效（定时未到 / 已过期）必须让管理员看见，否则会出现"发了但用户说没看到" -->
            <el-tag v-if="row.status === 1" size="small" :type="row.effective ? 'success' : 'warning'">
              {{ row.effective ? '生效中' : '未生效' }}
            </el-tag>
            <span v-else class="sc-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="160">
          <template #default="{ row }">{{ formatTime(row.publishTime) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" width="160">
          <template #default="{ row }">{{ row.expireTime ? formatTime(row.expireTime) : '不过期' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="118" fixed="right">
          <template #default="{ row }">
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              @command="(cmd: string) => onRowCommand(cmd, row)"
            >
              <el-button link type="primary">
                操作
                <el-icon><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="view">
                    <el-icon><View /></el-icon>
                    <span>查看</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="edit" :disabled="row.status === 1">
                    <el-icon><EditPen /></el-icon>
                    <span>编辑</span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status !== 1" command="publish">
                    <el-icon><Promotion /></el-icon>
                    <span>发布</span>
                  </el-dropdown-item>
                  <el-dropdown-item v-else command="recall">
                    <el-icon><RefreshLeft /></el-icon>
                    <span>撤回</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="remove" divided :disabled="row.status === 1">
                    <el-icon><Delete /></el-icon>
                    <span>删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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

    <!-- ============ 新建 / 编辑 ============ -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑公告' : '新建公告'"
      width="min(680px, 94vw)"
    >
      <el-form label-width="90px" class="sc-form-grid">
        <el-form-item label="标题" class="sc-span">
          <el-input v-model="form.title" maxlength="120" show-word-limit placeholder="一句话说清是什么事" />
        </el-form-item>

        <el-form-item label="正文" class="sc-span">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            maxlength="5000"
            show-word-limit
            placeholder="纯文本，换行会原样展示（不需要也不支持 HTML）"
          />
        </el-form-item>

        <el-form-item label="注意力分级" class="sc-span">
          <el-radio-group v-model="form.level">
            <el-radio-button :value="1">普通（横幅）</el-radio-button>
            <el-radio-button :value="2">重要（警示横幅）</el-radio-button>
            <el-radio-button :value="3">紧急（强制弹窗）</el-radio-button>
          </el-radio-group>
          <p class="field-hint sc-muted">{{ levelHint }}</p>
        </el-form-item>

        <el-form-item label="过期时间" class="sc-span">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空表示不过期"
            class="dt"
          />
          <p class="field-hint sc-muted">
            到点自动不再展示，不需要人工撤回；最远一年后。留空则一直生效到手动撤回。
          </p>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editing ? '保存' : '保存为草稿' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 查看（只读） ============ -->
    <el-dialog v-model="viewVisible" :title="viewing?.title || '公告详情'" width="min(560px, 94vw)">
      <div v-if="viewing" class="detail">
        <div class="detail-meta">
          <el-tag size="small" effect="light" :type="levelTag(viewing.level)">
            {{ levelLabel(viewing.level) }}
          </el-tag>
          <el-tag size="small" effect="plain" :type="statusTag(viewing.status)">
            {{ statusLabel(viewing.status) }}
          </el-tag>
          <span class="sc-muted">发布 {{ formatTime(viewing.publishTime) }}</span>
          <span class="sc-muted">
            过期 {{ viewing.expireTime ? formatTime(viewing.expireTime) : '不过期' }}
          </span>
        </div>
        <p class="detail-body">{{ viewing.content }}</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Delete,
  EditPen,
  Plus,
  Promotion,
  Refresh,
  RefreshLeft,
  View,
} from '@element-plus/icons-vue'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import {
  ANNOUNCEMENT_LEVEL_LABELS,
  ANNOUNCEMENT_STATUS_LABELS,
  type AnnouncementManageVO,
} from '@/types/api'
import { formatTime } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const rows = ref<AnnouncementManageVO[]>([])
const total = ref(0)

const query = reactive({
  keyword: '',
  status: undefined as number | undefined,
  level: undefined as number | undefined,
  page: 1,
  size: 10,
})

const dialogVisible = ref(false)
/** null 表示新建 */
const editing = ref<AnnouncementManageVO | null>(null)

const viewVisible = ref(false)
const viewing = ref<AnnouncementManageVO | null>(null)

const form = reactive({
  title: '',
  content: '',
  level: 1,
  expireTime: '' as string,
})

const levelHint = computed(() => {
  switch (form.level) {
    case 3:
      return '紧急：用户一进入系统就被弹窗挡住，必须点「我已知晓」才能继续。请只用于真正需要立刻知道的事（如机房断电、数据回滚）。'
    case 2:
      return '重要：顶部橙色横幅，用户可关闭。适合"今天之内需要留意"的事。'
    default:
      return '普通：顶部横幅，用户可关闭。适合日常通知（如开放时间调整）。'
  }
})

function levelLabel(level: number): string {
  return ANNOUNCEMENT_LEVEL_LABELS[level] || '普通'
}

function levelTag(level: number): 'info' | 'warning' | 'danger' {
  if (level >= 3) {
    return 'danger'
  }
  return level === 2 ? 'warning' : 'info'
}

function statusLabel(status: number): string {
  return ANNOUNCEMENT_STATUS_LABELS[status] || '未知'
}

function statusTag(status: number): 'info' | 'success' | 'warning' {
  if (status === 1) {
    return 'success'
  }
  return status === 2 ? 'warning' : 'info'
}

async function reload() {
  loading.value = true
  try {
    const result = await adminApi.announcements({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status,
      level: query.level,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void reload()
}

function resetQuery() {
  query.keyword = ''
  query.status = undefined
  query.level = undefined
  search()
}

function openCreate() {
  editing.value = null
  form.title = ''
  form.content = ''
  form.level = 1
  form.expireTime = ''
  dialogVisible.value = true
}

function openEdit(row: AnnouncementManageVO) {
  editing.value = row
  form.title = row.title
  form.content = row.content
  form.level = row.level
  form.expireTime = row.expireTime || ''
  dialogVisible.value = true
}

function onRowCommand(command: string, row: AnnouncementManageVO) {
  switch (command) {
    case 'view':
      viewing.value = row
      viewVisible.value = true
      break
    case 'edit':
      openEdit(row)
      break
    case 'publish':
      void publish(row)
      break
    case 'recall':
      void recall(row)
      break
    case 'remove':
      void remove(row)
      break
  }
}

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请填写正文')
    return
  }
  const body = {
    title: form.title.trim(),
    content: form.content,
    level: form.level,
    // 空串要变成 null，否则后端会尝试把它解析成时间
    expireTime: form.expireTime || null,
  }
  saving.value = true
  try {
    if (editing.value) {
      await adminApi.updateAnnouncement(editing.value.id, body)
      ElMessage.success('已保存')
    } else {
      await adminApi.createAnnouncement(body)
      ElMessage.success('已保存为草稿，点「发布」后才对用户可见')
    }
    dialogVisible.value = false
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function publish(row: AnnouncementManageVO) {
  const urgent = row.level >= 3
  try {
    await ElMessageBox.confirm(
      urgent
        ? '这是「紧急」公告：发布后所有用户进入系统都会被强制弹窗确认。确认发布？'
        : '发布后所有登录用户都会看到这条公告。确认发布？',
      '发布公告',
      { type: urgent ? 'warning' : 'info', confirmButtonText: '发布', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await adminApi.publishAnnouncement(row.id)
    ElMessage.success('已发布')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '发布失败')
  }
}

async function recall(row: AnnouncementManageVO) {
  try {
    await ElMessageBox.confirm(
      '撤回后用户立刻看不到这条公告（数据保留，可再次发布）。确认撤回？',
      '撤回公告',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await adminApi.recallAnnouncement(row.id)
    ElMessage.success('已撤回')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '撤回失败')
  }
}

async function remove(row: AnnouncementManageVO) {
  try {
    await ElMessageBox.confirm(`删除后不可恢复：${row.title}`, '删除公告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await adminApi.deleteAnnouncement(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(() => void reload())
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.tip {
  margin-bottom: 14px;
}

.kw {
  width: 240px;
}

.sel {
  width: 140px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  width: 100%;
}

.dt {
  width: 100%;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 11.5px;
  margin-bottom: 12px;
}

.detail-body {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--sc-text);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 52vh;
  overflow-y: auto;
}
</style>
