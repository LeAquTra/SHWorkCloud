import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { ADMIN_ROLES, STAFF_ROLES, SUPER_ADMIN_ROLES } from '@/types/api'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    // 首登强制改密是一个"必须专心做完"的动作，所以不进主壳，单独整屏呈现
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/ChangePasswordView.vue'),
    meta: { title: '修改密码' },
  },
  {
    path: '/',
    component: () => import('@/layouts/UserLayout.vue'),
    children: [
      {
        path: '',
        name: 'files',
        component: () => import('@/views/FileManagerView.vue'),
        meta: { title: '我的网盘' },
      },
      {
        // 回收站与文件列表是同一个视图的两种模式，用独立路由是为了让顶栏导航能高亮
        path: 'recycle',
        name: 'recycle',
        component: () => import('@/views/FileManagerView.vue'),
        props: { initialMode: 'recycle' },
        meta: { title: '回收站' },
      },
      {
        path: 'album',
        name: 'album',
        component: () => import('@/views/AlbumView.vue'),
        meta: { title: '我的相册' },
      },
      {
        // 好友 + 聊天合成一个页面：两者是同一件事的两半
        // （左侧好友列表要显示未读，右侧聊天要显示对方名片），拆成两个路由
        // 反而会出现"从聊天点回好友列表要跳路由、状态要重新拉"的割裂感
        path: 'friends',
        name: 'friends',
        component: () => import('@/views/FriendsView.vue'),
        meta: { title: '好友' },
      },
      {
        /**
         * 社区广场。
         *
         * 任何已登录用户都能看与发；**发出来的内容默认只有自己和管理员可见**，
         * 审核通过后才进广场 —— 这一点由后端保证（没有"直接发布"的接口），
         * 前端不给任何"直接发布"的入口。
         */
        path: 'community',
        name: 'community',
        component: () => import('@/views/CommunityView.vue'),
        meta: { title: '社区' },
      },
      {
        /**
         * 别人的主页（自己看自己请走 /profile，那里有完整资料与可编辑项）。
         *
         * 用 `/user/1002` 这种"用户 ID 直接进路径"的形状，而不是
         * `/user?id=1002`：这个地址是要被分享/被点开的（社区里点作者头像、
         * 聊天窗点对方名字都跳这里），路径形式更直观，也能直接刷新。
         */
        path: 'user/:id',
        name: 'user-home',
        component: () => import('@/views/UserHomeView.vue'),
        props: true,
        meta: { title: '用户主页' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人信息' },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { roles: STAFF_ROLES },
    children: [
      { path: '', redirect: '/admin/users' },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManageView.vue'),
        meta: { roles: ADMIN_ROLES, title: '用户管理' },
      },
      {
        path: 'import',
        name: 'admin-import',
        component: () => import('@/views/admin/StudentImportView.vue'),
        meta: { roles: STAFF_ROLES, title: '名单导入' },
      },
      {
        path: 'captchas',
        name: 'admin-captchas',
        component: () => import('@/views/admin/CaptchaManageView.vue'),
        meta: { roles: ADMIN_ROLES, title: '验证码题库' },
      },
      {
        path: 'announcements',
        name: 'admin-announcements',
        component: () => import('@/views/admin/AnnouncementManageView.vue'),
        // 公告会影响全站每一个人（紧急公告还会强制弹窗打断操作），只给超管
        meta: { roles: SUPER_ADMIN_ROLES, title: '公告管理' },
      },
      {
        path: 'posts',
        name: 'admin-posts',
        component: () => import('@/views/admin/PostReviewView.vue'),
        // 社区审核：**教师及以上**（STAFF_ROLES）。
        // 需求原文是"管理员以上审核"，这里把教师也算进来 —— 教师就是机房管理员，
        // 课堂上需要能处理学生发的内容。后端 @SaCheckRole 的取值集合与此一致。
        meta: { roles: STAFF_ROLES, title: '社区审核' },
      },
      {
        path: 'ops',
        name: 'admin-ops',
        component: () => import('@/views/admin/OpsView.vue'),
        meta: { roles: SUPER_ADMIN_ROLES, title: '运维' },
      },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ErrorView.vue'),
    props: { code: 403 },
    meta: { public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/ErrorView.vue'),
    props: { code: 404 },
    meta: { public: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const user = useUserStore()
  const isPublic = to.meta.public === true

  if (!user.isLoggedIn) {
    user.restoreFromStorage()
  }

  if (!user.isLoggedIn && !isPublic) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!user.isLoggedIn) {
    return true
  }

  // 首登强制改密：除改密页外一律拦回
  if (user.mustChangePassword && to.path !== '/change-password') {
    return { path: '/change-password' }
  }

  // 角色门槛（前端只做体验控制，真正的鉴权在后端）
  const roles = to.meta.roles as number[] | undefined
  if (roles && !roles.includes(user.role)) {
    return { path: '/403' }
  }

  return true
})

/**
 * 懒加载 chunk 拉取失败的自愈。
 *
 * <p>典型报错（浏览器控制台）：
 * <pre>
 *   TypeError: Failed to fetch dynamically imported module:
 *   https://站点/assets/StudentImportView-XXXX.js
 * </pre>
 *
 * <p>原因不是代码坏了，而是<b>发版后浏览器还留着旧的主包</b>：
 * 路由页面是动态 import 的，文件名带内容 hash；重新构建后 hash 变了，
 * 旧主包里写死的那些 chunk 名字在服务器上已经不存在 → 404。
 * 现象就是"点某个菜单没反应"（而且只有<b>之前没打开过</b>的页面会这样，
 * 打开过的 chunk 还在浏览器缓存里，所以看起来像是"部分菜单坏了"）。
 *
 * <p>处理：自动整页刷新一次，让浏览器重新拿新的 index.html 与新 chunk。
 * 用 sessionStorage 做一次性标记，避免资源真的缺失时无限刷新。
 */
const CHUNK_RELOAD_FLAG = 'sc_chunk_reloaded'

function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '')
  return (
    message.includes('Failed to fetch dynamically imported module') ||
    message.includes('Importing a module script failed') ||
    message.includes('error loading dynamically imported module')
  )
}

router.onError((error: unknown) => {
  if (!isChunkLoadError(error)) {
    return
  }
  if (sessionStorage.getItem(CHUNK_RELOAD_FLAG)) {
    // 已经自动刷过一次还是失败：说明服务器上确实缺文件，别再循环刷新刷屏
    ElMessage.error('页面资源加载失败，请手动刷新（Ctrl+F5）或联系管理员')
    return
  }
  sessionStorage.setItem(CHUNK_RELOAD_FLAG, '1')
  window.location.reload()
})

router.afterEach((to) => {
  // 导航成功说明本次用到的 chunk 都在，清掉标记 —— 下次发版还能自动恢复
  sessionStorage.removeItem(CHUNK_RELOAD_FLAG)

  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - 作业云盘` : '作业云盘'
})

export default router
