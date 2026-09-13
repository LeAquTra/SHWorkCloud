<template>
  <span
    class="glyph"
    :class="[`t-${tone}`, `v-${variant}`]"
    :style="{ width: `${size}px`, height: `${size}px`, borderRadius: `${radius}px` }"
  >
    <el-icon :size="iconSize"><component :is="icon" /></el-icon>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  Box,
  DataBoard,
  Document,
  DocumentCopy,
  Files,
  Folder,
  Headset,
  Notebook,
  Picture,
  Tickets,
  VideoCamera,
} from '@element-plus/icons-vue'
import { fileTone, type FileTone } from '@/utils/format'

/**
 * 文件类型图标。
 *
 * 用一个带渐变的小方块承载图标，而不是 emoji：emoji 在不同系统上字形、
 * 基线、色彩都不可控，是做不出统一观感的主要原因之一。
 */
const props = withDefaults(
  defineProps<{
    tone: FileTone
    size?: number
    /** tile = 渐变实心方块；plain = 只有图标着色（用于树、面包屑等窄场景） */
    variant?: 'tile' | 'plain'
  }>(),
  { size: 34, variant: 'tile' },
)

const ICONS: Record<FileTone, unknown> = {
  folder: Folder,
  image: Picture,
  video: VideoCamera,
  audio: Headset,
  pdf: Document,
  doc: DocumentCopy,
  sheet: Tickets,
  slide: DataBoard,
  archive: Box,
  text: Notebook,
  other: Files,
}

const icon = computed(() => ICONS[props.tone] || Files)
const iconSize = computed(() => (props.variant === 'plain' ? props.size : Math.round(props.size * 0.52)))
const radius = computed(() => (props.variant === 'plain' ? 0 : Math.max(6, Math.round(props.size * 0.3))))
</script>

<style scoped>
.glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  color: #fff;
  background-image: linear-gradient(135deg, var(--g1), var(--g2));
  box-shadow: 0 2px 6px -2px var(--g-shadow);
  transition: transform var(--sc-dur) var(--sc-ease), box-shadow var(--sc-dur) var(--sc-ease);
}

.glyph.v-plain {
  background: none;
  box-shadow: none;
  color: var(--g1);
}

.t-folder {
  --g1: #6366f1;
  --g2: #8b5cf6;
  --g-shadow: rgba(99, 102, 241, 0.5);
}

.t-image {
  --g1: #a855f7;
  --g2: #ec4899;
  --g-shadow: rgba(168, 85, 247, 0.5);
}

.t-video {
  --g1: #f43f5e;
  --g2: #fb7185;
  --g-shadow: rgba(244, 63, 94, 0.45);
}

.t-audio {
  --g1: #06b6d4;
  --g2: #22d3ee;
  --g-shadow: rgba(6, 182, 212, 0.45);
}

.t-pdf {
  --g1: #ef4444;
  --g2: #f97316;
  --g-shadow: rgba(239, 68, 68, 0.45);
}

.t-doc {
  --g1: #3b82f6;
  --g2: #60a5fa;
  --g-shadow: rgba(59, 130, 246, 0.45);
}

.t-sheet {
  --g1: #10b981;
  --g2: #34d399;
  --g-shadow: rgba(16, 185, 129, 0.45);
}

.t-slide {
  --g1: #f59e0b;
  --g2: #fbbf24;
  --g-shadow: rgba(245, 158, 11, 0.45);
}

.t-archive {
  --g1: #64748b;
  --g2: #94a3b8;
  --g-shadow: rgba(100, 116, 139, 0.4);
}

.t-text {
  --g1: #52525b;
  --g2: #a1a1aa;
  --g-shadow: rgba(82, 82, 91, 0.35);
}

.t-other {
  --g1: #94a3b8;
  --g2: #cbd5e1;
  --g-shadow: rgba(148, 163, 184, 0.4);
}
</style>
