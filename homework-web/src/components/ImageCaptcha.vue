<template>
  <div class="captcha">
    <div class="captcha-head">
      <span class="label">
        <el-icon><MagicStick /></el-icon>
        图片验证码
        <el-tag v-if="verified" size="small" type="success" effect="light">已通过</el-tag>
      </span>
      <el-button link type="primary" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>{{ verified ? '重新验证' : '换一题' }}</span>
      </el-button>
    </div>

    <el-skeleton v-if="loading && !data" :rows="2" animated />

    <template v-else-if="data">
      <!-- 题干：type=3 点选时 prompts 是要依次点击的目标 -->
      <p v-if="data.type === 3 && data.prompts.length" class="prompt">
        请依次点击：<strong>{{ data.prompts.join(' → ') }}</strong>
        <span class="muted">（已点 {{ clicks.length }}/{{ data.prompts.length }}）</span>
      </p>

      <div class="captcha-body" :class="{ clickable: data.type === 3 }">
        <div ref="stageRef" class="stage" @click="onStageClick">
          <img :src="data.imageUrl" alt="验证码" draggable="false" @load="onImageLoad" />
          <span
            v-for="(point, index) in clicks"
            :key="index"
            class="pin"
            :style="{ left: `${point.x}%`, top: `${point.y}%` }"
          >
            {{ index + 1 }}
          </span>
          <div v-if="verified" class="mask">
            <el-icon><CircleCheckFilled /></el-icon>
          </div>
        </div>

        <div class="controls">
          <!-- type=1：字符输入 -->
          <template v-if="data.type === 1">
            <el-input
              v-model="answer"
              placeholder="输入图中字符"
              maxlength="12"
              :disabled="verified"
              @keyup.enter="verify"
            />
            <el-button type="primary" :disabled="verified || !answer" :loading="verifying" @click="verify">
              验证
            </el-button>
          </template>

          <!-- type=2：单选 -->
          <template v-else-if="data.type === 2">
            <el-radio-group v-model="answer" :disabled="verified" class="options">
              <el-radio v-for="(option, index) in data.prompts" :key="index" :value="option">
                {{ option }}
              </el-radio>
            </el-radio-group>
            <el-button type="primary" :disabled="verified || !answer" :loading="verifying" @click="verify">
              验证
            </el-button>
          </template>

          <!-- type=3：点选。坐标在这里按显示尺寸换算回原图像素后提交 -->
          <template v-else>
            <el-button link :disabled="verified || clicks.length === 0" @click="clicks = []">
              清除已点位置
            </el-button>
            <el-button
              type="primary"
              :disabled="verified || clicks.length === 0"
              :loading="verifying"
              @click="verify"
            >
              提交
            </el-button>
          </template>
        </div>
      </div>

      <el-alert
        v-if="error"
        class="captcha-error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />
    </template>

    <el-empty v-else :description="error || '验证码加载失败'">
      <!--
        ⚠️ 必须把**真实原因**显示在这里。以前这里写死"验证码加载失败"，
        而具体错误（"题库为空"/"服务器内部错误"）只放在 data 分支的 alert 里 ——
        加载失败时 data 是 null，那个 alert 根本不会渲染，
        于是不管什么原因用户都只看到一句没有信息量的"验证码加载失败"。
      -->
      <template #description>
        <p class="empty-title">{{ error || '验证码加载失败' }}</p>
        <p v-if="poolEmpty" class="empty-hint">
          后台题库里没有启用中的题目，请联系管理员在「验证码题库」里上传题目。
        </p>
        <p v-else class="empty-hint">可以点下面重试；若一直失败，请把这句话告诉管理员。</p>
      </template>
      <el-button type="primary" :loading="loading" @click="load">重试</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CircleCheckFilled, MagicStick, Refresh } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { ApiError, CODE } from '@/api/http'
import type { CaptchaVO } from '@/types/api'

/**
 * 图片验证码。
 *
 * <p>三种题型共用一套组件，因为它们只差"答案怎么收集"：
 *   type=1 输入字符 / type=2 单选 / type=3 按提示点选。
 * 点选题的坐标必须换算回**原图像素**再提交 —— 图片在页面上会被缩放，
 * 直接提交页面坐标必然验证失败。
 */

const emit = defineEmits<{ 'update:passToken': [token: string] }>()

const data = ref<CaptchaVO | null>(null)
const loading = ref(false)
const verifying = ref(false)
const answer = ref('')
const clicks = ref<{ x: number; y: number }[]>([])
const error = ref('')
const passToken = ref('')
const verified = computed(() => passToken.value !== '')

