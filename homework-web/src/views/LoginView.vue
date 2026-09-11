<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="logo">📚</div>
        <h1>作业云盘</h1>
        <p class="subtitle">学生机房文件保存 · 用学号登录</p>
      </div>

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

      <el-alert
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="公用电脑请注意"
        description="这是机房共用电脑。下课前请点右上角「退出」，否则下一位同学会进入你的网盘。"
      />

      <p class="footnote">
        忘记密码或没有账号？请找任课老师重置密码（教师后台可直接重置）。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { ApiError, CODE } from '@/api/http'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ login: '', password: '' })

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
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #eef4ff 0%, #f7f9fc 60%, #ffffff 100%);
}

.login-card {
  width: 400px;
  padding: 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(31, 71, 136, 0.12);
}

.brand {
  text-align: center;
  margin-bottom: 24px;
}

.logo {
  font-size: 40px;
}

.brand h1 {
  margin: 8px 0 4px;
  font-size: 22px;
}

.subtitle {
  margin: 0;
  color: #909399;
  font-size: 13px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.tip {
  margin-top: 16px;
}

.footnote {
  margin: 16px 0 0;
  font-size: 12px;
  color: #909399;
  text-align: center;
  line-height: 1.6;
}
</style>
