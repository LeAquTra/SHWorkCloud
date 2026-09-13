<template>
  <nav class="crumbs">
    <button type="button" class="crumb" :class="{ current: !items.length }" @click="emit('navigate', 0)">
      <el-icon><HomeFilled /></el-icon>
      <span>全部文件</span>
    </button>
    <template v-for="item in items" :key="item.id">
      <el-icon class="sep"><ArrowRight /></el-icon>
      <button type="button" class="crumb" @click="emit('navigate', item.id)">{{ item.name }}</button>
    </template>
  </nav>
</template>

<script setup lang="ts">
import { ArrowRight, HomeFilled } from '@element-plus/icons-vue'
import type { BreadcrumbVO } from '@/types/api'

defineProps<{ items: BreadcrumbVO[] }>()
const emit = defineEmits<{ navigate: [id: number] }>()
</script>

<style scoped>
.crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  min-height: 26px;
}

.crumb {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border: none;
  border-radius: var(--sc-radius-sm);
  background: transparent;
  color: var(--sc-text-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: var(--sc-transition);
}

.crumb:hover {
  background: var(--sc-hover);
  color: var(--sc-text);
}

/* 最后一级是当前位置，不做成可点样式以免误以为能跳走 */
.crumb:last-child {
  color: var(--sc-text);
  font-weight: 620;
}

.sep {
  color: var(--sc-text-3);
  font-size: 12px;
}
</style>
