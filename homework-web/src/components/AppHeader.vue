<template>
  <header class="app-header">
    <router-link to="/" class="brand">
      <span class="mark">
        <BrandMark />
      </span>
      <span class="brand-text">
        <strong>作业云盘</strong>
        <small>Homework Cloud</small>
      </span>
    </router-link>

    <nav class="nav sc-no-scrollbar">
      <router-link
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.to) }"
      >
        <!--
          好友入口的未读红点：把 el-badge 包在图标外层而不是整个导航项上，
          这样红点贴着图标，长文案（"我的网盘"）不会把红点推到奇怪的位置。
        -->
        <el-badge
          v-if="item.to === '/friends' && friendStore.hasUnread"
          :value="friendStore.badge"
          class="nav-badge"
        >
          <el-icon><component :is="item.icon" /></el-icon>
        </el-badge>
        <el-icon v-else><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </nav>

    <div class="spacer" />

    <el-tooltip
      v-if="user.profile"
      placement="bottom"
      :content="`已用 ${formatSize(used)} / 共 ${formatSize(quota)}；回收站另占 ${formatSize(recycleUsed)}`"
    >
      <div class="quota" :class="{ warn: usedPercent >= 90 }">
        <div class="quota-text sc-tabular">
          <strong>{{ formatSize(used) }}</strong>
          <span class="sc-muted"> / {{ formatSize(quota) }}</span>
        </div>
        <div class="quota-track">
          <div class="quota-fill" :style="{ width: `${usedPercent}%` }" />
        </div>
      </div>
    </el-tooltip>

    <AnnouncementBell />

    <ThemeToggle />

    <el-dropdown trigger="click" placement="bottom-end" @command="onCommand">
      <button type="button" class="user-chip">
        <el-avatar :size="30" :src="avatarUrl" class="avatar" @error="avatarFailed = true">
          {{ initial }}
        </el-avatar>
        <span class="user-meta">
          <strong>{{ user.displayName }}</strong>
          <small>{{ user.username }}</small>
        </span>
        <el-tag v-if="user.role > 0" size="small" effect="light" type="warning" class="role-tag">
          {{ roleLabel }}
        </el-tag>
        <el-icon class="caret"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="profile">
            <el-icon><User /></el-icon>
            <span>个人信息</span>
          </el-dropdown-item>
          <el-dropdown-item command="album">
            <el-icon><Picture /></el-icon>
            <span>我的相册</span>
          </el-dropdown-item>
          <el-dropdown-item command="friends">
            <el-icon><ChatDotRound /></el-icon>
            <span>我的好友</span>
          </el-dropdown-item>
          <el-dropdown-item command="home">
            <el-icon><HomeFilled /></el-icon>
            <span>我的主页</span>
          </el-dropdown-item>
          <el-dropdown-item v-if="user.canEnterAdmin" command="admin">
            <el-icon><Setting /></el-icon>
            <span>管理后台</span>
          </el-dropdown-item>
          <el-dropdown-item command="password" divided>
            <el-icon><Lock /></el-icon>
            <span>修改密码</span>
          </el-dropdown-item>
          <el-dropdown-item command="logout" divided>
            <el-icon><SwitchButton /></el-icon>
            <span>退出登录</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ChatDotRound,
  ChatLineSquare,
  Folder,
  HomeFilled,
  Lock,
  Picture,
  Setting,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import BrandMark from '@/components/BrandMark.vue'
import AnnouncementBell from '@/components/AnnouncementBell.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useFriendStore } from '@/stores/friend'
import { useUserStore } from '@/stores/user'
import { ROLE_LABELS } from '@/types/api'
import { formatSize } from '@/utils/format'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
/** 未读红点。轮询在 App.vue 里统一驱动，这里只读数值 */
const friendStore = useFriendStore()

const navItems = computed(() => {
  const items = [
    { to: '/', label: '我的网盘', icon: Folder },
    { to: '/album', label: '相册', icon: Picture },
    { to: '/community', label: '社区', icon: ChatLineSquare },
    { to: '/friends', label: '好友', icon: ChatDotRound },
    { to: '/profile', label: '个人信息', icon: User },
  ]
  if (user.canEnterAdmin) {
    items.push({ to: '/admin', label: '管理后台', icon: Setting })
  }
  return items
})

const roleLabel = computed(() => ROLE_LABELS[user.role] || '用户')

const used = computed(() => user.profile?.used ?? 0)
const quota = computed(() => user.profile?.quota ?? 0)
const recycleUsed = computed(() => user.profile?.recycleUsed ?? 0)
const usedPercent = computed(() =>
  quota.value > 0 ? Math.min(100, Math.round((used.value / quota.value) * 100)) : 0,
)

