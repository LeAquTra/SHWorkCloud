<template>
  <el-popover
    v-model:visible="visible"
    trigger="click"
    placement="bottom-end"
    :width="360"
    popper-class="sc-announce-popover"
  >
    <template #reference>
      <button
        type="button"
        class="bell"
        :class="{ unread: store.unreadCount > 0 }"
        :title="store.unreadCount > 0 ? `有 ${store.unreadCount} 条未读公告` : '公告'"
      >
        <el-badge :value="store.unreadCount" :max="9" :hidden="store.unreadCount === 0">
          <el-icon><Bell /></el-icon>
        </el-badge>
      </button>
    </template>

    <div class="pop">
      <div class="pop-head">
        <strong>公告</strong>
        <span class="sc-muted">{{ store.items.length }} 条生效中</span>
      </div>

      <p v-if="!store.items.length" class="pop-empty sc-muted">暂无公告</p>

      <ul v-else class="pop-list sc-scroll-y">
        <li v-for="item in store.items" :key="item.id">
          <button
            type="button"
            class="pop-item"
            :class="{ open: expandedId === item.id }"
            @click="toggle(item.id)"
          >
            <span class="row">
              <el-tag size="small" effect="light" :type="levelTag(item.level)">
                {{ levelLabel(item.level) }}
              </el-tag>
              <strong>{{ item.title }}</strong>
            </span>
            <span class="meta sc-muted">
              {{ formatTime(item.publishTime) }}
              <template v-if="item.expireTime"> · {{ formatTime(item.expireTime) }} 过期</template>
            </span>
            <p v-if="expandedId === item.id" class="content">{{ item.content }}</p>
          </button>
        </li>
      </ul>

      <p class="pop-foot sc-muted">
        公告由超级管理员发布。紧急公告会强制弹窗确认。
      </p>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import { useAnnouncementStore } from '@/stores/announcement'
import { ANNOUNCEMENT_LEVEL_LABELS } from '@/types/api'
import { formatTime } from '@/utils/format'

const store = useAnnouncementStore()
const visible = ref(false)
const expandedId = ref<number | null>(null)

function toggle(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

function levelLabel(level: number | null | undefined): string {
  return ANNOUNCEMENT_LEVEL_LABELS[level ?? 1] || '普通'
}

/** 分级 → 标签配色：普通灰、重要橙、紧急红 */
function levelTag(level: number | null | undefined): 'info' | 'warning' | 'danger' {
  if ((level ?? 1) >= 3) {
    return 'danger'
  }
  return (level ?? 1) === 2 ? 'warning' : 'info'
}

onMounted(() => {
  // 铃铛随页头一起挂载，所以由它负责首次拉取公告
  void store.load()
})
</script>

<style scoped>
.bell {
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--sc-radius-full);
  border: 1px solid var(--sc-border);
  background: var(--sc-surface);
  color: var(--sc-text-2);
  cursor: pointer;
  transition: var(--sc-transition);
}

.bell:hover {
  border-color: var(--sc-border-2);
  color: var(--sc-text);
  box-shadow: var(--sc-shadow-sm);
}

.bell.unread {
  color: var(--sc-brand);
  border-color: var(--sc-brand);
}

.pop {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pop-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 13px;
}

.pop-head strong {
  font-weight: 650;
}

.pop-head span {
  font-size: 11.5px;
}

.pop-empty {
  margin: 6px 0;
  font-size: 12.5px;
  text-align: center;
}

.pop-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 320px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.pop-item {
  width: 100%;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 9px 10px;
  border: none;
  border-radius: var(--sc-radius-sm);
  background: transparent;
  color: inherit;
  cursor: pointer;
  transition: var(--sc-transition);
}

.pop-item:hover,
.pop-item.open {
  background: var(--sc-hover);
}

.row {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.row strong {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  font-size: 11px;
}

.content {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--sc-text-2);
  /* 正文是纯文本，换行是作者的本意 */
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow-y: auto;
}

.pop-foot {
  margin: 2px 0 0;
  padding-top: 8px;
  border-top: 1px solid var(--sc-border);
  font-size: 11px;
  line-height: 1.5;
}
</style>
