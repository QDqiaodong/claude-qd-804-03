package com.substation.ops.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.substation.ops.common.BizException;
import com.substation.ops.common.Labels;
import com.substation.ops.domain.Bay;
import com.substation.ops.domain.OperationStep;
import com.substation.ops.domain.OperationTicket;
import com.substation.ops.repository.BayRepository;
import com.substation.ops.repository.OperationStepRepository;
import com.substation.ops.repository.OperationTicketRepository;

/**
 * 模块五：操作票与倒闸操作。硬约束 —— 步骤必须按序号逐条执行，不能跳步。
 *
 * 并发：执行/失败登记先取操作票行级悲观锁，两名值班员同时执行同一步时
 * 后提交者在锁内重检，只会看到「已执行」，不会生成重复执行记录。
 * 部分失败：失败步标记 FAILED 停在原位，票保持 RUNNING，后续步不可越过；
 * 重新进入页面仍显示真实阶段与失败原因，可在原步重试成功后继续推进。
 */
@Service
public class OperationTicketService {

    private final OperationTicketRepository ticketRepository;
    private final OperationStepRepository stepRepository;
    private final BayRepository bayRepository;
    private final StepFailureNoticeService noticeService;

    public OperationTicketService(OperationTicketRepository ticketRepository,
                                  OperationStepRepository stepRepository,
                                  BayRepository bayRepository,
                                  StepFailureNoticeService noticeService) {
        this.ticketRepository = ticketRepository;
        this.stepRepository = stepRepository;
        this.bayRepository = bayRepository;
        this.noticeService = noticeService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOfDay(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<OperationTicket> tickets = ticketRepository.findByIssueDateOrderByIdAsc(day);
        Map<Long, Map<String, Object>> noticeSummary =
                noticeService.activeSummaryByTickets(tickets.stream().map(OperationTicket::getId).toList());
        List<Map<String, Object>> result = new ArrayList<>();
        for (OperationTicket t : tickets) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("ticketNo", t.getTicketNo());
            map.put("issueDate", t.getIssueDate());
            map.put("taskName", t.getTaskName());
            map.put("operatorName", t.getOperatorName());
            map.put("guardian", t.getGuardian());
            map.put("dispatcher", t.getDispatcher());
            map.put("status", t.getStatus());
            map.put("statusName", Labels.opStatus(t.getStatus()));
            Bay bay = t.getBayId() == null ? null : bayRepository.findById(t.getBayId()).orElse(null);
            map.put("bayName", bay == null ? "" : bay.getName());
            map.put("bayId", t.getBayId());

            List<OperationStep> steps = stepRepository.findByTicketIdOrderByStepNoAsc(t.getId());
            map.put("totalSteps", steps.size());
            map.put("doneSteps", steps.stream().filter(s -> OperationStep.STATUS_DONE.equals(s.getStatus())).count());
            map.put("failedSteps", steps.stream().filter(s -> OperationStep.STATUS_FAILED.equals(s.getStatus())).count());
            Map<String, Object> nSummary = noticeSummary.get(t.getId());
            map.put("noticeUnconfirmedRoles",
                    nSummary == null ? List.of() : nSummary.get("unconfirmedRoles"));
            result.add(map);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OperationTicket t : ticketRepository.findAllByOrderByIssueDateDescIdDesc()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("ticketNo", t.getTicketNo());
            map.put("issueDate", t.getIssueDate());
            map.put("taskName", t.getTaskName());
            map.put("bayId", t.getBayId());
            map.put("status", t.getStatus());
            map.put("statusName", Labels.opStatus(t.getStatus()));
            Bay bay = t.getBayId() == null ? null : bayRepository.findById(t.getBayId()).orElse(null);
            map.put("bayName", bay == null ? "" : bay.getName());
            List<OperationStep> steps = stepRepository.findByTicketIdOrderByStepNoAsc(t.getId());
            List<Map<String, Object>> stepBrief = new ArrayList<>();
            for (OperationStep s : steps) {
                Map<String, Object> item = new HashMap<>();
                item.put("stepNo", s.getStepNo());
                item.put("actionDesc", s.getActionDesc());
                item.put("status", s.getStatus());
                stepBrief.add(item);
            }
            map.put("steps", stepBrief);
            result.add(map);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<String> roster() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.addAll(ticketRepository.findDistinctDispatcher());
        names.addAll(ticketRepository.findDistinctGuardian());
        return new ArrayList<>(names);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        OperationTicket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new BizException("操作票不存在"));
        Bay bay = ticket.getBayId() == null ? null : bayRepository.findById(ticket.getBayId()).orElse(null);

        Map<String, Object> map = new HashMap<>();
        map.put("id", ticket.getId());
        map.put("ticketNo", ticket.getTicketNo());
        map.put("issueDate", ticket.getIssueDate());
        map.put("taskName", ticket.getTaskName());
        map.put("operatorName", ticket.getOperatorName());
        map.put("guardian", ticket.getGuardian());
        map.put("dispatcher", ticket.getDispatcher());
        map.put("status", ticket.getStatus());
        map.put("statusName", Labels.opStatus(ticket.getStatus()));
        map.put("remark", ticket.getRemark());
        map.put("bayId", ticket.getBayId());
        map.put("bayName", bay == null ? "" : bay.getName());

        List<OperationStep> steps = stepRepository.findByTicketIdOrderByStepNoAsc(ticket.getId());
        // 未解除失败通知的双方确认矩阵：页面持续标出尚未确认的角色（调度员/监护人）
        Map<Integer, Map<String, Object>> confirmMatrix = noticeService.activeConfirmMatrix(ticket.getId());
        List<Map<String, Object>> stepList = new ArrayList<>();
        OperationStep nextStep = null;
        for (OperationStep s : steps) {
            Map<String, Object> item = new HashMap<>();
            item.put("stepNo", s.getStepNo());
            item.put("actionDesc", s.getActionDesc());
            item.put("executePerson", s.getExecutePerson());
            item.put("executeTime", s.getExecuteTime());
            item.put("status", s.getStatus());
            item.put("statusName", Labels.stepStatus(s.getStatus()));
            item.put("failPerson", s.getFailPerson());
            item.put("failTime", s.getFailTime());
            item.put("failReason", s.getFailReason());
            boolean done = OperationStep.STATUS_DONE.equals(s.getStatus());
            boolean failed = OperationStep.STATUS_FAILED.equals(s.getStatus());
            item.put("done", done);
            item.put("failed", failed);
            item.put("executable", !done && nextStep == null);
            // 未解除通知的确认状态随步骤一起下发：通知状态与步骤状态同一查询口径
            Map<String, Object> confirm = confirmMatrix.get(s.getStepNo());
            item.put("failureNotice", confirm);
            item.put("unconfirmedRoles",
                    confirm == null ? List.of() : confirm.get("unconfirmedRoles"));
            String blockedHint;
            if (done) {
                blockedHint = "";
            } else if (failed) {
                blockedHint = "上次操作失败：" + s.getFailReason() + "，请在本步重新执行";
            } else if (nextStep == null) {
                blockedHint = "";
            } else {
                blockedHint = "请先执行第 " + nextStep.getStepNo() + " 步";
            }
            item.put("blockedHint", blockedHint);
            stepList.add(item);
            if (!done && nextStep == null) {
                nextStep = s;
            }
        }
        map.put("steps", stepList);
        map.put("totalSteps", steps.size());
        map.put("doneSteps", steps.stream().filter(s -> OperationStep.STATUS_DONE.equals(s.getStatus())).count());
        map.put("failedSteps", steps.stream().filter(s -> OperationStep.STATUS_FAILED.equals(s.getStatus())).count());
        map.put("nextStepNo", nextStep == null ? null : nextStep.getStepNo());
        map.put("hasFailure", steps.stream().anyMatch(s -> OperationStep.STATUS_FAILED.equals(s.getStatus())));
        // 本票全部失败通知（含重试成功后已解除的留痕，双方已读/解除时间保留，不提供删除）
        map.put("failureNotices", noticeService.listByTicket(ticket.getId()));
        return map;
    }

