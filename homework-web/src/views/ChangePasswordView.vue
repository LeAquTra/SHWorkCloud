<template>
  <div class="pwd-page">
    <div class="glow glow-a" />
    <div class="glow glow-b" />

    <div class="card sc-fade-up">
      <div class="brand">
        <span class="mark"><el-icon><Key /></el-icon></span>
        <div>
          <h2>修改密码</h2>
          <p class="sc-muted">改完请牢记，初始密码是统一的</p>
        </div>
      </div>

      <el-alert
        v-if="user.mustChangePassword"
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="首次登录必须修改初始密码"
        description="初始密码是统一的。如果不改，同学之间可以互相登录账号。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            size="large"
            show-password
            autocomplete="new-password"
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            size="large"
            show-password
            autocomplete="new-password"
            :prefix-icon="Key"
          />
          <!-- 强度条：把"8~32 位 + 字母数字"这条规则变成看得见的反馈 -->
          <div class="strength">
            <span v-for="n in 3" :key="n" class="bar" :class="n <= strength.level ? `on-${strength.level}` : ''" />
            <span class="strength-text" :class="`t-${strength.level}`">{{ strength.label }}</span>
          </div>
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            size="large"
            show-password
            autocomplete="new-password"
            :prefix-icon="CircleCheck"
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-button type="primary" size="large" class="submit" :loading="loading" @click="onSubmit">
          确认修改
        </el-button>
      </el-form>

      <p class="footnote">
        修改成功后其它设备上的登录会被踢下线，当前这台机器保持登录。
      </p>
    </div>

    <div class="corner">
      <ThemeToggle />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { CircleCheck, Key, Lock } from '@element-plus/icons-vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { authApi } from '@/api'
import { ApiError } from '@/api/http'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const strength = computed(() => {
  const value = form.newPassword
  if (!value) {
    return { level: 0, label: '' }
  }
  let score = 0
  if (value.length >= 8) score += 1
  if (/[A-Za-z]/.test(value) && /\d/.test(value)) score += 1
  if (value.length >= 12 || /[^A-Za-z0-9]/.test(value)) score += 1
  const level = Math.max(1, score) as 1 | 2 | 3
  return { level, label: ['', '较弱', '一般', '很强'][level] }
})

/** 与后端 PasswordValidator 的规则保持一致，避免提交后才报错 */
const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度需在 8~32 位之间', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!value) {
          callback()
          return
        }
        if (!/[A-Za-z]/.test(value) || !/\d/.test(value)) {
          callback(new Error('密码必须同时包含字母和数字'))
          return
        }
        if (/\s/.test(value)) {
          callback(new Error('密码不能包含空格'))
          return
        }
        if (value.toLowerCase() === user.username.toLowerCase()) {
          callback(new Error('密码不能与学号相同'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function onSubmit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    await authApi.changePassword(form.oldPassword, form.newPassword, form.confirmPassword)
    user.markPasswordChanged()
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    ElMessage.success('密码修改成功')
    await router.replace('/')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '修改失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.pwd-page {
  position: relative;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  overflow: hidden;
}

.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}

.glow-a {
  width: 460px;
  height: 460px;
  top: -160px;
  left: -100px;
  background: var(--sc-aurora-1);
}

.glow-b {
  width: 400px;
  height: 400px;
  bottom: -180px;
  right: -90px;
  background: var(--sc-aurora-3);
}

.card {
  position: relative;
  z-index: 1;
  width: min(452px, 100%);
  padding: 38px 36px;
  border-radius: var(--sc-radius-xl);
  border: 1px solid var(--sc-border);
  background: var(--sc-glass-strong);
  backdrop-filter: blur(24px) saturate(150%);
  box-shadow: var(--sc-shadow-lg);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.mark {
  width: 40px;
  height: 40px;
  border-radius: 13px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 20px;
  background-image: var(--sc-gradient);
  box-shadow: var(--sc-shadow-brand);
}

.brand h2 {
  margin: 0;
  font-size: 20px;
  letter-spacing: -0.025em;
}

.brand p {
  margin: 2px 0 0;
  font-size: 12px;
}

.tip {
  margin-bottom: 18px;
}

.strength {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  margin-top: 8px;
}

.bar {
  height: 3px;
  flex: 1;
  border-radius: var(--sc-radius-full);
  background: var(--sc-hover);
  transition: background-color var(--sc-dur) var(--sc-ease);
}

.bar.on-1 {
  background: var(--sc-danger);
}

.bar.on-2 {
  background: var(--sc-warning);
}

.bar.on-3 {
  background: var(--sc-success);
}

.strength-text {
  font-size: 11px;
  min-width: 26px;
  text-align: right;
}

.strength-text.t-1 {
  color: var(--sc-danger);
}

.strength-text.t-2 {
  color: var(--sc-warning);
}

.strength-text.t-3 {
  color: var(--sc-success);
}

.submit {
  width: 100%;
  height: 44px;
}

.footnote {
  margin: 18px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sc-text-3);
  text-align: center;
}

.corner {
  position: fixed;
  top: 18px;
  right: 20px;
  z-index: 5;
}
</style>
