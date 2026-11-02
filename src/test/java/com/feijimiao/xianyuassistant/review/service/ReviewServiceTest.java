package com.feijimiao.xianyuassistant.review.service;

import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.service.JobEngine;
import com.feijimiao.xianyuassistant.review.entity.ReviewRecord;
import com.feijimiao.xianyuassistant.review.entity.ReviewRule;
import com.feijimiao.xianyuassistant.review.mapper.ReviewRecordMapper;
import com.feijimiao.xianyuassistant.review.mapper.ReviewRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {
    @Mock ReviewRuleMapper ruleMapper;
    @Mock ReviewRecordMapper recordMapper;
    @Mock JobEngine jobEngine;
    private ReviewService service;

    @BeforeEach void setUp() { service = new ReviewService(ruleMapper, recordMapper, jobEngine); }

    @Test void scheduleIsIdempotentAndCreatesDelayedJob() {
        ReviewRule rule = new ReviewRule();
        rule.setId(4L); rule.setAccountId(7L); rule.setEnabled(1); rule.setDelaySeconds(60);
        rule.setTemplate("感谢支持"); rule.setRequireManualConfirm(0); rule.setMaxRetries(2);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(recordMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> { invocation.<ReviewRecord>getArgument(0).setId(11L); return 1; })
                .when(recordMapper).insert(any(ReviewRecord.class));
        when(jobEngine.createJob(any(), any(), any(), eq(7L), any(), eq(2), eq(0))).thenReturn(new JobDefinition());

        ReviewRecord created = service.schedule(7L, "O1", "buyer", null);
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.getReviewText()).isEqualTo("感谢支持");
        verify(recordMapper, atLeastOnce()).insert(created);
        verify(jobEngine).createJob(eq(ReviewService.JOB_TYPE), eq("review:7:O1"), any(), eq(7L), any(), eq(2), eq(0));
    }

    @Test void manualConfirmationIsRequiredWhenRuleRequestsIt() {
        ReviewRule rule = new ReviewRule();
        rule.setId(4L); rule.setAccountId(7L); rule.setEnabled(1); rule.setDelaySeconds(10);
        rule.setTemplate("模板"); rule.setRequireManualConfirm(1); rule.setMaxRetries(3);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(recordMapper.selectOne(any())).thenReturn(null);

        ReviewRecord created = service.schedule(7L, "O2", null, "自定义");
        assertThat(created.getStatus()).isEqualTo("WAITING_CONFIRM");
        verifyNoInteractions(jobEngine);
    }

    @Test void workerDoesNotPretendPlatformSubmissionSucceeded() {
        ReviewRecord record = new ReviewRecord();
        record.setId(9L); record.setStatus("PENDING");
        when(recordMapper.selectById(9L)).thenReturn(record);

        assertThat(service.executePending(9L)).isTrue();
        assertThat(record.getStatus()).isEqualTo("MANUAL_REQUIRED");
        assertThat(record.getLastError()).contains("未接入");
        verify(recordMapper).updateById(record);
    }
}
