package com.substation.ops.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.substation.ops.common.ApiResponse;
import com.substation.ops.service.StepFailureNoticeService;

/**
 * 操作失败站内通知：调度员/监护人各自确认已读，彼此不覆盖；
 * 通知只可解除（原步重试成功由系统解除），不提供删除。
 */
@RestController
@RequestMapping("/api/failure-notices")
public class StepFailureNoticeController {

    private final StepFailureNoticeService noticeService;

    public StepFailureNoticeController(StepFailureNoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /** 当前值班员的全部失败通知（未解除在前），含未读数；刷新或换设备均以服务端落库状态为准 */
    @GetMapping("/mine")
    public ApiResponse<Map<String, Object>> mine(@RequestParam(required = false) String person) {
        return ApiResponse.success(noticeService.mineSummary(person));
    }

    /** 确认已读：只更新本人那条记录；已解除通知也可补确认，但保留第一次确认时间 */
    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable Long id,
                                                     @RequestParam(required = false) String person) {
        return ApiResponse.success("已确认", noticeService.markRead(id, person));
    }
}
