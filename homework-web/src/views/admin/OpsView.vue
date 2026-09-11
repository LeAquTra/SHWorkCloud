<template>
  <div class="page">
    <!-- 当前配置 -->
    <el-card shadow="never">
      <template #header><span>当前关键配置</span></template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="自助注册">
          <el-tag :type="config.registerEnabled ? 'success' : 'info'" size="small">
            {{ config.registerEnabled ? '已开启' : '已关闭（机房推荐）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="导入策略">{{ config.importStrategy }}</el-descriptions-item>
        <el-descriptions-item label="默认配额">
          {{ formatSize(Number(config.defaultQuotaBytes || 0)) }}
        </el-descriptions-item>
        <el-descriptions-item label="单文件上限">
          {{ formatSize(Number(config.maxFileSizeBytes || 0)) }}
        </el-descriptions-item>
        <el-descriptions-item label="回收站保留">
          {{ config.recycleRetentionDays }} 天
        </el-descriptions-item>
        <el-descriptions-item label="信任代理">
          <el-tag :type="config.trustProxy ? 'warning' : 'success'" size="small">
            {{ config.trustProxy ? '是（必须部署在 Nginx 之后）' : '否（直连）' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div class="inner-net">
        内网豁免网段（这些来源不做 IP 维度限流，避免机房同出口 IP 被整体限流）：
        <code>{{ (config.internalNetworks as string[] | undefined)?.join(', ') || '-' }}</code>
      </div>
    </el-card>

    <!-- 孤儿对象 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            孤儿对象
            <el-tag :type="orphanCount > 0 ? 'warning' : 'success'" size="small" class="ml8">
              {{ orphanCount }} 个待清理
            </el-tag>
          </span>
          <div>
            <el-button @click="loadOrphans">刷新</el-button>
            <el-button type="primary" :disabled="orphanCount === 0" @click="cleanOrphans">
              清理
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="什么是孤儿对象"
        description="服务端已签发上传凭证、但超过 24 小时仍未完成登记的对象。常见于学生传到一半下课关机。它们不占用户配额、在网盘里也看不见，但会一直产生 OSS 存储费用。"
      />

      <el-table v-if="orphans.length" :data="orphans" max-height="260">
        <el-table-column prop="sessionId" label="会话ID" width="100" />
        <el-table-column prop="userId" label="用户ID" width="100" />
        <el-table-column prop="objectKey" label="ObjectKey" min-width="280" show-overflow-tooltip />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="没有待清理的孤儿对象" />
    </el-card>

    <!-- 维护操作 -->
    <el-card shadow="never">
      <template #header><span>维护操作</span></template>

      <div class="op">
        <div class="op-desc">
          <strong>重算物化路径</strong>
          <p>按 parent_id 重算全表 path。会全表加载并逐条更新，请在维护窗口执行。</p>
        </div>
        <el-button :loading="working === 'paths'" @click="rebuildPaths">执行</el-button>
      </div>

      <el-divider />

      <div class="op">
        <div class="op-desc">
          <strong>全量容量对账</strong>
          <p>按 file_entry 重算每个用户的 used_storage 缓存值，修复漂移。</p>
        </div>
        <el-button :loading="working === 'reconcile'" @click="reconcile">执行</el-button>
      </div>

      <el-divider />

      <div class="op">
        <div class="op-desc">
          <strong>机房清场</strong>
          <p>按登录 IP 前缀批量踢出会话。下课后用它清掉某个机房仍登录着的账号。</p>
        </div>
        <div class="op-action">
          <el-input v-model="ipPrefix" placeholder="如 192.168.1." class="w200" />
          <el-button :loading="working === 'flush'" @click="flushSessions">执行</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import type { OrphanVO } from '@/types/api'
import { formatSize, formatTime } from '@/utils/format'

const config = ref<Record<string, unknown>>({})
const orphans = ref<OrphanVO[]>([])
const orphanCount = ref(0)
const ipPrefix = ref('')
const working = ref<'' | 'paths' | 'reconcile' | 'flush'>('')

async function loadConfig() {
  try {
    config.value = await adminApi.configSummary()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载配置失败')
  }
}

async function loadOrphans() {
  try {
    orphanCount.value = await adminApi.orphanCount()
    orphans.value = await adminApi.orphans(200)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载孤儿对象失败')
  }
}

async function cleanOrphans() {
  try {
    await ElMessageBox.confirm(
      '将删除这些 OSS 对象并标记会话已放弃。已建立索引的对象不会被误删。',
      '清理孤儿对象',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const count = await adminApi.cleanOrphans(500)
    ElMessage.success(`已清理 ${count} 个对象`)
    await loadOrphans()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '清理失败')
  }
}

async function rebuildPaths() {
  working.value = 'paths'
  try {
    const fixed = await adminApi.rebuildPaths()
    ElMessage.success(`重算完成，修正 ${fixed} 条记录`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '执行失败')
  } finally {
    working.value = ''
  }
}

async function reconcile() {
  working.value = 'reconcile'
  try {
    const result = await adminApi.reconcileStorage()
    ElMessage.success(
      `对账完成：检查 ${result.checkedUsers} 人，修正 ${result.correctedUsers} 人，最大偏差 ${formatSize(result.maxDiffBytes)}`,
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '执行失败')
  } finally {
    working.value = ''
  }
}

async function flushSessions() {
  if (ipPrefix.value.trim().length < 5) {
    ElMessage.warning('请填写至少 5 个字符的 IP 前缀，如 192.168.1.')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将踢出登录 IP 以「${ipPrefix.value}」开头的所有会话。`,
      '机房清场',
      { type: 'warning' },
    )
  } catch {
    return
  }
  working.value = 'flush'
  try {
    const result = await adminApi.flushSessions(ipPrefix.value.trim())
    ElMessage.success(`已踢出 ${result.kickedCount} 个会话`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '执行失败')
  } finally {
    working.value = ''
  }
}

onMounted(async () => {
  await Promise.all([loadConfig(), loadOrphans()])
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ml8 {
  margin-left: 8px;
}

.tip {
  margin-bottom: 12px;
}

.inner-net {
  margin-top: 12px;
  font-size: 12px;
  color: #606266;
}

.inner-net code {
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 3px;
}

.op {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.op-desc strong {
  font-size: 14px;
}

.op-desc p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #909399;
}

.op-action {
  display: flex;
  gap: 8px;
}

.w200 {
  width: 200px;
}
</style>
