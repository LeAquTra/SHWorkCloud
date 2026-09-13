<template>
  <div class="error-view">
    <div class="glow" />
    <div class="content sc-fade-up">
      <span class="code sc-gradient-text">{{ code }}</span>
      <h2>{{ title }}</h2>
      <p class="sc-muted">{{ subTitle }}</p>
      <div class="actions">
        <el-button type="primary" size="large" @click="goHome">返回首页</el-button>
        <el-button size="large" v-if="code === 403" @click="goLogin">换个账号登录</el-button>
      </div>
    </div>
    <div class="corner">
      <ThemeToggle />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import ThemeToggle from '@/components/ThemeToggle.vue'

const props = defineProps<{ code?: number }>()

const isForbidden = computed(() => props.code === 403)
const title = computed(() => (isForbidden.value ? '没有访问权限' : '页面不存在'))
const subTitle = computed(() =>
  isForbidden.value
    ? '你的账号没有访问该页面的权限。如果这是老师要求的功能，请联系管理员。'
    : '页面地址可能已经变更，请从首页重新进入。',
)

function goHome() {
  location.assign('/')
}

function goLogin() {
  sessionStorage.clear()
  location.assign('/login')
}
</script>

<style scoped>
.error-view {
  position: relative;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.glow {
  position: absolute;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  filter: blur(100px);
  background: var(--sc-aurora-1);
  pointer-events: none;
}

.content {
  position: relative;
  text-align: center;
  padding: 24px;
}

.code {
  display: block;
  font-size: 88px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.05em;
}

h2 {
  margin: 14px 0 6px;
  font-size: 21px;
}

p {
  margin: 0 0 26px;
  font-size: 13.5px;
}

.actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}

.corner {
  position: fixed;
  top: 18px;
  right: 20px;
  z-index: 5;
}
</style>
