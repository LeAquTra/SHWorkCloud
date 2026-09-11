<template>
  <div class="admin-layout">
    <header class="header">
      <div class="brand">
        <span class="logo">🛠</span>
        <span class="title">作业云盘 · 管理后台</span>
      </div>
      <div class="spacer" />
      <el-button link type="primary" @click="router.push('/')">
        <el-icon><Back /></el-icon>
        <span>返回我的网盘</span>
      </el-button>
      <SessionBadge />
    </header>

    <div class="body">
      <aside class="menu">
        <el-menu :default-active="activePath" router>
          <el-menu-item v-if="user.isAdmin" index="/admin/users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/import">
            <el-icon><UploadFilled /></el-icon>
            <span>学生名单导入</span>
          </el-menu-item>
          <el-menu-item v-if="user.isAdmin" index="/admin/captchas">
            <el-icon><Picture /></el-icon>
            <span>验证码题库</span>
          </el-menu-item>
          <el-menu-item v-if="user.isSuperAdmin" index="/admin/ops">
            <el-icon><Tools /></el-icon>
            <span>运维</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back, Picture, Tools, UploadFilled, User } from '@element-plus/icons-vue'
import SessionBadge from '@/components/SessionBadge.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const activePath = computed(() => route.path)

onMounted(async () => {
  if (!user.profile) {
    try {
      await user.loadProfile()
    } catch {
      /* 401 已由拦截器处理 */
    }
  }
})
</script>

<style scoped>
.admin-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--sc-page-bg);
}

.header {
  height: var(--sc-header-h);
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}

.brand {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 700;
}

.spacer {
  flex: 1;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.menu {
  width: 200px;
  flex: 0 0 200px;
  background: #fff;
  border-right: 1px solid #ebeef5;
}

.content {
  flex: 1;
  min-width: 0;
  padding: 16px;
  overflow: auto;
}
</style>
