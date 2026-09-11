<template>
  <div class="error-view">
    <el-result :icon="icon" :title="title" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ code?: number }>()

const icon = computed(() => (props.code === 403 ? 'warning' : 'error'))
const title = computed(() => (props.code === 403 ? '403 无权限' : '404 页面不存在'))
const subTitle = computed(() =>
  props.code === 403
    ? '你的账号没有访问该页面的权限。如果这是老师要求的功能，请联系管理员。'
    : '页面地址可能已经变更，请从首页重新进入。',
)

function goHome() {
  location.assign('/')
}
</script>

<style scoped>
.error-view {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
</style>