/** 头像签名地址失效（超过 1 小时）时退回文字头像，避免出现裂图 */
const avatarFailed = ref(false)
const avatarUrl = computed(() =>
  avatarFailed.value ? undefined : user.profile?.avatarUrl || undefined,
)
const initial = computed(() => (user.displayName || '?').trim().slice(0, 1).toUpperCase())

// 换过头像就重新给一次机会，否则清空再上传后仍会一直显示文字头像
watch(
  () => user.profile?.avatarVersion,
  () => {
    avatarFailed.value = false
  },
)

function isActive(to: string): boolean {
  if (to === '/') {
    return route.path === '/' || route.path === '/recycle'
  }
  // /friends 页里点某人会跳到 /user/:id，那时"好友"仍应保持高亮 ——
  // 否则用户会觉得"我明明是从好友进来的，怎么导航跑到别的地方了"
  if (to === '/friends') {
    return route.path.startsWith('/friends') || route.path.startsWith('/user/')
  }
  return route.path.startsWith(to)
}

async function onCommand(command: string) {
  switch (command) {
    case 'profile':
      await router.push('/profile')
      break
    case 'album':
      await router.push('/album')
      break
    case 'friends':
      await router.push('/friends')
      break
    case 'home':
      // 自己的主页 = 个人信息页（他人主页是 /user/:id，见 UserHomeView）
      await router.push('/profile')
      break
    case 'admin':
      await router.push('/admin')
      break
    case 'password':
      await router.push('/change-password')
      break
    case 'logout':
      await doLogout()
      break
  }
}

async function doLogout() {
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

onMounted(async () => {
  if (!user.profile && user.isLoggedIn) {
    try {
      await user.loadProfile()
    } catch {
      /* 401 已由 http 拦截器处理 */
    }
  }
})
</script>

<style scoped>
.app-header {
  height: var(--sc-header-h);
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 var(--sc-gutter);
  background: var(--sc-glass);
  backdrop-filter: blur(20px) saturate(150%);
  border-bottom: 1px solid var(--sc-border);
  position: relative;
  z-index: 20;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: inherit;
  text-decoration: none;
  flex: 0 0 auto;
}

.mark {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  background-image: var(--sc-gradient);
  box-shadow: var(--sc-shadow-brand);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.brand-text strong {
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.brand-text small {
  font-size: 10px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--sc-text-3);
}

.nav {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 14px;
  overflow-x: auto;
}

.nav-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 14px;
  border-radius: var(--sc-radius-full);
  color: var(--sc-text-2);
  font-size: 13.5px;
  font-weight: 500;
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

/* 未读红点：只包住图标，因此需要压掉 el-badge 默认的位移与字号 */
.nav-badge {
  display: inline-flex;
  line-height: 1;
}

.nav-badge :deep(.el-badge__content) {
  border: none;
  font-size: 10px;
  height: 15px;
  line-height: 15px;
  padding: 0 4px;
  transform: translateY(-2px) translateX(4px);
}

.spacer {
  flex: 1;
}

.quota {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 132px;
}

.quota-text {
  font-size: 11.5px;
  line-height: 1;
  color: var(--sc-text-2);
}

.quota-text strong {
  font-weight: 650;
  color: var(--sc-text);
}

.quota-track {
  height: 4px;
  border-radius: var(--sc-radius-full);
  background: var(--sc-hover);
  overflow: hidden;
}

.quota-fill {
  height: 100%;
  border-radius: var(--sc-radius-full);
  background-image: var(--sc-gradient);
  transition: width var(--sc-dur-slow) var(--sc-ease);
}

.quota.warn .quota-fill {
  background-image: none;
  background-color: var(--sc-danger);
}

.quota.warn .quota-text strong {
  color: var(--sc-danger);
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  height: 42px;
  padding: 0 12px 0 6px;
  border-radius: var(--sc-radius-full);
  border: 1px solid var(--sc-border);
  background: var(--sc-surface);
  color: inherit;
  cursor: pointer;
  transition: var(--sc-transition);
}

.user-chip:hover {
  border-color: var(--sc-border-2);
  box-shadow: var(--sc-shadow-sm);
}

.avatar {
  background-image: var(--sc-gradient);
  color: #fff;
  font-weight: 600;
}

.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.user-meta strong {
  font-size: 13px;
  font-weight: 620;
  max-width: 108px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-meta small {
  font-size: 11px;
  color: var(--sc-text-3);
  font-variant-numeric: tabular-nums;
}

.caret {
  color: var(--sc-text-3);
  font-size: 12px;
}

.role-tag {
  margin-left: 2px;
}

@media (max-width: 1180px) {
  .brand-text {
    display: none;
  }

  .quota {
    display: none;
  }
}

@media (max-width: 900px) {
  .user-meta,
  .role-tag {
    display: none;
  }

  .nav-item span {
    display: none;
  }

  .nav-item {
    padding: 0 11px;
  }
}
</style>
