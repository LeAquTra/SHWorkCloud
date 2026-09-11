import { defineStore } from 'pinia'
import { markRaw } from 'vue'
import { abortUpload, purgeOtherUsersCheckpoints, uploadFile, type UploadPhase } from '@/utils/uploader'
import { useUserStore } from '@/stores/user'
import { randomId } from '@/utils/format'

export interface UploadTask {
  id: string
  /** 用 markRaw 存 File：被 Vue 代理后的 File 调用 slice() 会抛 Illegal invocation */
  file: File
  parentId: number
  name: string
  size: number
  phase: UploadPhase | 'pending'
  percent: number
  uploadedBytes: number
  speed: number
  message: string
  receipt: string
  error: string
  controller: AbortController | null
}

interface UploadState {
  tasks: UploadTask[]
  pumping: boolean
}

/**
 * 上传队列。
 * <p>文件级串行（一次只跑一个文件）、分片级并发由 uploader 控制（默认 2）。
 * 机房共享出口带宽，这样能避免"一个人拖 20 个文件把全班带宽占满"。
 */
export const useUploadStore = defineStore('uploader', {
  state: (): UploadState => ({
    tasks: [],
    pumping: false,
  }),

  getters: {
    /** 有未完成任务时，离开页面要拦截 */
    hasUnfinished: (state) =>
      state.tasks.some(
        (task) => task.phase === 'pending' || task.phase === 'hashing'
          || task.phase === 'uploading' || task.phase === 'committing',
      ),
    unfinishedCount: (state) =>
      state.tasks.filter((task) => task.phase !== 'done' && task.phase !== 'failed').length,
    finishedCount: (state) => state.tasks.filter((task) => task.phase === 'done').length,
    failedCount: (state) => state.tasks.filter((task) => task.phase === 'failed').length,
  },

  actions: {
    enqueue(files: File[], parentId: number) {
      const user = useUserStore()
      // 换人上机时不要看到上一位同学的续传任务
      purgeOtherUsersCheckpoints(user.userId)

      for (const file of files) {
        this.tasks.push({
          id: randomId(),
          file: markRaw(file),
          parentId,
          name: file.name,
          size: file.size,
          phase: 'pending',
          percent: 0,
          uploadedBytes: 0,
          speed: 0,
          message: '等待上传',
          receipt: '',
          error: '',
          controller: null,
        })
      }
      void this.pump()
    },

    async pump() {
      if (this.pumping) {
        return
      }
      this.pumping = true
      try {
        // 文件级串行
        for (;;) {
          const next = this.tasks.find((task) => task.phase === 'pending')
          if (!next) {
            break
          }
          await this.run(next)
        }
      } finally {
        this.pumping = false
      }
    },

    async run(task: UploadTask) {
      const user = useUserStore()
      const controller = new AbortController()
      task.controller = controller
      task.error = ''
      task.percent = 0
      task.uploadedBytes = 0
      task.phase = 'hashing'

      try {
        const result = await uploadFile({
          file: task.file,
          parentId: task.parentId,
          userId: user.userId,
          signal: controller.signal,
          onProgress: (progress) => {
            task.phase = progress.phase
            task.percent = progress.percent
            task.uploadedBytes = progress.uploadedBytes
            task.speed = progress.speed
            task.message = progress.message
              || (progress.phase === 'committing' ? '正在登记到网盘…' : '')
          },
        })
        task.phase = 'done'
        task.percent = 100
        task.uploadedBytes = task.size
        task.message = result.instant ? '秒传成功' : '已保存到网盘'
        task.receipt = result.commit.receipt || ''
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          // 暂停：保留断点，等用户继续
          task.phase = 'paused'
          task.message = '已暂停，可继续上传'
        } else {
          task.phase = 'failed'
          task.error = error instanceof Error ? error.message : '上传失败'
          task.message = task.error
        }
      } finally {
        task.controller = null
      }
    },

    /** 暂停：中止当前请求但保留断点，可从已上传分片继续 */
    pause(id: string) {
      const task = this.tasks.find((item) => item.id === id)
      if (task?.controller) {
        task.controller.abort()
      }
    },

    /** 继续/重试 */
    async resume(id: string) {
      const task = this.tasks.find((item) => item.id === id)
      if (!task) {
        return
      }
      task.phase = 'pending'
      task.error = ''
      await this.pump()
    },

    /** 取消并清理服务端未完成分片（避免 OSS 持续计费） */
    async cancel(id: string) {
      const task = this.tasks.find((item) => item.id === id)
      if (!task) {
        return
      }
      if (task.controller) {
        task.controller.abort()
      }
      const user = useUserStore()
      await abortUpload(user.userId, task.file)
      this.remove(id)
    },

    remove(id: string) {
      const index = this.tasks.findIndex((item) => item.id === id)
      if (index >= 0) {
        this.tasks.splice(index, 1)
      }
    },

    clearFinished() {
      this.tasks = this.tasks.filter(
        (task) => task.phase !== 'done' && task.phase !== 'failed' && task.phase !== 'paused',
      )
    },
  },
})
