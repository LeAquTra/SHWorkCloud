import { defineStore } from 'pinia'

/**
 * 主题（明 / 暗 / 跟随系统）。
 *
 * <p>为什么主题存 localStorage 而 token 存 sessionStorage：
 * token 属于"本次上机的会话状态"，共用电脑上必须随标签页关闭而失效；
 * 而主题是这台机器的显示偏好，下一位同学继续用暗色并不构成信息泄露，
 * 反复重置反而更烦。因此两者的存储位置刻意不同。
 */

export type ThemeMode = 'light' | 'dark' | 'auto'

export const THEME_KEY = 'sc_theme'

/** 供 index.html 的内联脚本与 store 共用，避免两处写死同一个 key */
export function readStoredMode(): ThemeMode {
  const raw = localStorage.getItem(THEME_KEY)
  return raw === 'light' || raw === 'dark' || raw === 'auto' ? raw : 'auto'
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches
}

interface ThemeState {
  mode: ThemeMode
  /** 当前真正生效的主题（把 auto 解析之后的结果） */
  resolved: 'light' | 'dark'
}

export const useThemeStore = defineStore('theme', {
  state: (): ThemeState => ({
    mode: readStoredMode(),
    resolved: 'light',
  }),

  getters: {
    isDark: (state) => state.resolved === 'dark',
    /** 三态切换顺序：亮 → 暗 → 跟随系统 → 亮 */
    nextMode: (state): ThemeMode =>
      state.mode === 'light' ? 'dark' : state.mode === 'dark' ? 'auto' : 'light',
    label: (state) => (state.mode === 'light' ? '亮色' : state.mode === 'dark' ? '暗色' : '跟随系统'),
  },

  actions: {
    /** 应用主题到 <html>：Element Plus 的暗色变量挂在 html.dark 上 */
    apply() {
      const dark = this.mode === 'dark' || (this.mode === 'auto' && systemPrefersDark())
      this.resolved = dark ? 'dark' : 'light'

      const root = document.documentElement
      root.classList.toggle('dark', dark)
      root.dataset.theme = this.resolved
      // 让浏览器把滚动条、表单控件、自动填充也切成对应主题
      root.style.colorScheme = this.resolved
    },

    setMode(mode: ThemeMode) {
      this.mode = mode
      try {
        localStorage.setItem(THEME_KEY, mode)
      } catch {
        // 隐私模式下写不进去：本次会话仍然生效，只是下次打开回到默认值
      }
      this.apply()
    },

    cycle() {
      this.setMode(this.nextMode)
    },

    /**
     * 启动时调用。注意 index.html 里已有一段内联脚本先按存储值刷了一次 class，
     * 这里只是让 store 状态与 DOM 对齐，并挂上系统主题变化监听。
     */
    init() {
      this.apply()
      if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
        window
          .matchMedia('(prefers-color-scheme: dark)')
          .addEventListener('change', () => {
            if (this.mode === 'auto') {
              this.apply()
            }
          })
      }
    },
  },
})
