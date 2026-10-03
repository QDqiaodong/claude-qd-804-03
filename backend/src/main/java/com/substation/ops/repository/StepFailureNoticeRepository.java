package com.substation.ops.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.substation.ops.domain.StepFailureNotice;

public interface StepFailureNoticeRepository extends JpaRepository<StepFailureNotice, Long> {

    /** 同票同步的未解除通知（每个角色至多一条，由登记逻辑在票行锁内保证） */
    List<StepFailureNotice> findByTicketIdAndStepNoAndStatus(
            Long ticketId, Integer stepNo, String status);

    /** 某张票所有未解除通知：重试成功后一次性解除 */
    List<StepFailureNotice> findByTicketIdAndStatusOrderByStepNoAscIdAsc(
            Long ticketId, String status);

    /** 某张票的全部通知（含已解除留痕），按发生时间倒序 */
    List<StepFailureNotice> findByTicketIdOrderByFailTimeDescIdDesc(Long ticketId);

    /** 某人收到的全部通知，未解除在前、发生时间倒序 */
    List<StepFailureNotice> findByRecipientNameOrderByStatusAscFailTimeDescIdDesc(String recipientName);

    /** 确认已读时加行锁：两个角色各自确认互不覆盖 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from StepFailureNotice n where n.id = :id")
    Optional<StepFailureNotice> findByIdForUpdate(@Param("id") Long id);
}
