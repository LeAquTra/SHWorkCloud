<template>
  <div class="pwd-page">
    <div class="pwd-card">
      <h2>修改密码</h2>
      <el-alert
        v-if="user.mustChangePassword"
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="首次登录必须修改初始密码"
        description="初始密码是统一的，如果不改，同学之间可以互相登录账号。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
          <div class="hint">至少 8 位，且必须同时包含字母和数字；不能与学号相同。</div>
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="onSubmit">
          确认修改
        </el-button>
      </el-form>

      <p class="footnote">
        修改成功后其它设备上的登录会被踢下线，当前这台机器保持登录。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api'
import { ApiError } from '@/api/http'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

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
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sc-page-bg);
}

.pwd-card {
  width: 420px;
  padding: 28px 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(31, 71, 136, 0.1);
}

.pwd-card h2 {
  margin: 0 0 16px;
  font-size: 20px;
}

.tip {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.submit {
  width: 100%;
}

.footnote {
  margin: 16px 0 0;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>
