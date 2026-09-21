import { defineStore } from 'pinia'
import { adminApi } from '@/api'

/**
 * 社区待审核数的**唯一来源**。
 *
 * <p>为什么必须是 store 而不是各页面各写一个 `setInterval`：
 * <ol>
 *   <li>同一个数字有两个消费者 —— 后台侧栏「社区审核」的**红点角标**
 *       （{@code AdminLayout}）和审核页顶部的「当前还有 N 条待审核」
 *       （{@code PostReviewView}）。各拉各的就会出现"侧栏显示 3 条、
 *       点进去页面说 0 条"这种自相矛盾的界面；</li>
 *   <li>审核页在轮询后知道队列变了，可以让侧栏**立刻**跟着变，不用等下一次轮询；</li>
 *   <li>轮询的启停（谁来起、什么时候起、组件卸载怎么办）只写一遍。</li>
 * </ol>
 *
 * <p><b>轮询失败一律静默保留上一次的值</b>：网络抖一下不该让红点闪掉、
 * 更不该在学生做题时弹一个错误提示。要报错的是"审核页加载列表"，
 * 那件事的错误由 {@code PostReviewView} 自己常驻显示。
 *
 * <p>页面切到后台（标签页不可见）时暂停轮询，回到前台立刻补一次 ——
 * 既省掉没人看的请求，又保证老师切回来时红点是最新的。
 */
export const usePostReviewStore = defineStore('postReview', {
  state: () => ({
    /** 全站待审核帖子数（后端 post.status = 0 的总数） */
    pending: 0,
    /** 是否已经成功取过一次值；用来区分"确实是 0"与"还没拉到" */
    loaded: false,
    /**
     * 每次**变大**就 +1。
     * <p>侧栏拿它当 `key` 绑在红点上：数字一变 key 就变，Vue 会重建那个节点，
     * CSS 动画因此会重新播一遍 —— 这是"有人刚发帖"的唯一视觉线索。
     * 数字变小（有人在别处审完了）不动它，那种变化不需要打断谁。
     */
    bumpKey: 0,
  }),

  getters: {
    /** 有东西在等审核 —— 侧栏红点与提示文案都用它 */
    hasPending: (state) => state.pending > 0,
    /** 角标文案：超过 99 显示 99+（与 el-badge 的 :max 一致） */
    badgeText: (state) => (state.pending > 99 ? '99+' : String(state.pending)),
  },

  actions: {
    /**
     * 拉一次待审数。返回本次取到的值，失败返回 {@code null}（调用方据此判断"这次没拿到"）。
     */
    async refresh(): Promise<number | null> {
      try {
        const count = await adminApi.postPendingCount()
        this.set(count)
        return count
      } catch {
        return null
      }
    },

    /**
     * 写入一个已知的待审数。
     * <p>两个来源用它：轮询拉到的新值，以及审核页 `GET /admin/posts` 响应里
     * 顺带带回来的 `pendingTotal`（那次请求本来就要发，不必再单独拉一次计数）。
     * <p>变大时推进 {@link bumpKey}，让侧栏红点闪一下。
     */
    set(count: number) {
      const next = Math.max(0, count)
      if (next > this.pending) {
        this.bumpKey += 1
      }
      this.pending = next
      this.loaded = true
    },

    /** 退出登录时清掉，避免下一位上机的同学看到上一位的红点 */
    reset() {
      this.pending = 0
      this.loaded = false
    },
  },
})

// ---------------------------------------------------------------- 轮询（不属于 state）

/**
 * 待审数轮询间隔（毫秒）。
 *
 * <p>20 秒是"发帖 → 红点亮"的最坏延迟。审核不是即时通讯，不需要更短；
 * 但也不宜再长：老师在办公室开着后台，学生刚交的帖子要能在一眼之内看到。
 *
 * <p>真正的即时性由另外三条路径兜住（见 {@link bindForegroundRefresh}）：
 * 切回标签页、切回浏览器窗口、以及审核页自己拉列表时都会顺手对齐这个数字。
 */
export const PENDING_POLL_MS = 20_000

/** 两次"回到前台"刷新之间的最小间隔：切窗口很频繁，别把它变成压测 */
const FOREGROUND_COOLDOWN_MS = 3_000

let timer: number | undefined
/** 正在跑的那次 refresh 的引用，避免回到前台时并发叠请求 */
let inFlight: Promise<unknown> | null = null
let foregroundBound = false
let lastForegroundAt = 0

type Store = ReturnType<typeof usePostReviewStore>

function tick(store: Store) {
  if (inFlight) {
    return
  }
  inFlight = store.refresh().finally(() => {
    inFlight = null
  })
}

/** 回到前台时补一次（带冷却）：让红点跟上"我不在的时候发生的事" */
function refreshOnForeground(store: Store) {
  if (timer === undefined) {
    return
  }
  const now = Date.now()
  if (now - lastForegroundAt < FOREGROUND_COOLDOWN_MS) {
    return
  }
  lastForegroundAt = now
  tick(store)
}

/**
 * 开始轮询待审数，并**立即先拉一次**（否则进后台要等一个间隔才看到红点）。
 *
 * <p>重复调用是安全的：已有定时器就先停掉再重开。只应由后台入口
 * （{@code AdminLayout}）调用 —— 非后台角色打这个接口会被 403 挡掉。
 */
export function startPendingPolling(store: Store = usePostReviewStore()) {
  stopPendingPolling()
  void store.refresh()

  timer = window.setInterval(() => {
    // 标签页不可见时不轮询：没人看的请求没有意义
    if (document.visibilityState === 'visible') {
      tick(store)
    }
  }, PENDING_POLL_MS)

  bindForegroundRefresh(store)
}

/**
 * 绑定"回到前台就刷新一次"。
 *
 * <p>为什么需要它：机房里最常见的动作是<b>切走再切回来</b>
 * （老师去看别的窗口、去机房转一圈、去另一个标签页）。
 * 只靠定时器的话，切回来的那一刻红点还是旧的 —— 而这正是他最需要看到最新值的一刻。
 * 这里用 document 与 window 两个信号，并加 3 秒冷却，避免频繁切窗口把接口打成压测。
 *
 * <p>只绑定一次（模块级 flag），组件反复挂载/卸载不会叠加监听。
 */
function bindForegroundRefresh(store: Store) {
  if (foregroundBound) {
    return
  }
  foregroundBound = true
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      refreshOnForeground(store)
    }
  })
  // 同一个标签页内的窗口切换不会触发 visibilitychange，但对老师同样是"我回来了"
  window.addEventListener('focus', () => refreshOnForeground(store))
}

export function stopPendingPolling() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}
