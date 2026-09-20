<template>
  <span class="user-avatar-wrap" :style="{ width: `${size}px`, height: `${size}px` }">
    <el-avatar
      :size="size"
      :src="failed ? undefined : card.avatarUrl || undefined"
      class="user-avatar"
      @error="failed = true"
    >
      {{ initial }}
    </el-avatar>
    <!--
      未读角标：用 Element Plus 的 badge 而不是自己写一个圆点，
      是为了跟顶栏其它角标（公告铃铛）保持同一套观感。
    -->
    <el-badge v-if="badge > 0" :value="badge > 99 ? '99+' : badge" class="badge" />
  </span>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UserCard } from '@/types/api'

/**
 * 用户头像（列表/聊天气泡/会话栏通用）。
 *
 * <p>两个必须处理的细节：
 * <ol>
 *   <li><b>签名失效回退</b>：{@code avatarUrl} 是 1 小时有效的 OSS 签名地址，
 *       过期或对象被清理后会裂图。这里 {@code @error} 后回退成"首字母文字头像"，
 *       比一个裂图方块好看得多（顶栏与文件缩略图也是同一套做法）；</li>
 *   <li><b>换头像要重新给机会</b>：只监听 {@code avatarVersion}，
 *       换了头像就把失败标记清掉，否则用户清空再上传后仍一直看到文字头像。</li>
 * </ol>
 */
const props = withDefaults(
  defineProps<{
    card: UserCard
    size?: number
    /** 未读角标数；0 或省略时不显示 */
    badge?: number
  }>(),
  { size: 36, badge: 0 },
)

const failed = ref(false)

watch(
  () => props.card.avatarVersion,
  () => {
    failed.value = false
  },
)

const initial = computed(() => {
  const name = (props.card.displayName || props.card.username || '?').trim()
  return name.slice(0, 1).toUpperCase()
})
</script>

<style scoped>
.user-avatar-wrap {
  position: relative;
  display: inline-block;
  flex: 0 0 auto;
}

.user-avatar {
  background-image: var(--sc-gradient);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}

.badge {
  position: absolute;
  top: -2px;
  right: -2px;
}

/* Element Plus 的 badge 默认是"内容 + 角标"的横向布局，
   这里角标要浮在头像右上角，所以把它压成绝对定位 */
.badge :deep(.el-badge__content) {
  border: none;
  transform: translateY(0) translateX(0);
  font-size: 10px;
  height: 16px;
  line-height: 16px;
  padding: 0 5px;
}
</style>
