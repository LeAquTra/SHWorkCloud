<template>
  <div class="session-badge-wrap">
    <!-- 机房共用电脑：必须让当前登录者一眼看到"现在是谁" -->
    <span class="sc-session-badge">
      <el-icon><UserFilled /></el-icon>
      <span>{{ user.displayName }}</span>
      <span class="sep">|</span>
      <span class="student-no">{{ user.username }}</span>
      <el-tag v-if="user.role > 0" size="small" type="warning" effect="dark">
        {{ roleLabel }}
      </el-tag>
    </span>
    <el-button link type="danger" @click="onLogout">
      <el-icon><SwitchButton /></el-icon>
      <span>退出</span>
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { ROLE_LABELS } from '@/types/api'

const user = useUserStore()
const roleLabel = computed(() => ROLE_LABELS[user.role] || '用户')

async function onLogout() {
  try {
    await ElMessageBox.confirm(
      '退出后本机将不再保留登录状态。公用电脑请务必退出，否则下一位同学会进入你的网盘。',
      '确认退出登录',
      { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await user.logout()
  location.assign('/login')
}
</script>

<style scoped>
.session-badge-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sep {
  color: #c0c4cc;
}

.student-no {
  font-family: Consolas, Monaco, monospace;
}
</style>
