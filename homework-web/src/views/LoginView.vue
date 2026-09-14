<template>
  <div class="login-page">
    <div class="glow glow-a" />
    <div class="glow glow-b" />

    <div class="login-shell sc-fade-up">
      <!-- 左：产品说明。窄屏会整块隐藏，只留表单 -->
      <aside class="showcase">
        <div class="brand">
          <span class="mark"><BrandMark /></span>
          <div>
            <strong>作业云盘</strong>
            <small>Homework Cloud</small>
          </div>
        </div>

        <h1>
          把作业稳稳地<br />
          <span class="sc-gradient-text">存到云端</span>
        </h1>
        <p class="lede">
          机房电脑关机就清空。上传到云盘后，换台机器、回家用手机，都能接着看、接着交。
        </p>

        <ul class="features">
          <li v-for="feature in features" :key="feature.title">
            <span class="feat-icon"><el-icon><component :is="feature.icon" /></el-icon></span>
            <div>
              <strong>{{ feature.title }}</strong>
              <span>{{ feature.desc }}</span>
            </div>
          </li>
        </ul>
      </aside>

      <!-- 右：登录表单 -->
      <section class="form-card">
        <header class="form-head">
          <h2>欢迎回来</h2>
          <p class="sc-muted">用学号登录，首次登录会要求修改初始密码</p>
        </header>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item label="学号 / 用户名" prop="login">
            <el-input
              v-model="form.login"
              size="large"
              placeholder="请输入学号"
              autocomplete="off"
              :prefix-icon="User"
              @keyup.enter="onSubmit"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              placeholder="请输入密码"
              show-password
              autocomplete="new-password"
              :prefix-icon="Lock"
              @keyup.enter="onSubmit"
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="submit"
            :loading="loading"
            @click="onSubmit"
          >
            登 录
          </el-button>
        </el-form>

        <div class="form-foot">
          <span class="sc-muted">忘记密码或没有账号？</span>
          <el-button v-if="registerEnabled" link type="primary" @click="registerVisible = true">
            立即注册
          </el-button>
          <span v-else class="sc-muted">请找任课老师重置</span>
        </div>

        <div class="shared-tip">
          <el-icon><WarningFilled /></el-icon>
          <span>这是机房共用电脑：下课前请点右上角「退出」，否则下一位同学会进入你的网盘。</span>
        </div>
      </section>
    </div>

    <div class="corner">
      <ThemeToggle />
    </div>

    <RegisterDialog v-model="registerVisible" :config="registerConfig" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  Lock,
  Refresh,
  UploadFilled,
  User,
  VideoCamera,
  WarningFilled,
} from '@element-plus/icons-vue'
import BrandMark from '@/components/BrandMark.vue'
import RegisterDialog from '@/components/RegisterDialog.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { authApi } from '@/api'
import { ApiError, CODE } from '@/api/http'
import { useUserStore } from '@/stores/user'
import type { RegisterConfigVO } from '@/types/api'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ login: '', password: '' })

const registerVisible = ref(false)
const registerConfig = ref<RegisterConfigVO | null>(null)
const registerEnabled = computed(() => registerConfig.value?.registerEnabled === true)

const features = [
  {
    icon: UploadFilled,
    title: '大文件断点续传',
    desc: '分片直传 OSS，传一半断了可以接着传',
  },
  {
    icon: VideoCamera,
    title: '在线阅览',
    desc: '图片、PDF、视频、Office 正文直接在浏览器打开',
  },
  {
    icon: Refresh,
    title: '回收站兜底',
    desc: '误删先放回收站，随时可以还原',
  },
]

const rules: FormRules = {
  login: [{ required: true, message: '请输入学号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
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
    const vo = await authApi.login(form.login.trim(), form.password)
    user.setSession(vo)
    // 登录后不留密码在内存里
    form.password = ''
    formRef.value.resetFields()

    if (vo.mustChangePassword) {
      ElMessage.warning('首次登录请先修改初始密码')
      await router.replace('/change-password')
      return
    }
    const redirect = (route.query.redirect as string) || '/'
    await router.replace(redirect)
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === CODE.ACCOUNT_LOCKED) {
        ElMessage.error(error.message)
      } else if (error.code === CODE.ACCOUNT_DISABLED) {
        ElMessage.error('账号已被禁用，请联系老师')
      } else {
        ElMessage.error(error.message || '登录失败')
      }
    } else {
      ElMessage.error('登录失败，请检查网络后重试')
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    // 公开接口：先问清楚要不要显示注册入口
    registerConfig.value = await authApi.registerConfig()
  } catch {
    registerConfig.value = null
  }
})
</script>

<style scoped>
.login-page {
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
  width: 520px;
  height: 520px;
  top: -180px;
  left: -120px;
  background: var(--sc-aurora-1);
}

.glow-b {
  width: 460px;
  height: 460px;
  bottom: -200px;
  right: -100px;
  background: var(--sc-aurora-3);
}

.login-shell {
  position: relative;
  z-index: 1;
  width: min(1000px, 100%);
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 0;
  border-radius: var(--sc-radius-xl);
  border: 1px solid var(--sc-border);
  background: var(--sc-glass);
  backdrop-filter: blur(24px) saturate(150%);
  box-shadow: var(--sc-shadow-lg);
  overflow: hidden;
}

/* ---------------- 左侧展示 ---------------- */

.showcase {
  padding: 46px 44px;
  background-image: var(--sc-gradient-veil);
  border-right: 1px solid var(--sc-border);
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 34px;
}

.brand .mark {
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
}

.brand strong {
  display: block;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.brand small {
  font-size: 10px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--sc-text-3);
}

.showcase h1 {
  margin: 0 0 14px;
  font-size: 34px;
  line-height: 1.24;
  font-weight: 700;
  letter-spacing: -0.03em;
}

.lede {
  margin: 0 0 30px;
  color: var(--sc-text-2);
  line-height: 1.75;
  font-size: 13.5px;
}

.features {
  list-style: none;
  margin: auto 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.features li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.feat-icon {
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--sc-brand);
  background: var(--sc-brand-soft);
  font-size: 16px;
}

.features strong {
  display: block;
  font-size: 13.5px;
  font-weight: 620;
}

.features span {
  font-size: 12.5px;
  color: var(--sc-text-3);
}

/* ---------------- 右侧表单 ---------------- */

.form-card {
  padding: 46px 44px;
  background: var(--sc-glass-strong);
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-head h2 {
  margin: 0 0 4px;
  font-size: 23px;
  letter-spacing: -0.025em;
}

.form-head p {
  margin: 0 0 24px;
  font-size: 12.5px;
}

.submit {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  font-size: 15px;
  letter-spacing: 0.06em;
}

.form-foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  margin-top: 16px;
  font-size: 12.5px;
}

.shared-tip {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-top: 20px;
  padding: 11px 14px;
  border-radius: var(--sc-radius);
  font-size: 12px;
  line-height: 1.6;
  color: var(--sc-warning);
  background: color-mix(in srgb, var(--sc-warning) 10%, var(--sc-surface));
  border: 1px solid color-mix(in srgb, var(--sc-warning) 26%, transparent);
}

.corner {
  position: fixed;
  top: 18px;
  right: 20px;
  z-index: 5;
}

@media (max-width: 920px) {
  .login-shell {
    grid-template-columns: 1fr;
    width: min(460px, 100%);
  }

  .showcase {
    display: none;
  }

  .form-card {
    padding: 36px 28px;
  }
}
</style>
