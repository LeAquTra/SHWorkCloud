<template>
  <div class="theme-toggle" role="group" aria-label="主题切换">
    <span class="thumb" :style="{ transform: `translateX(${activeIndex * 100}%)` }" />
    <el-tooltip
      v-for="(option, index) in options"
      :key="option.value"
      :content="option.label"
      placement="bottom"
      :show-after="240"
    >
      <button
        type="button"
        class="opt"
        :class="{ active: theme.mode === option.value }"
        :aria-label="option.label"
        :aria-pressed="theme.mode === option.value"
        :data-index="index"
        @click="theme.setMode(option.value)"
      >
        <el-icon><component :is="option.icon" /></el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Monitor, Moon, Sunny } from '@element-plus/icons-vue'
import { useThemeStore, type ThemeMode } from '@/stores/theme'

const theme = useThemeStore()

const options: { value: ThemeMode; label: string; icon: unknown }[] = [
  { value: 'light', label: '亮色主题', icon: Sunny },
  { value: 'dark', label: '暗色主题', icon: Moon },
  { value: 'auto', label: '跟随系统', icon: Monitor },
]

const activeIndex = computed(() => {
  const index = options.findIndex((option) => option.value === theme.mode)
  return index < 0 ? 2 : index
})
</script>

<style scoped>
.theme-toggle {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding: 3px;
  border-radius: var(--sc-radius-full);
  background: var(--sc-hover);
  border: 1px solid var(--sc-border);
}

/* 滑动的选中指示块：比"切换按钮换图标"更能表达"三态"这件事 */
.thumb {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 32px;
  height: 28px;
  border-radius: var(--sc-radius-full);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow-sm);
  transition: transform var(--sc-dur) var(--sc-ease);
}

.opt {
  position: relative;
  z-index: 1;
  width: 32px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--sc-text-3);
  border-radius: var(--sc-radius-full);
  cursor: pointer;
  font-size: 15px;
  transition: color var(--sc-dur) var(--sc-ease);
}

.opt:hover {
  color: var(--sc-text);
}

.opt.active {
  color: var(--sc-brand);
}
</style>
