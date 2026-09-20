<template>
  <article class="post" :class="{ compact }">
    <UserAvatar :card="post.author" :size="compact ? 34 : 42" @click="openAuthor" />

    <div class="body">
      <header class="head">
        <button type="button" class="author" @click="openAuthor">
          {{ post.author.displayName }}
        </button>
        <el-tag v-if="post.author.role > 0" size="small" effect="light" type="warning">
          {{ ROLE_LABELS[post.author.role] || '用户' }}
        </el-tag>
        <span v-if="post.author.className" class="sc-muted class-name">
          {{ post.author.className }}
        </span>
        <span class="sc-muted time sc-tabular">{{ formatTime(post.createTime) }}</span>

        <!--
          状态标签只在"不是已通过"时显示：时间线里全是已通过的，
          每条都挂一个"已通过"纯属噪音；而待审/被拒对作者是必须看到的信号。
        -->
        <el-tag
          v-if="post.status !== POST_STATUS_APPROVED"
          size="small"
          effect="dark"
          :type="statusTagType"
        >
          {{ POST_STATUS_LABELS[post.status] || '未知' }}
        </el-tag>
      </header>

      <!--
        ⚠️ 正文渲染：**逐段插值，绝不 v-html**。
        segments 由服务端（LinkSegmenter）切好，前端的职责只有"把 link 段画成 <a>"。
        这样正文里手写的 <script> / <a href> 永远是文字，不会变成标记。
      -->
      <div class="content">
        <template v-for="(segment, index) in post.segments" :key="index">
          <a
            v-if="segment.type === 'link' && segment.href"
            :href="segment.href"
            class="link"
            target="_blank"
            rel="noopener noreferrer nofollow"
          >{{ segment.text }}</a>
          <template v-else>{{ segment.text }}</template>
        </template>
      </div>

      <!-- 纯文本兜底：万一段落字段缺失（老接口/异常数据），至少把原文显示出来 -->
      <div v-if="!post.segments || !post.segments.length" class="content">{{ post.content }}</div>

      <div v-if="post.linkCount > 0" class="link-notice sc-muted">
        <el-icon><Link /></el-icon>
        <span>含 {{ post.linkCount }} 个外部链接，请注意甄别来源</span>
      </div>

      <el-alert
        v-if="post.status === POST_STATUS_REJECTED && post.rejectReason"
        class="reject"
        type="error"
        :closable="false"
        show-icon
        :title="`未通过：${post.rejectReason}`"
      />

      <footer v-if="$slots.actions" class="foot">
        <slot name="actions" />
      </footer>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Link } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import {
  POST_STATUS_LABELS,
  POST_STATUS_PENDING,
  POST_STATUS_REJECTED,
  ROLE_LABELS,
  type PostVO,
} from '@/types/api'
import { formatTime } from '@/utils/format'

/**
 * 一条帖子。
 *
 * <p>抽成独立组件有两个理由：
 * <ol>
 *   <li>时间线、我的帖子、审核队列、他人主页四处都要渲染同一种卡片，
 *       复制三份必然会出现"某处忘了防 XSS"或"某处没做链接分段"；</li>
 *   <li>正文的渲染规则（分段 + 插值 + 不 v-html）只应该有一处实现。</li>
 * </ol>
 */
const props = withDefaults(defineProps<{ post: PostVO; compact?: boolean }>(), { compact: false })

const router = useRouter()

const statusTagType = computed(() => {
  switch (props.post.status) {
    case POST_STATUS_PENDING:
      return 'info'
    case POST_STATUS_REJECTED:
      return 'danger'
    default:
      return 'success'
  }
})

function openAuthor() {
  // 已注销用户没有主页可看（服务端会用占位名片把 username 置成"已注销"）
  if (props.post.author?.userId && props.post.author.username !== '已注销') {
    void router.push(`/user/${props.post.author.userId}`)
  }
}
</script>

<style scoped>
.post {
  display: flex;
  gap: 12px;
  padding: 16px 18px;
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  transition: var(--sc-transition);
}

.post:hover {
  border-color: var(--sc-border-2);
}

.post.compact {
  padding: 12px 14px;
}

.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.author {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: 14px;
  font-weight: 620;
  color: var(--sc-text);
  cursor: pointer;
  transition: var(--sc-transition);
}

.author:hover {
  color: var(--sc-brand);
}

.class-name,
.time {
  font-size: 11.5px;
}

.time {
  margin-left: auto;
}

.content {
  /* 保留用户输入的换行与空格，但不解析任何标记 */
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.7;
  color: var(--sc-text);
}

.content .link {
  color: var(--sc-brand);
  text-decoration: none;
  border-bottom: 1px solid var(--sc-brand-ring);
  word-break: break-all;
  transition: var(--sc-transition);
}

.content .link:hover {
  border-bottom-color: var(--sc-brand);
}

.link-notice {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
}

.reject {
  margin: 0;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.small {
  font-size: 11.5px;
}
</style>
