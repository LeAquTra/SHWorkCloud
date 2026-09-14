<template>
  <div class="announce-center">
    <!--
      1 / 2 级：顶部横幅，可关闭。

      用 transition-group 而不是只显示第一条：分级只决定"多重"，不决定"只能看一条"。
      同一时刻通常只有一两条，堆叠起来不会失控。
      页面顺序（最新/最紧急在前）由服务端排好，这里不再排序。
    -->
    <transition-group name="sc-banner">
      <div
        v-for="item in store.banners"
        :key="item.id"
        class="banner"
        :class="item.level >= 2 ? 'is-important' : 'is-normal'"
        role="status"
      >
        <el-icon class="icon">
          <WarningFilled v-if="item.level >= 2" />
          <InfoFilled v-else />
        </el-icon>

        <div class="text">
          <strong>{{ item.title }}</strong>
          <p class="body">{{ item.content }}</p>
        </div>

        <time v-if="item.publishTime" class="time">{{ formatTime(item.publishTime) }}</time>

        <button type="button" class="close" title="关闭这条公告" @click="store.dismiss(item.id)">
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </transition-group>

    <!--
      3 级：强制弹窗。

      `close-on-click-modal` / `close-on-press-escape` / `show-close` 全部关掉 ——
      "紧急"如果不能强制，就只是"比较显眼的横幅"。
      唯一的出口是「我已知晓」，点掉后才会弹下一条。
    -->
    <el-dialog
      :model-value="!!current"
      :title="current?.title || '紧急公告'"
      width="min(520px, 92vw)"
      align-center
      append-to-body
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      class="urgent-dialog"
    >
      <div class="urgent">
        <el-icon class="urgent-icon"><WarningFilled /></el-icon>
        <p class="urgent-text">{{ current?.content }}</p>
      </div>

      <p v-if="store.pendingUrgent.length > 1" class="urgent-queue">
        还有 {{ store.pendingUrgent.length - 1 }} 条紧急公告待确认
      </p>

      <template #footer>
        <el-button type="primary" size="large" @click="acknowledge">我已知晓</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { Close, InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import { useAnnouncementStore } from '@/stores/announcement'
import { formatTime } from '@/utils/format'

const store = useAnnouncementStore()

const current = computed(() => store.currentUrgent)

function acknowledge() {
  const item = current.value
  if (item) {
    store.confirm(item.id)
  }
}

onMounted(() => {
  // 页头的铃铛也会拉一次，store 内部做了去重，不会打两次请求
  void store.load()
})
</script>

<style scoped>
.announce-center {
  flex: 0 0 auto;
}

/* ---------------- 横幅 ---------------- */

.banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px var(--sc-gutter);
  border-bottom: 1px solid var(--sc-border);
  font-size: 13px;
  position: relative;
  z-index: 15;
}

.banner.is-normal {
  background: var(--sc-brand-soft);
  color: var(--sc-brand);
}

.banner.is-important {
  background: rgba(245, 158, 11, 0.14);
  color: var(--sc-warning);
}

.banner .icon {
  font-size: 16px;
  margin-top: 2px;
  flex: 0 0 auto;
}

.text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.text strong {
  font-size: 13.5px;
  font-weight: 650;
  color: var(--sc-text);
}

.body {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--sc-text-2);
  /* 正文是纯文本；长公告在横幅里只露 3 行，完整内容到铃铛里看 */
  white-space: pre-wrap;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.time {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--sc-text-3);
  font-variant-numeric: tabular-nums;
  margin-top: 2px;
}

.close {
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--sc-radius-full);
  background: transparent;
  color: inherit;
  opacity: 0.7;
  cursor: pointer;
  transition: var(--sc-transition);
}

.close:hover {
  opacity: 1;
  background: var(--sc-hover);
}

/* 横幅出现/消失的轻微位移动画（不写 transition-group 的 CSS 会没有动画） */
.sc-banner-enter-active,
.sc-banner-leave-active {
  transition: var(--sc-transition);
}

.sc-banner-enter-from,
.sc-banner-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ---------------- 紧急弹窗 ---------------- */

.urgent {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.urgent-icon {
  font-size: 24px;
  color: var(--sc-danger);
  flex: 0 0 auto;
  margin-top: 2px;
}

.urgent-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: var(--sc-text);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 46vh;
  overflow-y: auto;
}

.urgent-queue {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--sc-text-3);
}
</style>
