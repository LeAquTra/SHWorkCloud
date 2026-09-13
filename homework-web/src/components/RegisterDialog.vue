<template>
  <el-dialog
    :model-value="modelValue"
    width="480px"
    :close-on-click-modal="false"
    class="register-dialog"
    @update:model-value="onVisibleChange"
  >
    <template #header>
      <div class="reg-head">
        <span class="mark"><el-icon><UserFilled /></el-icon></span>
        <div>
          <strong>注册新账号</strong>
          <p class="sc-muted">用 QQ 邮箱接收验证码，注册后即可用学号或邮箱前缀登录</p>
        </div>
      </div>
    </template>

    <!-- 进度指示：让"要几步才能注册完"这件事一开始就清清楚楚 -->
    <div class="steps">
      <div class="step" :class="{ done: step > 1, active: step === 1 }">
        <span class="index">1</span>
        <span>验证邮箱</span>
      </div>
      <span class="line" :class="{ done: step > 1 }" />
      <div class="step" :class="{ active: step === 2 }">
        <span class="index">2</span>
        <span>设置密码</span>
      </div>
    </div>

    <el-alert
      v-if="config && !config.mailEnabled"
      class="notice"
      type="warning"
      :closable="false"
      show-icon
      title="当前未开启邮件通道"
      description="验证码只会写到后端日志（开发模式）。请找管理员索取验证码。"
    />

    <!-- ---------------- 第一步：邮箱 + 图片验证码 ---------------- -->
    <el-form
      v-if="step === 1"
      ref="emailFormRef"
      :model="form"
      :rules="emailRules"
      label-position="top"
      @submit.prevent
    >
      <el-form-item label="QQ 邮箱" prop="email">
        <el-input
          v-model="form.email"
          size="large"
          placeholder="例如 10001@qq.com"
          :prefix-icon="Message"
          @keyup.enter="sendCode"
        />
        <div v-if="config?.mailHint" class="hint">{{ config.mailHint }}</div>
      </el-form-item>

      <ImageCaptcha v-if="config?.requireImageCaptcha" ref="captchaRef" @update:pass-token="onPassToken" />

      <el-button
        type="primary"
        size="large"
        class="submit"
        :loading="sending"
        @click="sendCode"
      >
        发送邮箱验证码
      </el-button>
    </el-form>

    <!-- ---------------- 第二步：验证码 + 密码 ---------------- -->
    <el-form
      v-else
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent
    >
      <el-form-item label="邮箱验证码" prop="emailCode">
        <div class="code-row">
          <el-input
            v-model="form.emailCode"
            size="large"
            maxlength="6"
            placeholder="6 位数字"
            @keyup.enter="submit"
          />
          <el-button size="large" :disabled="countdown > 0" :loading="sending" @click="sendCode">
            {{ countdown > 0 ? `${countdown}s 后重发` : '重新发送' }}
          </el-button>
        </div>
        <div class="hint">已发送到 {{ form.email }}，10 分钟内有效。</div>
      </el-form-item>

      <el-form-item label="设置密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          size="large"
          show-password
          autocomplete="new-password"
          placeholder="8~32 位，须同时包含字母和数字"
        />
      </el-form-item>

      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          size="large"
          show-password
          autocomplete="new-password"
          placeholder="再输入一次"
          @keyup.enter="submit"
        />
      </el-form-item>

      <el-form-item label="登录名（可选）" prop="username">
        <el-input v-model="form.username" size="large" placeholder="留空则默认取邮箱 @ 前面的部分" />
      </el-form-item>

      <el-button type="primary" size="large" class="submit" :loading="submitting" @click="submit">
        完成注册
      </el-button>
      <el-button link class="back" @click="step = 1">返回上一步</el-button>
    </el-form>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Message, UserFilled } from '@element-plus/icons-vue'
import ImageCaptcha from '@/components/ImageCaptcha.vue'
import { authApi } from '@/api'
import { ApiError, CODE } from '@/api/http'
import type { RegisterConfigVO } from '@/types/api'

/**
 * 自助注册弹窗。
 *
 * <p>流程由 `GET /auth/register-config` 决定：
 *   requireImageCaptcha=false（默认）→ 邮箱验证码 → 注册
 *   requireImageCaptcha=true        → 图片验证码 → 邮箱验证码 → 注册
 *
 * <p>⚠️ captchaPassToken 在"发邮件码"时被服务端一次性消费，
 * 所以**注册提交里不再带它**（对接指南 §3.2 专门强调过这一点）。
 */

const props = defineProps<{
  modelValue: boolean
  config: RegisterConfigVO | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  registered: []
}>()

const step = ref<1 | 2>(1)
const emailFormRef = ref<FormInstance>()
const formRef = ref<FormInstance>()
const captchaRef = ref<InstanceType<typeof ImageCaptcha>>()
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
const captchaPassToken = ref('')

let timer: number | undefined

