<template>
  <div class="page">
    <el-card shadow="never">
      <div class="filters">
        <el-input
          v-model="query.keyword"
          placeholder="学号 / 姓名 / 邮箱"
          clearable
          class="w180"
          @keyup.enter="reload(1)"
        />
        <el-select v-model="query.className" placeholder="班级" clearable class="w160">
          <el-option v-for="name in classes" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select v-model="query.role" placeholder="角色" clearable class="w130">
          <el-option label="学生" :value="0" />
          <el-option label="教师" :value="2" />
          <el-option label="管理员" :value="1" />
          <el-option label="超级管理员" :value="9" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable class="w120">
          <el-option label="正常" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-button type="primary" @click="reload(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <div class="spacer" />
        <el-button
          :disabled="selected.length === 0"
          @click="batchResetPassword"
        >
          批量重置密码
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        height="100%"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" :selectable="(row: AdminUserVO) => row.role !== 9" />
        <el-table-column prop="username" label="登录名" width="130" />
        <el-table-column prop="studentNo" label="学号" width="130" />
        <el-table-column prop="realName" label="姓名" width="110" />
        <el-table-column prop="className" label="班级" width="130" />
        <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="row.role === 9 ? 'danger' : row.role > 0 ? 'warning' : 'info'" size="small">
              {{ ROLE_LABELS[row.role] || row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="容量" width="150">
          <template #default="{ row }">
            {{ formatSize(row.used) }} / {{ formatSize(row.quota) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后登录" width="170">
          <template #default="{ row }">{{ formatTime(row.lastLoginTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="editProfile(row)">编辑资料</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              :disabled="row.role === 9"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="primary" @click="editQuota(row)">配额</el-button>
            <el-button link type="primary" @click="resetPassword(row)">重置密码</el-button>
            <el-button link type="primary" @click="recalc(row)">重算容量</el-button>
            <el-button
              v-if="user.isSuperAdmin"
              link
              type="primary"
              :disabled="row.role === 9"
              @click="editRole(row)"
            >
              角色
            </el-button>
            <el-button
              v-if="user.isSuperAdmin"
              link
              type="danger"
              :disabled="row.role === 9"
              @click="removeUser(row)"
            >
              删除
            </el-button>
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

    <!-- 调整配额 -->
    <el-dialog v-model="quotaVisible" title="调整容量配额" width="400px">
      <el-form label-width="90px">
        <el-form-item label="用户">
          {{ current?.realName || current?.username }}
        </el-form-item>
        <el-form-item label="配额(GB)">
          <el-input-number v-model="quotaGb" :min="0" :max="1024" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改角色 -->
    <el-dialog v-model="roleVisible" title="修改角色" width="400px">
      <el-radio-group v-model="newRole">
        <el-radio :value="0">学生</el-radio>
        <el-radio :value="2">教师（机房管理员）</el-radio>
        <el-radio :value="1">管理员</el-radio>
      </el-radio-group>
      <p class="muted">超级管理员不可通过此入口设置。</p>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑资料（部分更新：只提交填了内容的字段） -->
    <el-dialog v-model="profileVisible" title="编辑用户资料" width="460px">
      <el-form label-width="90px">
        <el-form-item label="登录名">
          <span class="muted">{{ current?.username }}（不可修改）</span>
        </el-form-item>
        <el-form-item label="真实姓名">
          <el-input v-model="profileForm.realName" maxlength="50" placeholder="留空表示清空" />
        </el-form-item>
        <el-form-item label="学号">
          <el-input
            v-model="profileForm.studentNo"
            maxlength="32"
            placeholder="数字/字母/下划线/连字符，3~32 位"
          />
        </el-form-item>
        <el-form-item label="班级">
          <el-input v-model="profileForm.className" maxlength="100" placeholder="如 高一(3)班" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="profileForm.email" maxlength="100" placeholder="留空表示清空" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="profileForm.nickname" maxlength="50" placeholder="不能为空" />
        </el-form-item>
      </el-form>
      <p class="muted">
        只有内容发生变化的字段会被提交；学号与邮箱会做唯一性校验。
        登录名、角色、状态、配额、密码、头像请用各自的操作按钮。
      </p>
      <template #footer>
        <el-button @click="profileVisible = false">取消</el-button>
        <el-button type="primary" :loading="profileSaving" @click="saveProfile">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import { ROLE_LABELS, type AdminUpdateProfileReq, type AdminUserVO } from '@/types/api'
import { formatSize, formatTime } from '@/utils/format'

const user = useUserStore()

const query = reactive({
  keyword: '',
  className: '' as string | undefined,
  role: undefined as number | undefined,
  status: undefined as number | undefined,
  page: 1,
  size: 20,
})

const rows = ref<AdminUserVO[]>([])
const total = ref(0)
const loading = ref(false)
const selected = ref<AdminUserVO[]>([])
const classes = ref<string[]>([])

const current = ref<AdminUserVO | null>(null)
const quotaVisible = ref(false)
const quotaGb = ref(1)
const roleVisible = ref(false)
const newRole = ref(0)

// —— 编辑资料 ——
const profileVisible = ref(false)
const profileSaving = ref(false)
/** 编辑中的表单快照（字符串，null 归一成空串以便输入框显示与比较） */
const profileForm = reactive({
  realName: '',
  studentNo: '',
  className: '',
  email: '',
  nickname: '',
})

async function reload(toPage?: number) {
  if (toPage) {
    query.page = toPage
  }
  loading.value = true
  try {
    const result = await adminApi.users({
      keyword: query.keyword || undefined,
      className: query.className || undefined,
      role: query.role,
      status: query.status,
      page: query.page,
      size: query.size,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.keyword = ''
  query.className = undefined
  query.role = undefined
  query.status = undefined
  void reload(1)
}

function onSelectionChange(value: AdminUserVO[]) {
  selected.value = value
}

async function toggleStatus(row: AdminUserVO) {
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      next === 0
        ? `禁用「${row.realName || row.username}」后将立即踢下线，且无法再次登录。`
        : `确定启用「${row.realName || row.username}」吗？`,
      next === 0 ? '禁用账号' : '启用账号',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await adminApi.changeStatus(row.id, next)
    ElMessage.success('操作成功')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

function editQuota(row: AdminUserVO) {
  current.value = row
  quotaGb.value = Math.max(0, Math.round((row.quota / 1024 / 1024 / 1024) * 10) / 10)
  quotaVisible.value = true
}

async function saveQuota() {
  if (!current.value) {
    return
  }
  try {
    await adminApi.updateQuota(current.value.id, Math.round(quotaGb.value * 1024 * 1024 * 1024))
    quotaVisible.value = false
    ElMessage.success('配额已更新')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  }
}

// ---------------------------------------------------------------- 编辑资料

function editProfile(row: AdminUserVO) {
  current.value = row
  // null 归一成空串，输入框才能正常显示与比较
  profileForm.realName = row.realName ?? ''
  profileForm.studentNo = row.studentNo ?? ''
  profileForm.className = row.className ?? ''
  profileForm.email = row.email ?? ''
  profileForm.nickname = row.nickname ?? ''
  profileVisible.value = true
}

async function saveProfile() {
  const row = current.value
  if (!row) {
    return
  }
  // ⚠️ 关键：只把"真的改了"的字段放进请求体。
  // 后端是部分更新语义 —— 不传的键不动，而传空串表示清空。
  // 如果把整个表单都提交上去，没动过的字段也会被当成"改成当前值"，虽然结果相同，
  // 但一旦某次把空串误当成"值"提交，就会把学生的邮箱/学号清掉。
  const body: AdminUpdateProfileReq = {}
  const put = (key: keyof AdminUpdateProfileReq, next: string, original: string | null) => {
    const value = next.trim()
    if (value !== (original ?? '')) {
      body[key] = value
    }
  }
  put('realName', profileForm.realName, row.realName)
  put('studentNo', profileForm.studentNo, row.studentNo)
  put('className', profileForm.className, row.className)
  put('email', profileForm.email, row.email)
  put('nickname', profileForm.nickname, row.nickname)

  if (Object.keys(body).length === 0) {
    profileVisible.value = false
    ElMessage.info('没有检测到改动')
    return
  }

  profileSaving.value = true
  try {
    await adminApi.updateProfile(row.id, body)
    profileVisible.value = false
    ElMessage.success('资料已更新')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    profileSaving.value = false
  }
}

async function resetPassword(row: AdminUserVO) {
  try {
    await ElMessageBox.confirm(
      `将重置「${row.realName || row.username}」的密码，并强制其下次登录后修改。`,
      '重置密码',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const result = await adminApi.resetPassword(row.id)
    // 初始密码只在这里出现一次，必须让操作者看到并记录
    await ElMessageBox.alert(
      `账号：${result.username}\n初始密码：${result.initialPassword}\n\n该密码仅显示这一次，请立即告知学生。`,
      '重置成功',
      { confirmButtonText: '我已记录' },
    )
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重置失败')
  }
}

async function batchResetPassword() {
  const ids = selected.value.map((item) => item.id)
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将重置选中的 ${ids.length} 个账号密码（各自生成随机初始密码）。`,
      '批量重置密码',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const count = await adminApi.resetPasswordBatch(ids)
    ElMessage.success(`已重置 ${count} 个账号，请逐个查看新密码`)
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '批量重置失败')
  }
}

async function recalc(row: AdminUserVO) {
  try {
    const diff = await adminApi.recalcStorage(row.id)
    ElMessage.success(diff === 0 ? '容量一致，无需修正' : `已修正，差值 ${formatSize(diff)}`)
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重算失败')
  }
}

function editRole(row: AdminUserVO) {
  current.value = row
  newRole.value = row.role
  roleVisible.value = true
}

async function saveRole() {
  if (!current.value) {
    return
  }
  try {
    await adminApi.changeRole(current.value.id, newRole.value)
    roleVisible.value = false
    ElMessage.success('角色已更新（该用户需重新登录生效）')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  }
}

async function removeUser(row: AdminUserVO) {
  try {
    await ElMessageBox.confirm(
      `将删除账号「${row.realName || row.username}」（逻辑删除 + 踢下线 + 封禁）。\n默认保留其网盘文件。`,
      '删除账号',
      { type: 'error', confirmButtonText: '删除' },
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteUser(row.id, false)
    ElMessage.success('账号已删除')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(async () => {
  await reload(1)
  try {
    classes.value = await adminApi.classes()
  } catch {
    classes.value = []
  }
})
</script>

<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.w180 {
  width: 180px;
}

.w160 {
  width: 160px;
}

.w130 {
  width: 130px;
}

.w120 {
  width: 120px;
}

.spacer {
  flex: 1;
}

.table-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.table-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pager {
  justify-content: flex-end;
  margin-top: 8px;
}

.muted {
  color: var(--sc-text-3);
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
