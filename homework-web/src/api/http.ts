import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResult } from '@/types/api'

/** 业务错误：携带后端错误码，调用方可用 code 做分支 */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** 与后端 ErrorCode 对应的常量（只列前端需要分支处理的） */
export const CODE = {
  OK: 0,
  BAD_PARAM: 40000,
  QUOTA_EXCEEDED: 40010,
  DUPLICATE_NAME: 40020,
  UPLOAD_TOKEN_INVALID: 40061,
  FILE_NOT_FOUND: 40070,
  /** 对 text / office / none 类型调流式预览时的返回码 */
  NOT_PREVIEWABLE: 40073,
  /** 单文件超限；头像、文本阅览超限也是它 */
  FILE_TOO_LARGE: 40082,
  UNAUTHORIZED: 40100,
  CAPTCHA_POOL_EMPTY: 40104,
  TOO_FREQUENT: 40113,
  QUOTA_LIMITED: 40114,
  LOGIN_FAILED: 40116,
  ACCOUNT_DISABLED: 40117,
  ACCOUNT_LOCKED: 40118,
  MUST_CHANGE_PASSWORD: 40119,
  STUDENT_EXISTS: 40121,
  REGISTER_DISABLED: 40122,
  /** 头像冷却中：每 24 小时只能改一次 */
  AVATAR_TOO_FREQUENT: 40123,
  /** 公告不存在 / 状态不允许该操作 */
  ANNOUNCEMENT_NOT_FOUND: 40094,
  ANNOUNCEMENT_STATE_INVALID: 40095,
  FORBIDDEN: 40300,
} as const

const TOKEN_KEY = 'sc_token'

/**
 * Token 存 **sessionStorage** 而不是 localStorage。
 * 机房是共用电脑：学生 A 忘退就下课关机，用 localStorage 会让下一位学生
 * 直接进入 A 的网盘。sessionStorage 关闭标签即失效。
 */
export function getToken(): string {
  return sessionStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY)
}

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 60000,
})

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    // 与后端 sa-token.token-name 一致
    config.headers.set('Authorization', token)
  }
  return config
})

/** 需要跳转登录页时统一在这里做，避免各处 import router 造成循环依赖 */
function redirectToLogin(): void {
  clearToken()
  sessionStorage.clear()
  if (!location.pathname.startsWith('/login')) {
    location.assign('/login')
  }
}

/** 无权限：跳 403 页，而不是只弹一个 toast */
function redirectToForbidden(): void {
  if (!location.pathname.startsWith('/403')) {
    location.assign('/403')
  }
}

instance.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      redirectToLogin()
    } else if (status === 403) {
      redirectToForbidden()
    }
    return Promise.reject(error)
  },
)

/**
 * 统一请求：拆开 { code, message, data } 信封。
 * 业务码非 0 一律抛 ApiError，调用方 try/catch 后按 code 分支。
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResult<T>>(config)
  const body = response.data
  if (!body || typeof body.code !== 'number') {
    throw new ApiError(-1, '响应格式异常')
  }
  if (body.code === CODE.UNAUTHORIZED) {
    redirectToLogin()
    throw new ApiError(body.code, body.message)
  }
  if (body.code === CODE.MUST_CHANGE_PASSWORD) {
    if (!location.pathname.startsWith('/change-password')) {
      location.assign('/change-password')
    }
    throw new ApiError(body.code, body.message)
  }
  if (body.code === CODE.ACCOUNT_DISABLED) {
    redirectToLogin()
    throw new ApiError(body.code, '账号已被禁用，请联系老师')
  }
  if (body.code === CODE.FORBIDDEN) {
    redirectToForbidden()
    throw new ApiError(body.code, body.message || '无权限')
  }
  if (body.code !== CODE.OK) {
    throw new ApiError(body.code, body.message || '请求失败')
  }
  return body.data
}

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return request<T>({ url, method: 'GET', params })
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'POST', data, ...config })
}

export function put<T>(url: string, data?: unknown): Promise<T> {
  return request<T>({ url, method: 'PUT', data })
}

export function del<T>(url: string, data?: unknown, params?: Record<string, unknown>): Promise<T> {
  return request<T>({ url, method: 'DELETE', data, params })
}

/** 文件下载（模板导出等）：直接拿二进制 */
export async function getBlob(url: string, params?: Record<string, unknown>): Promise<Blob> {
  const response = await instance.request<Blob>({ url, method: 'GET', params, responseType: 'blob' })
  return response.data
}

export default instance
