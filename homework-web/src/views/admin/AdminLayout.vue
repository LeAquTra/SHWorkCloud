<template>
  <div class="admin">
    <AppHeader />
    <AnnouncementCenter />

    <div class="body">
      <aside class="side">
        <p class="sc-section-title side-title"><span class="sc-dot" />管理后台</p>

        <!--
          ⚠️ 这里刻意**不用** <el-menu router>。
          Element Plus 的 el-menu 在 router 模式下，内部要先拿到
          `appContext.config.globalProperties.$router`，并在
          `if (isNil(index) || isNil(indexPath)) return;` 这一行通过后才跳转；
          任一条件不满足就是**静默 return** —— 不跳转、不报错、连 @select 都不触发。
          现象正好是"导航点了没反应"。
          改用 router-link：与顶栏（UserLayout / AppHeader）用的是同一种机制，
          那条路是确定可用的，也不需要依赖组件库内部实现。
        -->
        <nav class="nav">
          <router-link
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: isActive(item.to) }"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </router-link>
        </nav>

        <div class="nav-foot">
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
import { computed, onMounted, type Component } from 'vue'
import { useRoute } from 'vue-router'
import { Bell, ChatLineSquare, InfoFilled, Picture, Tools, UploadFilled, User } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import AnnouncementCenter from '@/components/AnnouncementCenter.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const user = useUserStore()

interface NavItem {
  to: string
  label: string
  icon: Component
}

/**
 * 菜单项按角色显隐。
 * 用 computed 而不是模板里写 v-if：刷新页面时 role 可能还没恢复，
 * profile 拉回来之后这里的列表会自动补全，不需要手动刷新。
 */
const navItems = computed<NavItem[]>(() => {
  const items: NavItem[] = []
  if (user.isAdmin) {
    items.push({ to: '/admin/users', label: '用户管理', icon: User })
  }
  items.push({ to: '/admin/import', label: '学生名单导入', icon: UploadFilled })
  // 社区审核对所有后台角色开放（教师及以上）：教师是机房管理员，
  // 课堂上需要能处理学生发的内容。与路由 meta.roles / 后端 @SaCheckRole 一致。
  items.push({ to: '/admin/posts', label: '社区审核', icon: ChatLineSquare })
  if (user.isAdmin) {
    items.push({ to: '/admin/captchas', label: '验证码题库', icon: Picture })
  }
  if (user.isSuperAdmin) {
    items.push({ to: '/admin/announcements', label: '公告管理', icon: Bell })
    items.push({ to: '/admin/ops', label: '运维', icon: Tools })
  }
  return items
})

/** 前缀匹配：进入子页面时父项也保持高亮 */
function isActive(to: string): boolean {
  return route.path === to || route.path.startsWith(`${to}/`)
}

onMounted(async () => {
  // 刷新后 role 可能只来自 sessionStorage；这里拉一次 profile 校正，
  // 顺便把容量等顶栏要用的数据补齐（AppHeader 也会用到）
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

.side {
  width: 224px;
  flex: 0 0 224px;
  padding: 16px 0;
  border-right: 1px solid var(--sc-border);
  background: var(--sc-glass);
  backdrop-filter: blur(14px);
  display: flex;
  flex-direction: column;
}

.side-title {
  padding: 0 22px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 2px 12px;
  padding: 0 12px;
  height: 44px;
  border-radius: var(--sc-radius-sm);
  color: var(--sc-text-2);
  font-size: 14px;
  white-space: nowrap;
  transition: var(--sc-transition);
}

.nav-item:hover {
  background: var(--sc-hover);
  color: var(--sc-text);
}

.nav-item.active {
  background: var(--sc-brand-soft);
  color: var(--sc-brand);
  font-weight: 600;
}

.nav-foot {
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

/*
 * 窄屏（平板竖屏 / 分屏窗口 / 老师笔记本 1366×768 缩放后）：
 * 侧栏改成顶部横向标签条，避免固定 224px 侧栏把内容挤到只剩一条缝。
 */
@media (max-width: 1024px) {
  .body {
    flex-direction: column;
  }

  .side {
    width: 100%;
    flex: 0 0 auto;
    padding: 10px 0 6px;
    border-right: none;
    border-bottom: 1px solid var(--sc-border);
  }

  .side-title {
    display: none;
  }

  .nav {
    flex-direction: row;
    gap: 4px;
    padding: 0 12px 2px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
  }

  .nav::-webkit-scrollbar {
    display: none;
  }

  .nav-item {
    flex: 0 0 auto;
    margin: 0;
    height: 38px;
    font-size: 13.5px;
  }

  .nav-foot {
    display: none;
  }

  .content {
    padding: 14px var(--sc-gutter);
  }
}
</style>
