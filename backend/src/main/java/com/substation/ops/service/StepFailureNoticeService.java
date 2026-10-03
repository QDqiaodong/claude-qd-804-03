package com.substation.ops.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.substation.ops.common.BizException;
import com.substation.ops.domain.OperationStep;
import com.substation.ops.domain.OperationTicket;
import com.substation.ops.domain.StepFailureNotice;
import com.substation.ops.repository.StepFailureNoticeRepository;

/**
 * 操作失败站内通知：
 *
 * - 失败记录落库时（与步骤登记同一事务、同一票行锁内），向本票调度员、监护人
 *   各生成一条未解除通知，内容含票号、步骤、失败原因与发生时间；
 * - 两类接收人各自确认已读，只更新自己那条记录，彼此的已读状态互不覆盖；
 * - 通知未读不影响原步重试；页面按未解除 + 未确认口径持续标出角色；
 * - 原步重试成功后通知转「已解除」，双方已读时间原样保留，记录不可删除。
 */
@Service
public class StepFailureNoticeService {

    private final StepFailureNoticeRepository noticeRepository;

    public StepFailureNoticeService(StepFailureNoticeRepository noticeRepository) {
        this.noticeRepository = noticeRepository;
    }

    /**
     * 失败落库：刷新/生成调度员与监护人两条未解除通知。
     * 同一票同一步再次登记失败时复用原通知（更新原因与发生时间，清空已读确认，
     * 新一轮失败需重新确认）。
     */
    public void publishFailure(OperationTicket ticket, OperationStep step,
                               String failPerson, String failReason, LocalDateTime failTime) {
        upsert(ticket, step, StepFailureNotice.ROLE_DISPATCHER,
                ticket.getDispatcher(), failPerson, failReason, failTime);
        upsert(ticket, step, StepFailureNotice.ROLE_GUARDIAN,
                ticket.getGuardian(), failPerson, failReason, failTime);
    }

    private void upsert(OperationTicket ticket, OperationStep step, String role, String recipient,
                        String failPerson, String failReason, LocalDateTime failTime) {
        if (recipient == null || recipient.isBlank()) {
            // 票面未指定该角色时无法定向通知
            return;
        }
        StepFailureNotice notice = noticeRepository
                .findByTicketIdAndStepNoAndStatus(ticket.getId(), step.getStepNo(),
                        StepFailureNotice.STATUS_ACTIVE).stream()
                .filter(n -> role.equals(n.getRecipientRole()))
                .findFirst()
                .orElseGet(() -> {
                    StepFailureNotice n = new StepFailureNotice();
                    n.setTicketId(ticket.getId());
                    n.setStepNo(step.getStepNo());
                    n.setRecipientRole(role);
                    n.setRecipientName(recipient);
                    n.setTicketNo(ticket.getTicketNo());
                    n.setIssueDate(ticket.getIssueDate());
                    n.setActionDesc(step.getActionDesc());
                    return n;
                });
        // 新一轮失败：接收人可能随票面变化，内容与时间刷新，已读确认全部重置
        notice.setRecipientName(recipient);
        notice.setTicketNo(ticket.getTicketNo());
        notice.setIssueDate(ticket.getIssueDate());
        notice.setActionDesc(step.getActionDesc());
        notice.setFailPerson(failPerson);
        notice.setFailReason(failReason);
        notice.setFailTime(failTime);
        notice.setReadTime(null);
        notice.setStatus(StepFailureNotice.STATUS_ACTIVE);
        notice.setResolvedTime(null);
        noticeRepository.save(notice);
    }

    /**
     * 原步重试成功：该票该步未解除通知全部转已解除，双方已读时间保留。
     */
    public void resolveRetried(Long ticketId, Integer stepNo, LocalDateTime resolvedTime) {
        for (StepFailureNotice notice : noticeRepository
                .findByTicketIdAndStepNoAndStatus(ticketId, stepNo, StepFailureNotice.STATUS_ACTIVE)) {
            notice.setStatus(StepFailureNotice.STATUS_RESOLVED);
            notice.setResolvedTime(resolvedTime);
            // 不清除 readTime：双方已读时间随记录留痕
            noticeRepository.save(notice);
        }
    }

