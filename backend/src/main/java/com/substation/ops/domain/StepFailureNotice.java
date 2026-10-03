package com.substation.ops.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 操作失败站内通知：倒闸步骤登记失败落库时，向本票调度员（发令人）与监护人
 * 各生成一条通知。两类接收人的已读确认各自独立，互不覆盖。
 *
 * 生命周期：
 * - ACTIVE 未解除：失败步尚未重试成功。未读不阻断原步重试；页面持续标出尚未确认的角色。
 * - RESOLVED 已解除：原步重试成功后由系统解除，双方已读时间原样保留，
 *   记录留痕，不能作为普通通知直接删除。
 *
 * 同一张票的同一步只有一条「未解除」通知：该步再次登记失败时刷新原因/时间，
 * 并把双方已读确认清空（新一轮失败需要重新确认）；重试成功即解除。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "step_failure_notice")
public class StepFailureNotice {

    /** 未解除：失败仍挂在原步，等待重试与双方确认 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已解除：原步重试成功，通知留痕保留，不得删除 */
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 接收人角色：调度员（发令人）/ 监护人 */
    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_GUARDIAN = "GUARDIAN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "step_no")
    private Integer stepNo;

    /** 接收人角色：DISPATCHER/GUARDIAN */
    @Column(name = "recipient_role", length = 24)
    private String recipientRole;

    /** 接收人姓名（落库时取自票面发令人/监护人，票面之后改名不影响本通知） */
    @Column(name = "recipient_name", length = 32)
    private String recipientName;

    /** 票号冗余：通知列表直接展示，不必回查票面 */
    @Column(name = "ticket_no", length = 64)
    private String ticketNo;

    /** 票面业务日期：从通知跳入时把时间轴切到当天 */
    @Column(name = "issue_date")
    private LocalDate issueDate;

    /** 步骤内容冗余 */
    @Column(name = "action_desc", length = 255)
    private String actionDesc;

    /** 失败原因（最近一次登记） */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    /** 失败登记人 */
    @Column(name = "fail_person", length = 32)
    private String failPerson;

    /** 失败发生时间（通知生成时间） */
    @Column(name = "fail_time")
    private LocalDateTime failTime;

    /** 该接收人确认已读时间；null 表示尚未确认 */
    @Column(name = "read_time")
    private LocalDateTime readTime;

    /** ACTIVE / RESOLVED */
    @Column(name = "status", length = 24)
    private String status;

    /** 解除时间（原步重试成功时间） */
    @Column(name = "resolved_time")
    private LocalDateTime resolvedTime;
}
