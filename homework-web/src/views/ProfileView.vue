<template>
  <div class="profile sc-scroll-y">
    <header class="page-head">
      <div>
        <h2>个人信息</h2>
        <p class="sc-muted">昵称、签名、性别、生日可以自己改；学号姓名班级由老师通过名单导入维护</p>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </header>

    <el-skeleton v-if="loading && !profile" :rows="8" animated />

    <div v-else-if="profile" class="columns">
      <!-- ============ 左：身份 + 容量 ============ -->
      <div class="col-left">
        <section class="sc-surface card identity">
          <div class="avatar-wrap">
            <div class="avatar" :class="{ clickable: true }" @click="pickAvatar">
              <img v-if="avatarUrl" :src="avatarUrl" alt="头像" @error="avatarFailed = true" />
              <span v-else class="initial">{{ initial }}</span>
              <div class="avatar-hover">
                <el-icon><Camera /></el-icon>
                <span>更换</span>
              </div>
            </div>
            <input
              ref="avatarInput"
              type="file"
              accept="image/jpeg,image/png"
              hidden
              @change="onAvatarPicked"
            />
          </div>

          <h3>{{ profile.nickname || profile.realName || profile.username }}</h3>
          <p class="signature">{{ profile.signature || '还没有个性签名' }}</p>

          <div class="chips">
            <el-tag size="small" effect="light" :type="profile.role > 0 ? 'warning' : 'info'">
              {{ roleLabel }}
            </el-tag>
            <el-tag v-if="profile.className" size="small" effect="plain">
              {{ profile.className }}
            </el-tag>
            <el-tag size="small" effect="plain" type="success">学号 {{ profile.studentNo || profile.username }}</el-tag>
          </div>

          <div class="avatar-actions">
            <el-button size="small" @click="pickAvatar">上传头像</el-button>
            <el-button v-if="profile.avatarKey" size="small" type="danger" plain @click="clearAvatar">
              清除
            </el-button>
          </div>
          <p class="hint sc-muted">仅支持 JPG / PNG，不超过 5MB。换头像时服务端会自动删掉旧图。</p>
        </section>

        <!-- 容量环形图：纯 SVG，方便用品牌渐变描边 -->
        <section class="sc-surface card quota-card">
          <p class="sc-section-title"><span class="sc-dot" />存储容量</p>
          <div class="ring-wrap">
            <svg class="ring" viewBox="0 0 130 130" role="img" aria-label="容量使用率">
              <defs>
                <linearGradient id="quotaGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="var(--sc-brand)" />
                  <stop offset="55%" stop-color="var(--sc-brand-violet)" />
                  <stop offset="100%" stop-color="var(--sc-brand-cyan)" />
                </linearGradient>
              </defs>
              <circle class="ring-track" cx="65" cy="65" r="55" />
              <circle
                class="ring-bar"
                :class="{ danger: usedPercent >= 90 }"
                cx="65"
                cy="65"
                r="55"
                :stroke-dasharray="circumference"
                :stroke-dashoffset="circumference * (1 - usedPercent / 100)"
              />
            </svg>
            <div class="ring-center">
              <strong class="sc-tabular">{{ usedPercent }}%</strong>
              <small class="sc-muted">已使用</small>
            </div>
          </div>

          <ul class="quota-list">
            <li>
              <span class="sc-muted">已使用</span>
              <strong class="sc-tabular">{{ formatSize(profile.used) }}</strong>
            </li>
            <li>
              <span class="sc-muted">总容量</span>
              <strong class="sc-tabular">{{ formatSize(profile.quota) }}</strong>
            </li>
            <li>
              <span class="sc-muted">剩余可用</span>
              <strong class="sc-tabular">{{ formatSize(profile.free) }}</strong>
            </li>
            <li class="warn">
              <span>回收站占用</span>
              <strong class="sc-tabular">{{ formatSize(profile.recycleUsed) }}</strong>
            </li>
          </ul>

          <el-button v-if="profile.recycleUsed > 0" class="quota-cta" @click="router.push('/recycle')">
            <el-icon><Delete /></el-icon>
            <span>去清空回收站释放空间</span>
          </el-button>
        </section>

        <section class="sc-surface card">
          <p class="sc-section-title"><span class="sc-dot" />安全</p>
          <div class="security-row">
            <div>
              <strong>登录密码</strong>
              <p class="sc-muted">定期更换密码，公用电脑上尤其重要</p>
            </div>
            <el-button @click="router.push('/change-password')">修改</el-button>
          </div>
          <div class="security-row">
            <div>
              <strong>空闲自动退出</strong>
              <p class="sc-muted">{{ profile.idleLogoutMinutes }} 分钟无操作后自动退出登录</p>
            </div>
            <el-tag size="small" type="info" effect="plain">服务端策略</el-tag>
          </div>
        </section>
      </div>

      <!-- ============ 右：可编辑资料 + 学籍信息 ============ -->
      <div class="col-right">
        <section class="sc-surface card">
          <p class="sc-section-title"><span class="sc-dot" />个性属性</p>

          <el-form :model="form" label-position="top" @submit.prevent>
            <el-form-item label="昵称">
              <el-input v-model="form.nickname" maxlength="50" show-word-limit placeholder="最多 50 个字" />
            </el-form-item>

            <el-form-item label="个性签名">
              <el-input
                v-model="form.signature"
                type="textarea"
                :rows="3"
                maxlength="255"
                show-word-limit
                resize="none"
                placeholder="写一句给自己的话"
              />
              <el-button
                v-if="form.signature"
                link
                type="danger"
                class="clear-sig"
                @click="form.signature = ''"
              >
                清空签名
              </el-button>
            </el-form-item>

            <div class="two-col">
              <el-form-item label="性别">
                <el-radio-group v-model="form.gender">
                  <el-radio-button :value="0">不设置</el-radio-button>
                  <el-radio-button :value="1">男</el-radio-button>
                  <el-radio-button :value="2">女</el-radio-button>
                </el-radio-group>
              </el-form-item>

              <el-form-item label="生日">
                <el-date-picker
                  v-model="form.birthday"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择日期"
                  :disabled-date="isFuture"
                  class="date-picker"
                />
              </el-form-item>
            </div>

            <el-alert
              class="partial-tip"
              type="info"
              :closable="false"
              show-icon
              title="只提交改动的字段"
              description="接口按「请求体里有没有这个 key」判定，没改的字段不会被动，头像也不在这个接口里改。"
            />

            <div class="form-actions">
              <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">
                保存修改
              </el-button>
              <el-button :disabled="!dirty" @click="reset">撤销</el-button>
            </div>
          </el-form>
        </section>

        <section class="sc-surface card">
          <p class="sc-section-title"><span class="sc-dot" />学籍信息（只读）</p>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="登录名">{{ profile.username }}</el-descriptions-item>
            <el-descriptions-item label="学号">{{ profile.studentNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{ profile.realName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="班级">{{ profile.className || '-' }}</el-descriptions-item>
            <el-descriptions-item label="邮箱">{{ profile.email || '-' }}</el-descriptions-item>
            <el-descriptions-item label="用户 ID">{{ profile.userId }}</el-descriptions-item>
          </el-descriptions>
          <p class="hint sc-muted">
            学号、姓名、班级属于学籍数据，不能自助修改 —— 允许自己改学号会让账号体系失去意义。如有错误请联系老师。
          </p>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Camera, Delete, Refresh } from '@element-plus/icons-vue'
import { userApi } from '@/api'
import { ApiError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import { ROLE_LABELS, type ProfileUpdateVO } from '@/types/api'
import { formatSize } from '@/utils/format'

const router = useRouter()
const user = useUserStore()

const loading = ref(false)
const saving = ref(false)
const avatarInput = ref<HTMLInputElement>()
const avatarFailed = ref(false)

const profile = computed(() => user.profile)
const roleLabel = computed(() => (profile.value ? ROLE_LABELS[profile.value.role] || '用户' : ''))

const initial = computed(() => {
  const name = profile.value?.nickname || profile.value?.realName || profile.value?.username || '?'
  return name.trim().slice(0, 1).toUpperCase()
})

const avatarUrl = computed(() =>
  avatarFailed.value ? undefined : profile.value?.avatarUrl || undefined,
)

const form = reactive({
  nickname: '',
  signature: '',
  gender: 0,
  birthday: '',
})

const usedPercent = computed(() => {
  const data = profile.value
  if (!data || !data.quota) {
    return 0
  }
  return Math.min(100, Math.round((data.used / data.quota) * 100))
})

/** r=55 的周长，用来算环形进度 */
const circumference = 2 * Math.PI * 55

/** 只收集真正改动过的键 —— 接口语义就是"不传的键不动" */
const dirty = computed(() => Object.keys(buildPatch()).length > 0)

function buildPatch(): ProfileUpdateVO {
  const data = profile.value
  if (!data) {
    return {}
  }
  const patch: ProfileUpdateVO = {}

  const nickname = form.nickname.trim()
  if (nickname && nickname !== (data.nickname ?? '')) {
    patch.nickname = nickname
  }

  const signature = form.signature.trim()
  if (signature !== (data.signature ?? '')) {
    // 空串表示清空，按接口约定传 null
    patch.signature = signature === '' ? null : signature
  }

  if (form.gender !== (data.gender ?? 0)) {
    patch.gender = form.gender
  }

  const birthday = form.birthday || ''
  if (birthday !== (data.birthday ?? '')) {
    patch.birthday = birthday === '' ? null : birthday
  }

  return patch
}

function reset() {
  const data = profile.value
  if (!data) {
    return
  }
  form.nickname = data.nickname || ''
  form.signature = data.signature || ''
  form.gender = data.gender ?? 0
  form.birthday = data.birthday || ''
}

function isFuture(date: Date): boolean {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  return date.getTime() > today.getTime() || date.getFullYear() < 1900
}

async function load() {
  loading.value = true
  try {
    await user.loadProfile()
    reset()
    avatarFailed.value = false
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载个人信息失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  const patch = buildPatch()
  if (!Object.keys(patch).length) {
    return
  }
  saving.value = true
  try {
    const updated = await userApi.updateProfile(patch)
    user.profile = updated
    reset()
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ------------------------------------------------ 头像

function pickAvatar() {
  avatarInput.value?.click()
}

async function onAvatarPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  // 前端先挡一道给即时反馈；服务端会按 magic bytes 独立校验
  if (!/^image\/(jpeg|png)$/.test(file.type)) {
    ElMessage.error('只支持 JPG / PNG')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('头像不能超过 5MB')
    return
  }

  loading.value = true
  try {
    const updated = await userApi.uploadAvatar(file)
    user.profile = updated
    // avatarVersion 变了，之前的加载失败记录要清掉
    avatarFailed.value = false
    ElMessage.success('头像已更新')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '头像上传失败')
  } finally {
    loading.value = false
  }
}

async function clearAvatar() {
  try {
    await ElMessageBox.confirm('将删除当前头像（对象存储里的文件也会一并删除）。', '清除头像', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    const updated = await userApi.clearAvatar()
    user.profile = updated
    avatarFailed.value = false
    ElMessage.success('已清除头像')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '清除失败')
  }
}

onMounted(() => void load())
</script>

<style scoped>
.profile {
  flex: 1;
  min-height: 0;
  padding: 22px var(--sc-gutter) 36px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.page-head h2 {
  margin: 0 0 3px;
  font-size: 21px;
  letter-spacing: -0.025em;
}

.page-head p {
  margin: 0;
  font-size: 12.5px;
}

.columns {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.col-left,
.col-right {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.card {
  padding: 20px;
}

/* ---------------- 身份卡 ---------------- */

.identity {
  text-align: center;
}

.avatar-wrap {
  display: flex;
  justify-content: center;
  margin-bottom: 14px;
}

.avatar {
  position: relative;
  width: 96px;
  height: 96px;
  border-radius: var(--sc-radius-xl);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background-image: var(--sc-gradient);
  color: #fff;
  font-size: 36px;
  font-weight: 700;
  box-shadow: var(--sc-shadow-brand);
  cursor: pointer;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-hover {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 12px;
  background: rgba(8, 11, 20, 0.62);
  color: #fff;
  opacity: 0;
  transition: opacity var(--sc-dur) var(--sc-ease);
}

.avatar:hover .avatar-hover {
  opacity: 1;
}

.identity h3 {
  margin: 0 0 4px;
  font-size: 17px;
  letter-spacing: -0.02em;
}

.signature {
  margin: 0 0 12px;
  font-size: 12.5px;
  color: var(--sc-text-2);
  line-height: 1.6;
  word-break: break-word;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
  margin-bottom: 14px;
}

.avatar-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.hint {
  font-size: 11.5px;
  line-height: 1.6;
  margin: 10px 0 0;
}

/* ---------------- 容量 ---------------- */

.ring-wrap {
  position: relative;
  width: 168px;
  margin: 6px auto 14px;
}

.ring {
  width: 100%;
  display: block;
  transform: rotate(-90deg);
}

.ring-track,
.ring-bar {
  fill: none;
  stroke-width: 11;
  stroke-linecap: round;
}

.ring-track {
  stroke: var(--sc-hover);
}

.ring-bar {
  stroke: url(#quotaGrad);
  transition: stroke-dashoffset var(--sc-dur-slow) var(--sc-ease);
}

.ring-bar.danger {
  stroke: var(--sc-danger);
}

.ring-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.ring-center strong {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.1;
}

.ring-center small {
  font-size: 11.5px;
}

.quota-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.quota-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}

.quota-list strong {
  font-weight: 620;
}

.quota-list .warn span,
.quota-list .warn strong {
  color: var(--sc-warning);
}

.quota-cta {
  width: 100%;
  margin-top: 14px;
}

/* ---------------- 安全 / 学籍 ---------------- */

.security-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
}

.security-row + .security-row {
  border-top: 1px solid var(--sc-border);
}

.security-row strong {
  font-size: 13.5px;
  font-weight: 600;
}

.security-row p {
  margin: 2px 0 0;
  font-size: 12px;
}

/* ---------------- 表单 ---------------- */

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.date-picker {
  width: 100%;
}

.clear-sig {
  margin-top: 4px;
  margin-left: 0;
}

.partial-tip {
  margin-bottom: 16px;
}

.form-actions {
  display: flex;
  gap: 8px;
}

:deep(.el-descriptions__label) {
  width: 96px;
  color: var(--sc-text-3);
}

@media (max-width: 1080px) {
  .columns {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
