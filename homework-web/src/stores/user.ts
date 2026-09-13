import { defineStore } from 'pinia'
import { authApi, userApi } from '@/api'
import { clearToken, getToken, setToken } from '@/api/http'
import {
  ADMIN_ROLES,
  ROLE_SUPER_ADMIN,
  STAFF_ROLES,
  type UserProfileVO,
} from '@/types/api'

const USER_KEY = 'sc_user'

interface UserState {
  token: string
  userId: number
  username: string
  realName: string
  role: number
  mustChangePassword: boolean
  profile: UserProfileVO | null
  idleLogoutMinutes: number
  checkoutWarnMinutes: number
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    token: getToken(),
    userId: 0,
    username: '',
    realName: '',
    role: -1,
    mustChangePassword: false,
    profile: null,
    idleLogoutMinutes: 20,
    checkoutWarnMinutes: 5,
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    /** 能进后台（教师及以上）。角色编号不是有序等级，只能按集合判断 */
    canEnterAdmin: (state) => STAFF_ROLES.includes(state.role),
    isAdmin: (state) => ADMIN_ROLES.includes(state.role),
    isSuperAdmin: (state) => state.role === ROLE_SUPER_ADMIN,
    displayName: (state) => state.realName || state.username || '未登录',
  },

  actions: {
    /** 登录后写入会话。Token 存 sessionStorage（共用电脑，见 §3.4） */
    setSession(payload: {
      token: string
      userId: number
      username: string
      realName: string | null
      role: number
      mustChangePassword: boolean
    }) {
      this.token = payload.token
      this.userId = payload.userId
      this.username = payload.username
      this.realName = payload.realName || payload.username
      this.role = payload.role
      this.mustChangePassword = payload.mustChangePassword
      setToken(payload.token)
      sessionStorage.setItem(
        USER_KEY,
        JSON.stringify({
          userId: payload.userId,
          username: payload.username,
          realName: this.realName,
          role: payload.role,
          mustChangePassword: payload.mustChangePassword,
        }),
      )
    },

    /** 刷新页面后从 sessionStorage 恢复基础身份，再拉一次 profile 校正 */
    restoreFromStorage(): boolean {
      const raw = sessionStorage.getItem(USER_KEY)
      if (!raw) {
        return false
      }
      try {
        const data = JSON.parse(raw)
        this.userId = data.userId ?? 0
        this.username = data.username ?? ''
        this.realName = data.realName ?? ''
        this.role = data.role ?? -1
        this.mustChangePassword = !!data.mustChangePassword
        this.token = getToken()
        return this.token !== ''
      } catch {
        return false
      }
    },

    async loadProfile() {
      const profile = await userApi.profile()
      this.profile = profile
      this.userId = profile.userId
      this.username = profile.username
      this.realName = profile.realName || profile.username
      this.role = profile.role
      this.idleLogoutMinutes = profile.idleLogoutMinutes || 20
      this.checkoutWarnMinutes = profile.checkoutWarnMinutes || 5
    },

    markPasswordChanged() {
      this.mustChangePassword = false
      const raw = sessionStorage.getItem(USER_KEY)
      if (raw) {
        try {
          const data = JSON.parse(raw)
          data.mustChangePassword = false
          sessionStorage.setItem(USER_KEY, JSON.stringify(data))
        } catch {
          /* 忽略损坏数据 */
        }
      }
    },

    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 退出接口失败也要清本地，否则共用电脑会残留登录态
      }
      this.resetAll()
    },

    /** 彻底清理：退出、被踢、401 都走这里 */
    resetAll() {
      this.token = ''
      this.userId = 0
      this.username = ''
      this.realName = ''
      this.role = -1
      this.mustChangePassword = false
      this.profile = null
      clearToken()
      sessionStorage.clear()
      // 只删本应用的键：
      //  - sc_ckpt:* 是上传断点，换人上机不该看到上一位的续传任务；
      //  - sc_theme 是这台机器的显示偏好，不是敏感信息，留着重启后更顺手
      //    （整块 localStorage.clear() 会把它一起清掉）。
      Object.keys(localStorage)
        .filter((key) => key.startsWith('sc_ckpt') || key.startsWith('sc_user'))
        .forEach((key) => localStorage.removeItem(key))
    },
  },
})