/** 题库为空：这不是"网络不好"，要明确告诉用户找管理员上传题目 */
const poolEmpty = ref(false)

const stageRef = ref<HTMLElement>()

async function load() {
  loading.value = true
  error.value = ''
  poolEmpty.value = false
  answer.value = ''
  clicks.value = []
  passToken.value = ''
  emit('update:passToken', '')
  try {
    data.value = await authApi.captcha()
  } catch (err) {
    data.value = null
    if (err instanceof ApiError) {
      poolEmpty.value = err.code === CODE.CAPTCHA_POOL_EMPTY
      error.value = poolEmpty.value ? '验证码题库为空' : err.message
    } else {
      // 非业务错误（网络中断、502、超时…）：axios 的 message 是英文的，
      // 直接抛给用户没有意义，但也不能吞掉 —— 至少让他知道是网络侧问题
      error.value = '验证码加载失败，请检查网络后重试'
    }
  } finally {
    loading.value = false
  }
}

function onImageLoad() {
  // 图片真正渲染出来后才知道尺寸，无需额外处理；这里保留钩子以便将来做骨架过渡
}

/** 点选：记录相对显示框的百分比，提交时再乘原图宽高 */
function onStageClick(event: MouseEvent) {
  if (!data.value || data.value.type !== 3 || verified.value) {
    return
  }
  const stage = stageRef.value
  if (!stage) {
    return
  }
  const rect = stage.getBoundingClientRect()
  const x = ((event.clientX - rect.left) / rect.width) * 100
  const y = ((event.clientY - rect.top) / rect.height) * 100
  if (x < 0 || x > 100 || y < 0 || y > 100) {
    return
  }
  const limit = data.value.prompts.length || 3
  if (clicks.value.length >= limit) {
    clicks.value = []
  }
  clicks.value.push({ x, y })
}

async function verify() {
  if (!data.value || verified.value) {
    return
  }
  verifying.value = true
  error.value = ''
  try {
    const body: { captchaId: string; answer?: string; clicks?: { x: number; y: number }[] } = {
      captchaId: data.value.captchaId,
    }
    if (data.value.type === 3) {
      // 页面百分比 → 原图像素
      body.clicks = clicks.value.map((point) => ({
        x: Math.round((point.x / 100) * data.value!.width),
        y: Math.round((point.y / 100) * data.value!.height),
      }))
    } else {
      body.answer = answer.value.trim()
    }

    const result = await authApi.verifyCaptcha(body)
    passToken.value = result.captchaPassToken
    emit('update:passToken', result.captchaPassToken)
  } catch (err) {
    const message = err instanceof ApiError
      ? err.code === CODE.CAPTCHA_POOL_EMPTY
        ? '验证码题库为空，请联系管理员上传题目'
        : err.message
      : '验证失败，请重试'
    // 答错后换一题，避免学生反复猜同一张图；load() 会清空 error，所以最后再写回
    await load()
    error.value = message
  } finally {
    verifying.value = false
  }
}

onMounted(load)

defineExpose({ refresh: load })
</script>

<style scoped>
.captcha {
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  padding: 14px;
  background: var(--sc-surface-2);
}

.captcha-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}

.prompt {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--sc-text-2);
}

.muted {
  color: var(--sc-text-3);
}

.stage {
  position: relative;
  display: inline-block;
  border-radius: var(--sc-radius-sm);
  overflow: hidden;
  border: 1px solid var(--sc-border);
  background: var(--sc-surface);
  max-width: 100%;
  user-select: none;
}

.stage img {
  display: block;
  max-width: 100%;
  height: auto;
}

.captcha-body.clickable .stage {
  cursor: crosshair;
}

.pin {
  position: absolute;
  width: 22px;
  height: 22px;
  margin: -11px 0 0 -11px;
  border-radius: var(--sc-radius-full);
  background: var(--sc-gradient);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.6);
  pointer-events: none;
}

.mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  color: var(--sc-success);
  background: color-mix(in srgb, var(--sc-surface) 72%, transparent);
  backdrop-filter: blur(2px);
}

.controls {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.options {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.captcha-error {
  margin-top: 10px;
}

.empty-title {
  margin: 0;
  font-size: 13.5px;
  color: var(--sc-text);
  word-break: break-word;
}

.empty-hint {
  margin: 6px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--sc-text-3);
}
</style>
