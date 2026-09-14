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

      <!--
        人机验证不在这里内嵌，而是点「发送邮箱验证码」时弹出独立的验证码窗口
        （HumanCheckDialog，挂在 App.vue 上）。原因：这个弹窗要在登录、注册、上传
        三处复用，而且同一时刻只能存在一个；内嵌一份等于同时维护三份实现。
      -->
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
        <el-input
          v-model="form.username"
          size="large"
          maxlength="20"
          placeholder="只能数字或字母；留空则取邮箱 @ 前面的部分"
        />
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
import { authApi } from '@/api'
import { ApiError, CODE } from '@/api/http'
import { useHumanCheckStore } from '@/stores/humanCheck'
import type { RegisterConfigVO } from '@/types/api'

/**
 * 自助注册弹窗。
 *
 * <p>流程：邮箱 →（需要时先过**人机验证弹窗**）→ 邮箱验证码 → 设置密码 → 注册。
 * 是否要人机验证由 `GET /auth/human-check` 的 `register` 决定（后台题库为空时服务端会自动不要求）。
 *
 * <p>⚠️ captchaPassToken 在"发邮件码"时被服务端一次性消费，
 * 所以**注册提交里不再带它**，且每次重发邮件码都要重新验证一次
 * （见对接指南 §3.2）。
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
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
const humanCheck = useHumanCheckStore()

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
  // 登录名是可选字段：留空则服务端按邮箱 @ 前面的部分生成，所以空值要放行。
  // 规则与后端 AccountRules.USERNAME_PATTERN 保持一致：
  // ⚠️ 前端校验只是"提前告知"，真正的把关在后端（后端不合法会直接报错，不做静默改写）。
  username: [
    {
      validator: (_rule, value: string, callback) => {
        const name = (value ?? '').trim()
        if (!name) {
          callback()
          return
        }
        if (!/^[0-9A-Za-z]+$/.test(name)) {
          callback(new Error('登录名只能使用数字或大小写字母'))
          return
        }
        if (name.length > 20) {
          callback(new Error('登录名最多 20 个字符'))
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

  // 人机验证：先清掉本地缓存再取新凭证 —— 这个凭证是**一次性**的，
  // 上一次发送已经把它消费掉了，复用必然被服务端拒（CAPTCHA_PASS_INVALID）。
  // 注意这里**不能**用 force=true：题库为空 / 未开启时服务端并不要求，
  // 强弹一个空题库的弹窗会让用户既看不懂也过不去。
  const passToken = await issuePassToken()
  if (passToken === undefined) {
    return
  }

  sending.value = true
  try {
    await sendWithRetry(passToken)
    ElMessage.success('验证码已发送，请到邮箱查收')
    startCountdown()
    step.value = 2
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

/**
 * 取一个用于本次发送的验证码凭证。
 *
 * @returns 凭证（不需要人机验证时为 null）；`undefined` 表示用户取消了验证，调用方应中止
 */
async function issuePassToken(): Promise<string | null | undefined> {
  humanCheck.clearToken()
  const token = await humanCheck.ensure('register')
  if (!token && (await humanCheck.required('register'))) {
    ElMessage.info('需要完成人机验证才能发送验证码')
    return undefined
  }
  return token
}

/** 发送邮件码；服务端坚持要人机验证时强制弹一次窗再重试 */
async function sendWithRetry(passToken: string | null): Promise<void> {
  const email = form.email.trim()
  try {
    await authApi.sendEmailCode(email, passToken ?? undefined)
  } catch (error) {
    const needCaptcha = error instanceof ApiError
      && (error.code === CODE.CAPTCHA_REQUIRED || error.code === CODE.CAPTCHA_PASS_INVALID)
    if (!needCaptcha) {
      throw error
    }
    // 配置可能在页面停留期间变了（管理员刚上传了第一批题目）→ 强制弹一次再试
    humanCheck.clearToken()
    const retryToken = await humanCheck.ensure('register', true)
    if (!retryToken) {
      throw error
    }
    await authApi.sendEmailCode(email, retryToken)
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
    // 关掉弹窗就把未用掉的凭证清掉：它是一次性的，留着只会误导下一次发送
    humanCheck.clearToken()
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
