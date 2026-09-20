import { defineStore } from 'pinia'
import { chatApi } from '@/api'
import { useUserStore } from '@/stores/user'

/**
 * 未读红点。
 *
 * <p>为什么单独做一个 store 而不是放在好友页里：红点要挂在**顶栏导航**上，
 * 而顶栏在文件管理页、相册页等所有页面都存在 —— 用户不看好友页的时候
 * 也必须知道"有人在等我"。轮询因此放在这一层（由 App.vue 生命周期驱动）。
 *
 * <p><b>轮询间隔是一个刻意的折中：</b>
 *  - 顶栏红点：30 秒。它只回答"有没有人找我"，晚半分钟知道没有损失，
 *    而全班 50 台机器同时 5 秒轮询会给服务端带来 10 QPS 的无谓压力；
 *  - 聊天窗打开时：由聊天组件自己用 3~5 秒拉增量（见 FriendsView），
 *    那时用户确实在等消息，值这个开销。
 *
 * <p>接口是 `GET /chat/unread`，服务端只有两个 COUNT，
 * 走 `idx_to_read_id`，是这个功能里最便宜的一个请求。
 */
const POLL_INTERVAL_MS = 30_000

export const useFriendStore = defineStore('friend', {
  state: () => ({
    /** 未读消息 + 待处理好友申请 */
    total: 0,
    /** 未读消息数 */
    friends: 0,
    /** 待处理申请数（红点里要单独区分，因为处理入口在好友页） */
    requests: 0,
    /** 拉取过一次之前不显示红点，避免刚进页面闪一下 0 */
    loaded: false,
    /** 最近一次拉取是否失败：失败时保留上一次的数值，不要把红点清零 */
    failed: false,
  }),

  getters: {
    /** 是否显示红点 */
    hasUnread: (state) => state.loaded && state.total > 0,
    /** 红点文案：超过 99 显示 99+（避免把顶栏撑变形） */
    badge: (state): string => (state.total > 99 ? '99+' : String(state.total)),
  },

  actions: {
    /**
     * 拉一次未读数。
     * <p><b>失败时静默保留旧值</b>：轮询期间偶发网络抖动不该让红点闪掉，
     * 也不该弹错误提示（用户没做任何操作，弹窗只会让人困惑）。
     * 401 由 http 拦截器统一跳登录页，这里不再处理。
     */
    async refresh() {
      if (!useUserStore().isLoggedIn) {
        return
      }
      try {
        const data = await chatApi.unread()
        this.total = data.total
        this.friends = data.friends
        this.requests = data.requests
        this.loaded = true
        this.failed = false
      } catch {
        this.failed = true
      }
    },

    /**
     * 本地扣减未读数（读掉一条会话后用）。
     * <p>只做减法不重新请求：读完消息紧接着就会刷新会话列表，
     * 这里的作用是让顶栏红点在下一个轮询周期之前就立刻变对。
     */
    consume(chatCount: number, requestCount = 0) {
      this.friends = Math.max(0, this.friends - Math.max(0, chatCount))
      this.requests = Math.max(0, this.requests - Math.max(0, requestCount))
      this.total = this.friends + this.requests
    },

    /** 退出登录、被踢下线时清零 */
    reset() {
      this.total = 0
      this.friends = 0
      this.requests = 0
      this.loaded = false
      this.failed = false
    },
  },
})

/** 轮询间隔导出给需要保持一致的地方（目前只有 App.vue 用） */
export const FRIEND_POLL_INTERVAL_MS = POLL_INTERVAL_MS
