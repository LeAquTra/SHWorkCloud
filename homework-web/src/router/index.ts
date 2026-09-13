import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
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

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - 作业云盘` : '作业云盘'
})

export default router
