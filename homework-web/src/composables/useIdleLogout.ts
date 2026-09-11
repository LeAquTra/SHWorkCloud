import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * 空闲自动登出。
 *
 * 机房是共用电脑：学生下课直接关机走人，下一位学生打开浏览器就进了上一位的网盘。
 * 服务端有 sa-token.active-timeout（30 分钟）兜底，前端提前提醒并主动登出，
 * 避免"填了半天表单突然 401"这种糟糕体验。
 */
export function useIdleLogout() {
  const user = useUserStore()
  const warnVisible = ref(false)
  /** 提醒后留给用户的操作时间 */
  const GRACE_SECONDS = 60

  let warnTimer: number | undefined
  let logoutTimer: number | undefined
  let countdownTimer: number | undefined
  const secondsLeft = ref(GRACE_SECONDS)

  const clearTimers = () => {
    if (warnTimer) window.clearTimeout(warnTimer)
    if (logoutTimer) window.clearTimeout(logoutTimer)
    if (countdownTimer) window.clearInterval(countdownTimer)
    warnTimer = logoutTimer = undefined
    countdownTimer = undefined
  }

  const reset = () => {
    clearTimers()
    warnVisible.value = false
    secondsLeft.value = GRACE_SECONDS

    const idleMinutes = user.idleLogoutMinutes > 0 ? user.idleLogoutMinutes : 20
    // 提前 1 分钟提醒
    const warnAfter = Math.max(1, idleMinutes - 1) * 60_000
    warnTimer = window.setTimeout(() => {
      warnVisible.value = true
      secondsLeft.value = GRACE_SECONDS
      countdownTimer = window.setInterval(() => {
        secondsLeft.value -= 1
        if (secondsLeft.value <= 0) {
          void user.logout()
        }
      }, 1000)
      logoutTimer = window.setTimeout(() => {
        void user.logout()
      }, GRACE_SECONDS * 1000)
    }, warnAfter)
  }

  const events = ['mousemove', 'keydown', 'click', 'scroll', 'touchstart'] as const

  onMounted(() => {
    events.forEach((name) => window.addEventListener(name, reset, { passive: true }))
    reset()
  })

  onBeforeUnmount(() => {
    events.forEach((name) => window.removeEventListener(name, reset))
    clearTimers()
  })

  return { warnVisible, secondsLeft, continueSession: reset }
}
