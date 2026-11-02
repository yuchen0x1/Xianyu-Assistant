package com.feijimiao.xianyuassistant.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.service.JobEngine;
import com.feijimiao.xianyuassistant.review.entity.ReviewRecord;
import com.feijimiao.xianyuassistant.review.entity.ReviewRule;
import com.feijimiao.xianyuassistant.review.mapper.ReviewRecordMapper;
import com.feijimiao.xianyuassistant.review.mapper.ReviewRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {
    public static final String JOB_TYPE = "REVIEW_PENDING";
    private final ReviewRuleMapper ruleMapper;
    private final ReviewRecordMapper recordMapper;
    private final JobEngine jobEngine;

    public List<ReviewRule> listRules() { return ruleMapper.selectList(null); }
    public ReviewRecord getRecord(Long id) { return recordMapper.selectById(id); }
    public ReviewRule getRule(Long accountId) {
        return ruleMapper.selectOne(new LambdaQueryWrapper<ReviewRule>().eq(ReviewRule::getAccountId, accountId));
    }

    @Transactional
    public ReviewRule saveRule(ReviewRule rule) {
        if (rule.getAccountId() == null) throw new IllegalArgumentException("accountId不能为空");
        if (rule.getTemplate() == null || rule.getTemplate().isBlank()) throw new IllegalArgumentException("评价模板不能为空");
        rule.setDelaySeconds(Math.max(0, Math.min(rule.getDelaySeconds() == null ? 86400 : rule.getDelaySeconds(), 30 * 86400)));
        rule.setMaxRetries(Math.max(0, Math.min(rule.getMaxRetries() == null ? 3 : rule.getMaxRetries(), 10)));
        rule.setEnabled(rule.getEnabled() == null ? 0 : rule.getEnabled());
        rule.setRequireManualConfirm(rule.getRequireManualConfirm() == null ? 1 : rule.getRequireManualConfirm());
        ReviewRule existing = getRule(rule.getAccountId());
        if (existing == null) { ruleMapper.insert(rule); return rule; }
        rule.setId(existing.getId());
        rule.setCreatedTime(existing.getCreatedTime());
        rule.setUpdatedTime(LocalDateTime.now());
        ruleMapper.updateById(rule);
        return rule;
    }

    public List<ReviewRecord> listRecords(Long accountId, String status, int limit) {
        LambdaQueryWrapper<ReviewRecord> query = new LambdaQueryWrapper<ReviewRecord>()
                .eq(accountId != null, ReviewRecord::getAccountId, accountId)
                .eq(status != null && !status.isBlank(), ReviewRecord::getStatus, status)
                .orderByDesc(ReviewRecord::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500)));
        return recordMapper.selectList(query);
    }

    public List<ReviewRecord> listRecordsForAccounts(List<Long> accountIds, String status, int limit) {
        if (accountIds == null || accountIds.isEmpty()) return List.of();
        LambdaQueryWrapper<ReviewRecord> query = new LambdaQueryWrapper<ReviewRecord>()
                .in(ReviewRecord::getAccountId, accountIds)
                .eq(status != null && !status.isBlank(), ReviewRecord::getStatus, status)
                .orderByDesc(ReviewRecord::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500)));
        return recordMapper.selectList(query);
    }

    @Transactional
    public ReviewRecord schedule(Long accountId, String orderId, String buyerUserId, String reviewText) {
        if (accountId == null || orderId == null || orderId.isBlank()) throw new IllegalArgumentException("账号和订单不能为空");
        ReviewRule rule = getRule(accountId);
        if (rule == null || !Integer.valueOf(1).equals(rule.getEnabled())) throw new IllegalStateException("账号未启用评价规则");
        ReviewRecord existing = recordMapper.selectOne(new LambdaQueryWrapper<ReviewRecord>()
                .eq(ReviewRecord::getAccountId, accountId).eq(ReviewRecord::getOrderId, orderId));
        if (existing != null) return existing;
        LocalDateTime scheduledAt = LocalDateTime.now().plusSeconds(rule.getDelaySeconds());
        ReviewRecord record = new ReviewRecord();
        record.setAccountId(accountId); record.setOrderId(orderId); record.setBuyerUserId(buyerUserId);
        record.setRuleId(rule.getId()); record.setReviewText(reviewText == null || reviewText.isBlank() ? rule.getTemplate() : reviewText);
        record.setStatus(rule.getRequireManualConfirm() == 1 ? "WAITING_CONFIRM" : "PENDING");
        record.setScheduledAt(scheduledAt); record.setRetryCount(0);
        recordMapper.insert(record);
        if ("PENDING".equals(record.getStatus())) {
            JobDefinition job = jobEngine.createJob(JOB_TYPE, "review:" + accountId + ":" + orderId,
                    Map.of("reviewRecordId", record.getId()), accountId, scheduledAt, rule.getMaxRetries(), 0);
            record.setJobId(job.getId()); recordMapper.updateById(record);
        }
        return record;
    }

    @Transactional
    public boolean executePending(Long recordId) {
        ReviewRecord record = require(recordId);
        if ("CANCELLED".equals(record.getStatus()) || "SUBMITTED".equals(record.getStatus())) return true;
        // 当前平台接口未提供可靠的自动评价提交能力，不能伪造成功；转为人工处理状态。
        record.setStatus("MANUAL_REQUIRED");
        record.setLastError("平台评价提交能力未接入，请人工处理");
        record.setUpdatedTime(LocalDateTime.now());
        recordMapper.updateById(record);
        return true;
    }


    @Transactional
    public ReviewRecord confirm(Long id) {
        ReviewRecord record = require(id);
        if (!"WAITING_CONFIRM".equals(record.getStatus())) return record;
        record.setStatus("PENDING");
        ReviewRule rule = record.getRuleId() == null ? null : ruleMapper.selectById(record.getRuleId());
        LocalDateTime scheduled = LocalDateTime.now().plusSeconds(rule == null ? 0 : rule.getDelaySeconds());
        record.setScheduledAt(scheduled);
        JobDefinition job = jobEngine.createJob(JOB_TYPE, "review:" + record.getAccountId() + ":" + record.getOrderId(),
                Map.of("reviewRecordId", record.getId()), record.getAccountId(), scheduled,
                rule == null ? 3 : rule.getMaxRetries(), 0);
        record.setJobId(job.getId()); recordMapper.updateById(record); return record;
    }

    @Transactional
    public ReviewRecord cancel(Long id) { ReviewRecord record = require(id); record.setStatus("CANCELLED"); recordMapper.updateById(record); return record; }
    @Transactional
    public ReviewRecord retry(Long id) { ReviewRecord record = require(id); record.setStatus("PENDING"); record.setLastError(null); record.setScheduledAt(LocalDateTime.now()); recordMapper.updateById(record); confirmIfNeeded(record); return record; }

    private void confirmIfNeeded(ReviewRecord record) {
        JobDefinition job = jobEngine.createJob(JOB_TYPE, "review:" + record.getAccountId() + ":" + record.getOrderId(),
                Map.of("reviewRecordId", record.getId()), record.getAccountId(), LocalDateTime.now(), 3, 0);
        record.setJobId(job.getId()); recordMapper.updateById(record);
    }
    private ReviewRecord require(Long id) { ReviewRecord r = recordMapper.selectById(id); if (r == null) throw new IllegalArgumentException("评价记录不存在"); return r; }
}
