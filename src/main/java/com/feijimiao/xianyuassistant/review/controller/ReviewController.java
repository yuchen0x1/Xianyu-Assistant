package com.feijimiao.xianyuassistant.review.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.review.entity.ReviewRecord;
import com.feijimiao.xianyuassistant.review.entity.ReviewRule;
import com.feijimiao.xianyuassistant.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviewService;
    private final RbacService rbacService;

    @GetMapping("/rules")
    @RequirePermission("review:read")
    public ResultObject<List<ReviewRule>> rules() {
        List<Long> visible = rbacService.getVisibleAccountIds(UserContext.getUserId());
        return ResultObject.success(reviewService.listRules().stream()
                .filter(rule -> visible == null || visible.contains(rule.getAccountId()))
                .toList());
    }

    @PutMapping("/rules")
    @RequirePermission("review:write")
    public ResultObject<ReviewRule> saveRule(@RequestBody ReviewRule rule) {
        rbacService.assertAccountVisible(UserContext.getUserId(), rule.getAccountId());
        return ResultObject.success(reviewService.saveRule(rule));
    }

    @GetMapping("/records")
    @RequirePermission("review:read")
    public ResultObject<List<ReviewRecord>> records(@RequestParam(required = false) Long accountId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "100") int limit) {
        Long userId = UserContext.getUserId();
        List<Long> visible = rbacService.getVisibleAccountIds(userId);
        if (accountId != null) {
            rbacService.assertAccountVisible(userId, accountId);
            return ResultObject.success(reviewService.listRecords(accountId, status, limit));
        }
        if (visible == null) {
            return ResultObject.success(reviewService.listRecords(null, status, limit));
        }
        return ResultObject.success(reviewService.listRecordsForAccounts(visible, status, limit));
    }

    @PostMapping("/records")
    @RequirePermission("review:write")
    public ResultObject<ReviewRecord> schedule(@RequestParam Long accountId, @RequestParam String orderId,
                                                @RequestParam(required = false) String buyerUserId,
                                                @RequestParam(required = false) String reviewText) {
        rbacService.assertAccountVisible(UserContext.getUserId(), accountId);
        return ResultObject.success(reviewService.schedule(accountId, orderId, buyerUserId, reviewText));
    }

    @PostMapping("/records/{id}/confirm")
    @RequirePermission("review:write")
    public ResultObject<ReviewRecord> confirm(@PathVariable Long id) {
        assertRecordVisible(id);
        return ResultObject.success(reviewService.confirm(id));
    }

    @PostMapping("/records/{id}/retry")
    @RequirePermission("review:write")
    public ResultObject<ReviewRecord> retry(@PathVariable Long id) {
        assertRecordVisible(id);
        return ResultObject.success(reviewService.retry(id));
    }

    @PostMapping("/records/{id}/cancel")
    @RequirePermission("review:write")
    public ResultObject<ReviewRecord> cancel(@PathVariable Long id) {
        assertRecordVisible(id);
        return ResultObject.success(reviewService.cancel(id));
    }

    private void assertRecordVisible(Long id) {
        ReviewRecord record = reviewService.getRecord(id);
        rbacService.assertAccountVisible(UserContext.getUserId(), record == null ? null : record.getAccountId());
    }
}
