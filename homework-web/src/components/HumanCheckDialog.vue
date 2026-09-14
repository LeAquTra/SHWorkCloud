<template>
  <el-dialog
    :model-value="store.visible"
    :title="`人机验证 · ${store.scopeLabel}`"
    width="min(560px, 94vw)"
    :close-on-click-modal="false"
    append-to-body
    destroy-on-close
    class="human-check-dialog"
    @close="store.resolveCancelled()"
  >
    <p class="tip">
      <el-icon><Lock /></el-icon>
      <span>{{ tip }}</span>
    </p>

    <!--
      ImageCaptcha 自己会在挂载时取一道新题；配合 destroy-on-close，
      每次打开弹窗都是一道新题（用过一次的题不该再出现）。
    -->
    <ImageCaptcha @update:pass-token="onPassToken" />

    <template #footer>
      <span class="foot-hint">验证通过后会自动继续刚才的操作</span>
      <el-button @click="store.resolveCancelled()">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Lock } from '@element-plus/icons-vue'
import ImageCaptcha from '@/components/ImageCaptcha.vue'
import { useHumanCheckStore, type HumanCheckScope } from '@/stores/humanCheck'

/**
 * 人机验证弹窗。
 *
 * <p>挂在根组件上（App.vue）而不是各个页面里：登录页、注册弹窗、文件列表都要用它，
 * 而且**同一时刻只允许有一个** —— 一次上传会并发申请多个凭证，
 * 若每处各弹一个，用户会被弹窗淹没。真正的"合并 + 复用"逻辑在
 * {@link useHumanCheckStore.ensure}，这里只负责界面。
 */

const store = useHumanCheckStore()

const TIPS: Record<HumanCheckScope, string> = {
  login: '为拦截脚本撞库，登录前需要证明你是人。',
  register: '为防止脚本批量注册，发送邮箱验证码前需要完成一次验证。',
  upload: '为防止脚本滥用存储，上传前需要完成一次验证；通过后一段时间内不必重复验证。',
}

const tip = computed(() => (store.scope ? TIPS[store.scope] : '请完成下面的验证'))

/**
 * 验证通过。
 *
 * <p>延迟一点点再关闭：ImageCaptcha 会在图片上盖一层"已通过"的对勾遮罩，
 * 立刻关掉的话用户根本看不到自己成功了，会怀疑没生效。
 */
function onPassToken(token: string) {
  if (!token) {
    return
  }
  window.setTimeout(() => store.resolvePassed(token), 400)
}
</script>

<style scoped>
.tip {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sc-text-2);
}

.tip .el-icon {
  color: var(--sc-brand);
  margin-top: 3px;
}

.foot-hint {
  float: left;
  font-size: 11.5px;
  color: var(--sc-text-3);
  line-height: 32px;
}
</style>
