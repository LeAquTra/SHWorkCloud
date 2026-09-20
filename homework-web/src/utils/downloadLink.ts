/**
 * 「复制下载链接」。
 *
 * <p>下载地址来自 `GET /files/{id}/download-url`：私有 Bucket 的 10 分钟签名地址，
 * 已经带上 `response-content-disposition=attachment; filename=...`，所以复制出来
 * 直接粘到浏览器地址栏、下载工具或另一台设备上就能下，**不需要再带 Authorization**。
 *
 * <p>因为不需要登录就能用，这个地址本质上是一个「10 分钟内有效的临时取件码」：
 * 谁拿到谁能下。所以提示语必须把有效期说清楚，不能让用户以为是一条长期可用的分享链接
 * （真正的文件分享功能见设计文档 §1.4，属非目标）。
 *
 * <p>为什么放在独立的 utils 而不是塞进 `format.ts`：`format.ts` 是纯格式化工具
 * （无网络、无副作用），而这个模块要调接口并弹 toast。保持前者纯净，
 * 渲染层的格式化函数才不会被拖进「顺带发请求」的坑里。
 */
import { ElMessage } from 'element-plus'
import { fileApi } from '@/api'
import { ApiError } from '@/api/http'
import { copyText } from '@/utils/format'

/**
 * 后端签名有效期（秒），见 `FileService.SIGN_EXPIRE_SECONDS`。
 * 只用于提示文案 —— **真正的过期判定在服务端**，这里不参与任何逻辑分支。
 */
export const DOWNLOAD_LINK_EXPIRE_MINUTES = 10

/**
 * 取签名下载地址并复制到剪贴板。
 *
 * <p>返回是否复制成功；失败时（接口报错、浏览器拒绝写剪贴板）已经弹过提示，
 * 调用方无需再处理。
 */
export async function copyDownloadLink(fileId: number, fileName?: string): Promise<boolean> {
  try {
    const { url } = await fileApi.downloadUrl(fileId)
    if (!url || typeof url !== 'string') {
      // 与 triggerDownload 同一个坑：契约是 { url } 对象，一旦后端改成裸字符串，
      // 这里解构出的就是 undefined。剪贴板会安安静静地写入 "undefined"，
      // 用户粘出来才发现是废链接且无从定位，所以必须显式拦住。
      ElMessage.error('没有拿到有效的下载地址（响应格式不正确）')
      return false
    }

    const ok = await copyText(url)
    if (!ok) {
      ElMessage.error('浏览器拒绝了剪贴板写入，请手动复制（内网 http 站点可能受限）')
      return false
    }

    ElMessage.success({
      message: `${fileName ? `「${fileName}」的` : ''}下载链接已复制，${DOWNLOAD_LINK_EXPIRE_MINUTES} 分钟内有效`,
      duration: 5000,
    })
    return true
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '获取下载地址失败')
    return false
  }
}