    /**
     * 执行某一步：必须按顺序执行，跳步直接报错。并发取票行锁串行化。
     */
    @Transactional
    public Map<String, Object> executeStep(Long id, Integer stepNo, String person) {
        OperationTicket ticket = ticketRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("操作票不存在"));

        if (OperationTicket.STATUS_FINISHED.equals(ticket.getStatus())) {
            throw new BizException("该操作票已全部执行完毕");
        }
        if (OperationTicket.STATUS_CANCELED.equals(ticket.getStatus())) {
            throw new BizException("该操作票已作废，不能继续操作");
        }

        List<OperationStep> steps = stepRepository.findByTicketIdOrderByStepNoAsc(ticket.getId());
        if (steps.isEmpty()) {
            throw new BizException("该操作票没有编排操作步骤，无法执行");
        }

        OperationStep target = steps.stream()
                .filter(s -> s.getStepNo().equals(stepNo))
                .findFirst()
                .orElseThrow(() -> new BizException("第 " + stepNo + " 步不存在"));

        if (OperationStep.STATUS_DONE.equals(target.getStatus())) {
            // 并发重复提交：后提交者看到最新结果，不重复记录
            throw new BizException("第 " + stepNo + " 步已由 " + target.getExecutePerson()
                    + " 于 " + target.getExecuteTime() + " 执行，请勿重复勾选");
        }