const form = reactive({
  email: '',
  emailCode: '',
  password: '',
  confirmPassword: '',
  username: '',
})

/** 后端下发的是 Java 正则，个别语法 JS 不支持，解析失败就不做本地校验 */
const emailRegex = computed<RegExp | null>(() => {
  const pattern = props.config?.emailPattern
  if (!pattern) {
    return null
  }
  try {
    return new RegExp(pattern)
  } catch {
    return null
  }
})

const emailRules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const regex = emailRegex.value
        if (regex && value && !regex.test(value.trim())) {
          callback(new Error('邮箱格式不符合要求，请查看下方提示'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

/** 与后端 PasswordValidator 一致：8~32 位、同时含字母与数字、无空格 */
function passwordValidator(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) {
    callback()
    return
  }
  if (value.length < 8 || value.length > 32) {
    callback(new Error('密码长度需在 8~32 位之间'))
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
  callback()
}

const rules: FormRules = {
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { len: 6, message: '验证码是 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    { validator: passwordValidator, trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

function startCountdown() {
  countdown.value = 60
  window.clearInterval(timer)
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      window.clearInterval(timer)
      timer = undefined
    }
  }, 1000)
}

function onPassToken(token: string) {
  captchaPassToken.value = token
}

async function sendCode() {
  const formEl = step.value === 1 ? emailFormRef.value : null
  if (formEl) {
    const valid = await formEl.validate().catch(() => false)
    if (!valid) {
      return
    }
  }
  if (props.config?.requireImageCaptcha && !captchaPassToken.value) {
    // passToken 是一次性的：重发邮件码必须重新过一遍图片验证
    if (step.value === 2) {
      step.value = 1
      ElMessage.warning('重新发送需要先完成图片验证码')
    } else {
      ElMessage.warning('请先完成图片验证码')
    }
    return
  }

  sending.value = true
  try {
    await authApi.sendEmailCode(form.email.trim(), captchaPassToken.value || undefined)
    ElMessage.success('验证码已发送，请到邮箱查收')
    startCountdown()
    step.value = 2
    // passToken 已被一次性消费，重新回到第一步时必须换一张新题
    captchaPassToken.value = ''
    captchaRef.value?.refresh()
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === CODE.TOO_FREQUENT) {
        ElMessage.warning('发送过于频繁，请 60 秒后再试')
      } else if (error.code === CODE.QUOTA_LIMITED) {
        ElMessage.warning('该邮箱今日发送次数已达上限')
      } else {
        ElMessage.error(error.message)
      }
    } else {
      ElMessage.error('验证码发送失败，请稍后重试')
    }
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await authApi.register({
      email: form.email.trim(),
      emailCode: form.emailCode.trim(),
      password: form.password,
      username: form.username.trim() || undefined,
    })
    ElMessage.success('注册成功，请用新账号登录')
    emit('registered')
    emit('update:modelValue', false)
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === CODE.REGISTER_DISABLED) {
        ElMessage.error('管理员已关闭自助注册')
      } else if (error.code === CODE.STUDENT_EXISTS) {
        ElMessage.error('该学号/登录名已存在，请换一个或联系老师')
      } else {
        ElMessage.error(error.message)
      }
    } else {
      ElMessage.error('注册失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}

function onVisibleChange(visible: boolean) {
  if (!visible) {
    window.clearInterval(timer)
    countdown.value = 0
    step.value = 1
    captchaPassToken.value = ''
  }
  emit('update:modelValue', visible)
}

onBeforeUnmount(() => window.clearInterval(timer))
</script>

<style scoped>
.reg-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.reg-head strong {
  font-size: 16px;
  font-weight: 650;
}

.reg-head p {
  margin: 2px 0 0;
  font-size: 12px;
}

.mark {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 19px;
  background-image: var(--sc-gradient);
  box-shadow: var(--sc-shadow-brand);
  flex: 0 0 auto;
}

.steps {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}

.step {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--sc-text-3);
  font-weight: 500;
  transition: color var(--sc-dur) var(--sc-ease);
}

.step .index {
  width: 20px;
  height: 20px;
  border-radius: var(--sc-radius-full);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  border: 1px solid var(--sc-border-2);
  transition: var(--sc-transition);
}

.step.active {
  color: var(--sc-brand);
}

.step.active .index {
  background-image: var(--sc-gradient);
  border-color: transparent;
  color: #fff;
}

.step.done {
  color: var(--sc-success);
}

.step.done .index {
  background: var(--sc-success);
  border-color: transparent;
  color: #fff;
}

.line {
  flex: 1;
  height: 1px;
  background: var(--sc-border);
}

.line.done {
  background: var(--sc-success);
}

.notice {
  margin-bottom: 14px;
}

.hint {
  font-size: 12px;
  color: var(--sc-text-3);
  line-height: 1.5;
  margin-top: 4px;
}

.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.back {
  width: 100%;
  margin-top: 8px;
  margin-left: 0;
}
</style>