    /**
     * 当前登录值班员的通知列表（未解除在前），点击后用于定位失败步骤。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMine(String person) {
        if (person == null || person.isBlank()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (StepFailureNotice n : noticeRepository
                .findByRecipientNameOrderByStatusAscFailTimeDescIdDesc(person.trim())) {
            result.add(toMap(n));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mineSummary(String person) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> notices = listMine(person);
        map.put("notices", notices);
        map.put("unreadCount", notices.stream().filter(n -> Boolean.TRUE.equals(n.get("unread"))).count());
        return map;
    }

    /**
     * 确认已读：只能确认发给自己的那条；幂等，重复确认保留第一次时间。
     */
    @Transactional
    public Map<String, Object> markRead(Long id, String person) {
        if (person == null || person.isBlank()) {
            throw new BizException("请先在顶部选择当前值班员再确认通知");
        }
        StepFailureNotice notice = noticeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("通知不存在"));
        if (!person.trim().equals(notice.getRecipientName())) {
            throw new BizException("该通知是发给 " + notice.getRecipientName() + " 的，不能代为确认");
        }
        if (notice.getReadTime() == null) {
            notice.setReadTime(LocalDateTime.now());
            noticeRepository.save(notice);
        }
        return toMap(notice);
    }

    /**
     * 票面详情用：按步骤号给出未解除通知的双方确认状态。
     * key = stepNo，value 含调度员/监护人是否已确认与确认时间。
     * 通知状态（未解除/已解除）与步骤状态（FAILED/DONE）在同一事务口径下产出，
     * 刷新或换设备看到的结果一致。
     */
    @Transactional(readOnly = true)
    public Map<Integer, Map<String, Object>> activeConfirmMatrix(Long ticketId) {
        Map<Integer, Map<String, Object>> matrix = new LinkedHashMap<>();
        for (StepFailureNotice n : noticeRepository
                .findByTicketIdAndStatusOrderByStepNoAscIdAsc(ticketId,
                        StepFailureNotice.STATUS_ACTIVE)) {
            Map<String, Object> cell = matrix.computeIfAbsent(n.getStepNo(), k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("stepNo", k);
                m.put("dispatcherRead", false);
                m.put("guardianRead", false);
                m.put("dispatcherReadTime", null);
                m.put("guardianReadTime", null);
                m.put("dispatcherNoticeId", null);
                m.put("guardianNoticeId", null);
                m.put("dispatcherName", null);
                m.put("guardianName", null);
                m.put("unconfirmedRoles", new ArrayList<String>());
                return m;
            });
            if (StepFailureNotice.ROLE_DISPATCHER.equals(n.getRecipientRole())) {
                cell.put("dispatcherRead", n.getReadTime() != null);
                cell.put("dispatcherReadTime", n.getReadTime());
                cell.put("dispatcherNoticeId", n.getId());
                cell.put("dispatcherName", n.getRecipientName());
            } else if (StepFailureNotice.ROLE_GUARDIAN.equals(n.getRecipientRole())) {
                cell.put("guardianRead", n.getReadTime() != null);
                cell.put("guardianReadTime", n.getReadTime());
                cell.put("guardianNoticeId", n.getId());
                cell.put("guardianName", n.getRecipientName());
            }
        }
        for (Map<String, Object> cell : matrix.values()) {
            List<String> unconfirmed = new ArrayList<>();
            if (!Boolean.TRUE.equals(cell.get("dispatcherRead"))) {
                unconfirmed.add("调度员");
            }
            if (!Boolean.TRUE.equals(cell.get("guardianRead"))) {
                unconfirmed.add("监护人");
            }
            cell.put("unconfirmedRoles", unconfirmed);
        }
        return matrix;
    }

    /**
     * 票面失败通知追溯：含已解除留痕（已读/解除时间均保留）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByTicket(Long ticketId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StepFailureNotice n : noticeRepository.findByTicketIdOrderByFailTimeDescIdDesc(ticketId)) {
            result.add(toMap(n));
        }
        return result;
    }

    /**
     * 多张票的未解除通知汇总（列表页用）：每张票给出仍有未确认通知的角色，
     * 以及未解除通知条数，未打开票面也能看到尚未确认的调度员/监护人。
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Object>> activeSummaryByTickets(List<Long> ticketIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (ticketIds == null || ticketIds.isEmpty()) {
            return result;
        }
        for (Long ticketId : ticketIds) {
            boolean dispatcherPending = false;
            boolean guardianPending = false;
            int activeCount = 0;
            for (StepFailureNotice n : noticeRepository.findByTicketIdAndStatusOrderByStepNoAscIdAsc(
                    ticketId, StepFailureNotice.STATUS_ACTIVE)) {
                activeCount++;
                if (n.getReadTime() == null) {
                    if (StepFailureNotice.ROLE_DISPATCHER.equals(n.getRecipientRole())) {
                        dispatcherPending = true;
                    } else if (StepFailureNotice.ROLE_GUARDIAN.equals(n.getRecipientRole())) {
                        guardianPending = true;
                    }
                }
            }
            if (activeCount > 0) {
                List<String> roles = new ArrayList<>();
                if (dispatcherPending) {
                    roles.add("调度员");
                }
                if (guardianPending) {
                    roles.add("监护人");
                }
                Map<String, Object> summary = new HashMap<>();
                summary.put("activeCount", activeCount);
                summary.put("unconfirmedRoles", roles);
                result.put(ticketId, summary);
            }
        }
        return result;
    }

    private Map<String, Object> toMap(StepFailureNotice n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.getId());
        map.put("ticketId", n.getTicketId());
        map.put("ticketNo", n.getTicketNo());
        map.put("issueDate", n.getIssueDate());
        map.put("stepNo", n.getStepNo());
        map.put("actionDesc", n.getActionDesc());
        map.put("recipientRole", n.getRecipientRole());
        map.put("recipientRoleName",
                StepFailureNotice.ROLE_DISPATCHER.equals(n.getRecipientRole()) ? "调度员" : "监护人");
        map.put("recipientName", n.getRecipientName());
        map.put("failPerson", n.getFailPerson());
        map.put("failReason", n.getFailReason());
        map.put("failTime", n.getFailTime());
        map.put("readTime", n.getReadTime());
        map.put("status", n.getStatus());
        map.put("resolved", StepFailureNotice.STATUS_RESOLVED.equals(n.getStatus()));
        map.put("resolvedTime", n.getResolvedTime());
        // 未读 = 未解除且本人尚未确认；已解除通知不再算作未读（但记录保留）
        map.put("unread", StepFailureNotice.STATUS_ACTIVE.equals(n.getStatus()) && n.getReadTime() == null);
        return map;
    }
}
