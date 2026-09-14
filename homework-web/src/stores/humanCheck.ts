import { defineStore } from 'pinia'
import { authApi } from '@/api'
import type { HumanCheckVO } from '@/types/api'

/** 需要人机验证的三个场景 */
export type HumanCheckScope = 'login' | 'register' | 'upload'

export const HUMAN_CHECK_SCOPE_LABELS: Record<HumanCheckScope, string> = {
  login: '登录',
  register: '注册',
  upload: '上传文件',
}

/** 拿不到配置时的兜底：按"不需要验证"处理，理由见 load() */
const FALLBACK_CONFIG: HumanCheckVO = {
  login: false,
  register: false,
  upload: false,
  passTtlSeconds: 300,
}

interface HumanCheckState {
  config: HumanCheckVO | null
  loading: boolean
  /** 弹窗是否可见 */
  visible: boolean
  /** 当前正在为哪个场景弹窗 */
  scope: HumanCheckScope | null
  /** 已拿到但还没用掉的凭证（复用可避免"密码输错一次就重做一次验证码"） */
  token: string
  tokenExpireAt: number
  /** 等待用户完成验证的 resolve；null 表示当前没有待处理的弹窗 */
  pending: ((token: string | null) => void) | null
  /** 并发调用共用同一个弹窗，避免同时弹出好几个验证码窗口 */
  inflight: Promise<string | null> | null
}

/**
 * 人机验证（后台图片验证码题库）。
 *
 * <p>三个场景共用一套流程：**先问服务端此刻要不要验证**（`GET /auth/human-check` 给的是
 * 生效值），要就弹一次验证码窗口，拿到 `captchaPassToken` 再继续原来的请求。
 *
 * <p>两个容易踩的点：
 * <ol>
 *   <li>**服务端才是权威**。配置可能在页面停留期间变化（管理员刚上传了第一批题目），
 *       所以业务代码在收到 `40105 / 40103` 时必须能"再弹一次"，不能只信页面加载时那份配置；</li>
 *   <li>**并发要合并**。一次上传会并发申请多个凭证，若每个都弹窗，用户会被弹窗淹没。
 *       这里的 in-flight 合并 + 凭证复用就是为这个准备的。</li>
 * </ol>
 */
export const useHumanCheckStore = defineStore('humanCheck', {
  state: (): HumanCheckState => ({
    config: null,
    loading: false,
    visible: false,
    scope: null,
    token: '',
    tokenExpireAt: 0,
    pending: null,
    inflight: null,
  }),

  getters: {
    /** 弹窗标题用的场景名 */
    scopeLabel(state): string {
      return state.scope ? HUMAN_CHECK_SCOPE_LABELS[state.scope] : ''
    },
  },

  actions: {
    /**
     * 拉取三个场景的生效开关。
     *
     * <p>失败时**按"不需要验证"处理**：人机验证是防脚本的附加防线，
     * 不能因为它自己的配置接口挂了就把所有人挡在登录页外面
     * （真需要验证时服务端仍会返回 40105，业务代码会再弹一次窗）。
     */
    async load(force = false): Promise<HumanCheckVO> {
      if (this.config && !force) {
        return this.config
      }
      if (this.loading) {
        return this.config ?? FALLBACK_CONFIG
      }
      this.loading = true
      try {
        this.config = await authApi.humanCheck()
      } catch {
        this.config = this.config ?? { ...FALLBACK_CONFIG }
      } finally {
        this.loading = false
      }
      return this.config
    },

    /** 该场景此刻是否需要人机验证（配置未加载时会先打一次接口） */
    async required(scope: HumanCheckScope): Promise<boolean> {
      const config = await this.load()
      return config[scope] === true
    },

    /**
     * 确保拿到一个可用的凭证；不需要验证或用户取消时返回 `null`。
     *
     * @param force true = 忽略"配置说不需要"，强制弹窗。
     *              服务端返回 40105/40103 时用它重试（配置可能已经变了）。
     *              注意 force 仍会复用**未过期**的凭证 —— 否则密码输错重试也要重做验证码。
     */
    async ensure(scope: HumanCheckScope, force = false): Promise<string | null> {
      const config = await this.load()
      if (!force && config[scope] !== true) {
        return null
      }
      if (this.token && this.tokenExpireAt > Date.now()) {
        return this.token
      }
      if (this.inflight) {
        return this.inflight
      }
      this.inflight = this.openDialog(scope).finally(() => {
        this.inflight = null
      })
      return this.inflight
    },

    /** 打开弹窗并等待结果（由 HumanCheckDialog 调用 resolve* 结束等待） */
    openDialog(scope: HumanCheckScope): Promise<string | null> {
      return new Promise<string | null>((resolve) => {
        this.scope = scope
        this.visible = true
        this.pending = (token) => {
          this.pending = null
          this.visible = false
          this.scope = null
          if (token) {
            this.token = token
            this.tokenExpireAt = Date.now() + (this.config?.passTtlSeconds ?? 300) * 1000
          }
          resolve(token)
        }
      })
    },

    /** 验证通过 */
    resolvePassed(token: string) {
      this.pending?.(token)
    },

    /** 用户取消 / 关闭弹窗 */
    resolveCancelled() {
      this.pending?.(null)
    },

    /** 凭证已被服务端消费（注册场景是一次性的）时清掉本地缓存 */
    clearToken() {
      this.token = ''
      this.tokenExpireAt = 0
    },
  },
})
