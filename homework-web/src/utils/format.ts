/** 展示格式化工具 */

export function formatSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || bytes < 0) {
    return '-'
  }
  if (bytes === 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / Math.pow(1024, index)
  return `${value >= 100 || index === 0 ? Math.round(value) : value.toFixed(1)} ${units[index]}`
}

/** 后端返回的是 "yyyy-MM-dd HH:mm:ss" 形式的字符串 */
export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ')
}

export function formatPercent(value: number): string {
  return `${Math.max(0, Math.min(100, Math.round(value)))}%`
}

/** 文件类型图标（用 emoji 避免额外引入图标包） */
export function fileIcon(item: { folder: boolean; suffix: string | null }): string {
  if (item.folder) {
    return '📁'
  }
  const suffix = (item.suffix || '').toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(suffix)) return '🖼'
  if (['mp4', 'avi', 'mkv', 'mov', 'wmv', 'flv', 'webm'].includes(suffix)) return '🎬'
  if (['mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a'].includes(suffix)) return '🎵'
  if (suffix === 'pdf') return '📕'
  if (['doc', 'docx'].includes(suffix)) return '📘'
  if (['xls', 'xlsx', 'csv'].includes(suffix)) return '📗'
  if (['ppt', 'pptx'].includes(suffix)) return '📙'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(suffix)) return '🗜'
  if (['txt', 'md'].includes(suffix)) return '📄'
  return '📎'
}

/** 触发浏览器下载（走已签名的 URL） */
export function triggerDownload(url: string): void {
  const a = document.createElement('a')
  a.href = url
  // 后端已在签名 URL 上带 response-content-disposition，这里不再指定 download 属性
  a.rel = 'noopener'
  a.target = '_blank'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/** 生成简短随机 id（不依赖 Web Crypto，内网 http 下 crypto.randomUUID 不存在） */
export function randomId(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4)
}
