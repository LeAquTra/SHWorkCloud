import { defineStore } from 'pinia'
import { announcementApi } from '@/api'
import { ANNOUNCEMENT_LEVEL_URGENT, type AnnouncementActiveVO } from '@/types/api'

/**
 * 已关闭的横幅公告 id。
 * 用 sessionStorage 而不是 localStorage：这套系统跑在**公用机房电脑**上，
 * 上一位同学关掉的公告，下一位登录时应该重新看到。
 */
const DISMISS_KEY = 'sc_announce_dismissed'
/** 已点过「我已知晓」的紧急公告 id（同样是"本次会话内不再打扰"） */
const CONFIRM_KEY = 'sc_announce_confirmed'

function readIds(key: string): number[] {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) {
      return []
    }
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((v): v is number => typeof v === 'number') : []
  } catch {
    // 隐私模式 / 存储被禁用：退化成"本次页面生命周期内记住"，不影响功能
    return []
  }
}

function writeIds(key: string, ids: number[]): void {
  try {
    sessionStorage.setItem(key, JSON.stringify(ids))
  } catch {
    /* 存不下就算了，最多是刷新后重新打扰一次 */
  }
}

/**
 * 公告状态。
 *
 * 后端已经把「哪些公告此刻该生效」算好了（`GET /announcements/active`），
 * 前端只管**怎么打扰**：1/2 级走横幅、3 级走强制弹窗。
 * 是否"已读"是纯客户端概念，不需要落库 —— 公告是广播，不是消息队列，
 * 为它维护一份 per-user 已读表不值得（也没人真的会去查）。
 */
export const useAnnouncementStore = defineStore('announcement', {
  state: () => ({
    items: [] as AnnouncementActiveVO[],
    loading: false,
    loaded: false,
    dismissed: readIds(DISMISS_KEY),
    confirmed: readIds(CONFIRM_KEY),
  }),

  getters: {
    /** 顶部横幅：1/2 级且没被关掉 */
    banners(state): AnnouncementActiveVO[] {
      return state.items.filter(
        (item) => (item.level ?? 1) < ANNOUNCEMENT_LEVEL_URGENT && !state.dismissed.includes(item.id),
      )
    },

    /** 还没确认过的紧急公告（可能多条，弹窗一次只处理一条） */
    pendingUrgent(state): AnnouncementActiveVO[] {
      return state.items.filter(
        (item) => (item.level ?? 1) >= ANNOUNCEMENT_LEVEL_URGENT && !state.confirmed.includes(item.id),
      )
    },

    /** 当前该弹的那条紧急公告（没有则为 null） */
    currentUrgent(): AnnouncementActiveVO | null {
      return this.pendingUrgent[0] ?? null
    },

    /** 顶栏铃铛上的未读数字 */
    unreadCount(): number {
      return this.banners.length + this.pendingUrgent.length
    },
  },

  actions: {
    /**
     * 拉取生效中的公告。
     *
     * 失败**必须吞掉**：公告是附加信息，不能因为它拉不到就让用户进不了网盘。
     * 同理 `loaded` 只在成功后置位，这样下次进入页面还会再试一次。
     */
    async load(force = false) {
      if (this.loading || (this.loaded && !force)) {
        return
      }
      this.loading = true
      try {
        this.items = await announcementApi.active()
        this.loaded = true
      } catch {
        /* 静默：公告不可用不影响主流程 */
      } finally {
        this.loading = false
      }
    },

    /** 关掉一条横幅 */
    dismiss(id: number) {
      if (this.dismissed.includes(id)) {
        return
      }
      this.dismissed = [...this.dismissed, id]
      writeIds(DISMISS_KEY, this.dismissed)
    },

    /** 紧急公告「我已知晓」 */
    confirm(id: number) {
      if (this.confirmed.includes(id)) {
        return
      }
      this.confirmed = [...this.confirmed, id]
      writeIds(CONFIRM_KEY, this.confirmed)
    },

    /** 退出登录后清空（sessionStorage 已被 user store 整体清过，这里只重置内存态） */
    reset() {
      this.items = []
      this.loaded = false
      this.dismissed = []
      this.confirmed = []
    },
  },
})