        OperationStep firstUndone = steps.stream()
                .filter(s -> !OperationStep.STATUS_DONE.equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("全部步骤已执行完毕"));

        if (!firstUndone.getStepNo().equals(stepNo)) {
            String reason = OperationStep.STATUS_FAILED.equals(firstUndone.getStatus())
                    ? "（第 " + firstUndone.getStepNo() + " 步上次操作失败，须先在该步重试成功）"
                    : "";
            throw new BizException("倒闸操作不得跳步：应先执行第 " + firstUndone.getStepNo() + " 步"
                    + reason + "，当前不要执行第 " + stepNo + " 步");
        }

        LocalDateTime now = LocalDateTime.now();
        target.setStatus(OperationStep.STATUS_DONE);
        target.setExecutePerson(person);
        target.setExecuteTime(now);
        target.setFailPerson(null);
        target.setFailTime(null);
        target.setFailReason(null);
        stepRepository.save(target);

        // 原步重试成功（或首次执行成功）：该步未解除通知转「已解除」，双方已读时间保留
        noticeService.resolveRetried(ticket.getId(), stepNo, now);

        // 直接以数据库当前状态重新统计，避免重试（FAILED→DONE）时重复计数
        List<OperationStep> latest = stepRepository.findByTicketIdOrderByStepNoAsc(ticket.getId());
        long doneCount = latest.stream().filter(s -> OperationStep.STATUS_DONE.equals(s.getStatus())).count();
        if (doneCount >= latest.size()) {
            ticket.setStatus(OperationTicket.STATUS_FINISHED);
        } else {
            ticket.setStatus(OperationTicket.STATUS_RUNNING);
        }
        ticketRepository.save(ticket);

        return detail(id);
    }

    /**
     * 登记某一步操作失败：失败步停在原位，票保持执行中，后续步骤不可越过。
     */
    @Transactional
    public Map<String, Object> failStep(Long id, Integer stepNo, String person, String reason) {
        OperationTicket ticket = ticketRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("操作票不存在"));

        if (OperationTicket.STATUS_FINISHED.equals(ticket.getStatus())) {
            throw new BizException("该操作票已全部执行完毕，不能登记失败");
        }
        if (OperationTicket.STATUS_CANCELED.equals(ticket.getStatus())) {
            throw new BizException("该操作票已作废");
        }

        List<OperationStep> steps = stepRepository.findByTicketIdOrderByStepNoAsc(ticket.getId());
        OperationStep target = steps.stream()
                .filter(s -> s.getStepNo().equals(stepNo))
                .findFirst()
                .orElseThrow(() -> new BizException("第 " + stepNo + " 步不存在"));

        if (OperationStep.STATUS_DONE.equals(target.getStatus())) {
            throw new BizException("第 " + stepNo + " 步已执行成功，不能登记失败");
        }
        OperationStep firstUndone = steps.stream()
                .filter(s -> !OperationStep.STATUS_DONE.equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("全部步骤已执行完毕"));
        if (!firstUndone.getStepNo().equals(stepNo)) {
            throw new BizException("只能对当前待执行的第 " + firstUndone.getStepNo() + " 步登记失败，不能提前登记后续步骤");
        }

        LocalDateTime failTime = LocalDateTime.now();
        target.setStatus(OperationStep.STATUS_FAILED);
        target.setFailPerson(person);
        target.setFailTime(failTime);
        target.setFailReason(reason);
        stepRepository.save(target);
        ticket.setStatus(OperationTicket.STATUS_RUNNING);
        ticketRepository.save(ticket);

        // 失败记录落库：同一事务内向本票调度员、监护人生成站内通知（内容含票号/步骤/原因/时间）
        noticeService.publishFailure(ticket, target, person, reason, failTime);
        return detail(id);
    }
}
