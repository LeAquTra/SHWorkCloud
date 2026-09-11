import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ROLE_ADMIN, ROLE_SUPER_ADMIN, ROLE_TEACHER } from '@/types/api'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/ChangePasswordView.vue'),
    meta: { title: '修改密码' },
  },
  {
    path: '/',
    name: 'files',
    component: () => import('@/views/FileManagerView.vue'),
    meta: { title: '我的网盘' },
  },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { minRole: ROLE_TEACHER },
    children: [
      { path: '', redirect: '/admin/users' },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManageView.vue'),
        meta: { minRole: ROLE_ADMIN, title: '用户管理' },
      },
      {
        path: 'import',
        name: 'admin-import',
        component: () => import('@/views/admin/StudentImportView.vue'),
        meta: { minRole: ROLE_TEACHER, title: '名单导入' },
      },
      {
        path: 'captchas',
        name: 'admin-captchas',
        component: () => import('@/views/admin/CaptchaManageView.vue'),
        meta: { minRole: ROLE_ADMIN, title: '验证码题库' },
      },
      {
        path: 'ops',
        name: 'admin-ops',
        component: () => import('@/views/admin/OpsView.vue'),
        meta: { minRole: ROLE_SUPER_ADMIN, title: '运维' },
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
  const minRole = to.meta.minRole as number | undefined
  if (minRole !== undefined && user.role < minRole) {
    return { path: '/403' }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - 作业云盘` : '作业云盘'
})

export default router
