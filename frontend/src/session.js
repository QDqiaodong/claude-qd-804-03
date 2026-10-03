import { ref } from 'vue'
import { currentPath, go } from './router'

/**
 * 跨页面的轻量会话与跳转状态：
 *
 * - currentUser：当前值班员（演示环境无登录体系，在顶部切换，存 localStorage）。
 *   通知按「发给谁」取数，换设备/刷新后选择同一姓名即看到同一批通知，
 *   已读/解除状态全部以服务端落库为准，不做本地状态推断。
 * - openTicketRequest：从站内通知进入操作票时携带票号/失败步骤，
 *   OperationView 监听后打开对应票并滚动定位到失败步骤。
 */
const USER_KEY = 'substation-current-duty'

export const currentUser = ref(localStorage.getItem(USER_KEY) || '')

export function setCurrentUser(name) {
  currentUser.value = name || ''
  if (name) {
    localStorage.setItem(USER_KEY, name)
  } else {
    localStorage.removeItem(USER_KEY)
  }
}

export const openTicketRequest = ref(null)

/** 通知跳入时要求顶部时间轴切换到的票面业务日期 */
export const navigateDate = ref(null)

/** 失败登记/重试后自增，驱动铃铛立即重取（不等轮询） */
export const noticesTick = ref(0)

export function pokeNotices() {
  noticesTick.value += 1
}

let seq = 0

/** 从通知跳入操作票：切页面 + 请求打开指定票并定位失败步骤 */
export function openOperationTicket(ticketId, ticketIssueDate, focusStepNo) {
  openTicketRequest.value = {
    nonce: ++seq,
    ticketId,
    issueDate: ticketIssueDate || null,
    focusStepNo: focusStepNo || null
  }
  if (ticketIssueDate) {
    navigateDate.value = { nonce: seq, date: ticketIssueDate }
  }
  if (currentPath.value !== '/operations') {
    go('/operations')
  }
}
