<template>
  <div class="admin">
    <AppHeader />

    <div class="body">
      <aside class="menu">
        <p class="sc-section-title"><span class="sc-dot" />管理后台</p>
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

        <div class="menu-foot">
          <el-icon><InfoFilled /></el-icon>
          <span>前端只做入口显隐，真正的权限校验在后端。</span>
        </div>
      </aside>

      <main class="content sc-scroll-y">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { InfoFilled, Picture, Tools, UploadFilled, User } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import { useUserStore } from '@/stores/user'

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
.admin {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.menu {
  width: 224px;
  flex: 0 0 224px;
  padding: 16px 0;
  border-right: 1px solid var(--sc-border);
  background: var(--sc-glass);
  backdrop-filter: blur(14px);
  display: flex;
  flex-direction: column;
}

.menu .sc-section-title {
  padding: 0 22px;
}

.menu-foot {
  margin: auto 16px 0;
  display: flex;
  gap: 7px;
  align-items: flex-start;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--sc-text-3);
}

.content {
  flex: 1;
  min-width: 0;
  padding: 20px var(--sc-gutter);
}
</style>
