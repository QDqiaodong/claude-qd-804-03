<template>
  <el-popover
    v-model:visible="panelVisible"
    placement="bottom-end"
    :width="430"
    trigger="click"
    popper-class="fn-pop"
    @show="onShow"
  >
    <template #reference>
      <button class="fn-bell" :class="{ shaking: unreadCount > 0 }" title="操作失败通知">
        <span class="fn-bell-ico">🔔</span>
        <span v-if="unreadCount > 0" class="fn-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
      </button>
    </template>

    <div class="fn-panel">
      <div class="fn-head">
        <span>操作失败通知</span>
        <span class="fn-head-sub" v-if="person">发给：{{ person }}</span>
      </div>

      <div v-if="!person" class="fn-login">
        请先在顶部选择「当前值班员」，再查看发给本人的失败通知。
      </div>

      <div v-else-if="loading" class="fn-empty">加载中…</div>
      <div v-else-if="!notices.length" class="fn-empty">暂无操作失败通知</div>

      <div v-else class="fn-list">
        <div
          v-for="n in notices"
          :key="n.id"
          class="fn-item"
          :class="{ unread: n.unread, resolved: n.resolved }"
        >
          <div class="fn-item-top">
            <el-tag size="small" :type="n.resolved ? 'success' : 'danger'" effect="plain">
              {{ n.resolved ? '已解除' : '未解除' }}
            </el-tag>
            <span class="fn-ticket">{{ n.ticketNo }}</span>
            <span class="fn-role">{{ n.recipientRoleName }}</span>
            <span v-if="n.unread" class="fn-dot"></span>
            <span class="fn-time">{{ fmt(n.failTime) }}</span>
          </div>
          <div class="fn-step">第 {{ n.stepNo }} 步：{{ n.actionDesc }}</div>
          <div class="fn-reason">失败原因：{{ n.failReason }}（登记人 {{ n.failPerson || '—' }}）</div>
          <div class="fn-item-foot">
            <span v-if="n.readTime" class="fn-read-ok">
              本人已确认 {{ fmt(n.readTime) }}
            </span>
            <span v-else class="fn-read-no">本人尚未确认</span>
            <span v-if="n.resolved" class="fn-resolved-at">
              原步重试成功，{{ fmt(n.resolvedTime) }} 解除
            </span>
          </div>
          <div class="fn-actions">
            <el-button
              size="small"
              :type="n.unread ? 'primary' : 'default'"
              :disabled="!!n.readTime"
              @click="confirmRead(n)"
            >{{ n.readTime ? '已确认' : '确认已读' }}</el-button>
            <el-button
              size="small"
              :type="n.resolved ? 'default' : 'warning'"
              plain
              @click="jump(n)"
            >{{ n.resolved ? '查看票面' : '进入失败步骤' }}</el-button>
          </div>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { myFailureNotices, readFailureNotice } from '../api'
import { currentUser, noticesTick, openOperationTicket } from '../session'

const person = computed(() => currentUser.value)
const notices = ref([])
const loading = ref(false)
const panelVisible = ref(false)

const unreadCount = computed(() => notices.value.filter((n) => n.unread).length)

function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : ''
}

async function load() {
  if (!person.value) {
    notices.value = []
    return
  }
  loading.value = true
  try {
    const res = await myFailureNotices(person.value)
    // 状态一律以服务端落库为准：刷新页面 / 换设备登录后已读与解除完全一致
    notices.value = res.data?.notices || []
  } catch (e) {
    ElMessage.error(`通知加载失败：${e.message}`)
  } finally {
    loading.value = false
  }
}

function onShow() {
  load()
}

async function confirmRead(n) {
  try {
    const res = await readFailureNotice(n.id, person.value)
    const idx = notices.value.findIndex((i) => i.id === n.id)
    if (idx >= 0) notices.value[idx] = res.data
    ElMessage.success('已确认，仅记录你本人的已读，不影响对方')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function jump(n) {
  panelVisible.value = false
  // 进入操作票并定位到失败步骤；已解除通知也定位到该步（此时步骤已是成功状态）
  openOperationTicket(n.ticketId, n.issueDate, n.stepNo)
}

watch(person, () => load(), { immediate: true })
watch(noticesTick, () => load())

// 多终端协作：铃铛收起时也轮询，对方重试成功 / 自己在别处确认后红点实时变化
const timer = setInterval(() => {
  if (!panelVisible.value) load()
}, 8000)
onBeforeUnmount(() => clearInterval(timer))

defineExpose({ reload: load })
</script>

<style scoped>
.fn-bell {
  position: relative;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.45);
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  cursor: pointer;
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.fn-bell:hover {
  background: rgba(255, 255, 255, 0.28);
}

.fn-bell.shaking .fn-bell-ico {
  animation: fn-shake 1.4s ease-in-out infinite;
  transform-origin: 50% 12%;
}

@keyframes fn-shake {
  0%, 45%, 100% { transform: rotate(0); }
  55% { transform: rotate(-16deg); }
  65% { transform: rotate(14deg); }
  75% { transform: rotate(-9deg); }
  85% { transform: rotate(6deg); }
}

.fn-badge {
  position: absolute;
  top: -4px;
  right: -5px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: #d50000;
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  line-height: 17px;
  border: 1.5px solid #bf360c;
}

.fn-panel {
  margin: -12px;
}

.fn-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 14px;
  font-weight: 700;
  padding: 12px 14px 8px;
  color: var(--ops-ink);
}

.fn-head-sub {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--ops-grey);
}

.fn-login,
.fn-empty {
  padding: 26px 16px;
  text-align: center;
  color: #9e9e9e;
  font-size: 12.5px;
}

.fn-list {
  max-height: 430px;
  overflow-y: auto;
  padding: 0 10px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fn-item {
  border: 1px solid var(--ops-line);
  border-left: 4px solid #c62828;
  border-radius: 8px;
  padding: 8px 10px;
  background: #fff;
}

.fn-item.resolved {
  border-left-color: var(--ops-green);
  background: #f6fbf4;
}

.fn-item.unread {
  background: #fdecea;
}

.fn-item-top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.fn-ticket {
  font-weight: 700;
  color: var(--ops-orange-dark);
}

.fn-role {
  color: var(--ops-grey);
}

.fn-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d50000;
}

.fn-time {
  margin-left: auto;
  color: var(--ops-grey);
  font-size: 11px;
}

.fn-step {
  font-size: 12.5px;
  margin-top: 5px;
  color: var(--ops-ink);
}

.fn-reason {
  font-size: 11.5px;
  color: #c62828;
  margin-top: 3px;
  line-height: 1.45;
}

.fn-item-foot {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  font-size: 11px;
}

.fn-read-no {
  color: #c62828;
  font-weight: 700;
}

.fn-read-ok {
  color: var(--ops-green);
}

.fn-resolved-at {
  color: var(--ops-grey);
}

.fn-actions {
  margin-top: 7px;
  display: flex;
  gap: 8px;
}
</style>
