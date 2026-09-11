/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 后端 API 前缀；生产由 Nginx 同源托管时为 /api */
  readonly VITE_API_BASE: string
  /** 单机分片并发数，机房建议 2 */
  readonly VITE_UPLOAD_PARALLEL: string
  /** 超过该大小(MB)改用抽样指纹，需与后端 app.upload.instant-threshold-bytes 一致 */
  readonly VITE_INSTANT_THRESHOLD_MB: string
  /** 本节课下课时间，格式 HH:mm；配置后前端会在下课前提醒确认文件已保存 */
  readonly VITE_CLASS_END_TIME: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
