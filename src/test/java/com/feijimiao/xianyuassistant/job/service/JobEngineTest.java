package com.feijimiao.xianyuassistant.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.job.entity.JobDeadLetter;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.entity.JobExecution;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.context.UserContext;
import org.springframework.dao.DataIntegrityViolationException;
import com.feijimiao.xianyuassistant.job.mapper.JobDeadLetterMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobDefinitionMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobEngineTest {

    @Mock
    private JobDefinitionMapper jobDefinitionMapper;
    @Mock
    private JobExecutionMapper jobExecutionMapper;
    @Mock
    private JobDeadLetterMapper deadLetterMapper;
    @Mock
    private RbacService rbacService;

    private JobEngine jobEngine;

    @BeforeEach
    void setUp() {
        jobEngine = new JobEngine(jobDefinitionMapper, jobExecutionMapper, deadLetterMapper,
                new ObjectMapper(), rbacService, null);
        UserContext.clear();
    }

    @Test
    void claimsPendingJobByWritingRunningStatusAndHandlesNullRetryFields() {
        JobDefinition job = pendingJob();
        when(jobDefinitionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        jobEngine.registerWorker("TEST", (definition, params) -> true);

        ReflectionTestUtils.invokeMethod(jobEngine, "executeJob", job);

        ArgumentCaptor<UpdateWrapper<JobDefinition>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(jobDefinitionMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSet())
                .contains("status")
                .contains("lease_token");
        assertThat(captor.getAllValues().get(1).getSqlSet())
                .contains("status")
                .contains("lease_token");
        assertThat(job.getStatus()).isEqualTo("SUCCESS");
        assertThat(job.getLeaseToken()).isNull();
        verify(jobExecutionMapper).insert(any(JobExecution.class));
    }

    @Test
    void skipsWorkerWhenAnotherWorkerAlreadyClaimedJob() {
        JobDefinition job = pendingJob();
        when(jobDefinitionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);
        jobEngine.registerWorker("TEST", (definition, params) -> {
            throw new AssertionError("worker must not run after failed claim");
        });

        ReflectionTestUtils.invokeMethod(jobEngine, "executeJob", job);

        verify(jobExecutionMapper, never()).insert(any(JobExecution.class));
        assertThat(job.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void doesNotWriteDeadLetterWhenTerminalFencingUpdateFails() {
        JobDefinition job = pendingJob();
        job.setMaxRetries(0);
        when(jobDefinitionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1, 0);
        jobEngine.registerWorker("TEST", (definition, params) -> false);

        ReflectionTestUtils.invokeMethod(jobEngine, "executeJob", job);

        verify(deadLetterMapper, never()).insert(any(JobDeadLetter.class));
        assertThat(job.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(job.getLeaseToken()).isNotBlank();
    }

    @Test
    void writesDeadLetterOnlyAfterTerminalFencingUpdateSucceeds() {
        JobDefinition job = pendingJob();
        job.setMaxRetries(0);
        when(jobDefinitionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1, 1);
        jobEngine.registerWorker("TEST", (definition, params) -> false);

        ReflectionTestUtils.invokeMethod(jobEngine, "executeJob", job);

        verify(deadLetterMapper).insert(any(JobDeadLetter.class));
        assertThat(job.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(job.getLeaseToken()).isNull();
    }

    @Test
    void cancelJobMarksPendingJobAsCancelled() {
        UserContext.set(1L, "tester");
        when(rbacService.getUserRoleNames(1L)).thenReturn(List.of("ADMIN"));
        when(jobDefinitionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(pendingJob());
        when(jobDefinitionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        boolean cancelled = jobEngine.cancelJob("test-key");

        assertThat(cancelled).isTrue();
        ArgumentCaptor<UpdateWrapper<JobDefinition>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(jobDefinitionMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("status");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("CANCELLED");
    }

    @Test
    void cancelJobReturnsFalseForBlankKey() {
        assertThat(jobEngine.cancelJob(null)).isFalse();
        assertThat(jobEngine.cancelJob("  ")).isFalse();
        verify(jobDefinitionMapper, never()).update(any(), any());
    }

    @Test
    void dueJobQueryUsesMybatisPlusApplyPlaceholderNotQuestionMark() {
        // 回归保护：MyBatis-Plus apply 使用 "?" 会在构建 wrapper 时抛
        // "sql not contains: {0}"，导致任务引擎永远无法轮询到到期任务。
        // 此处同时断言：参数确实被绑定（paramNameValuePairs 含 now），
        // 且生成的 SQL 片段不含裸 "?"（防止退化为无参拼接）。
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        var wrapper = jobEngine.dueJobQuery(now);
        assertThat(wrapper.getSqlSegment())
                .contains("next_run_time IS NULL OR next_run_time <= ")
                .doesNotContain("next_run_time IS NULL OR next_run_time <= ?");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(now);
    }

    private JobDefinition pendingJob() {
        JobDefinition job = new JobDefinition();
        job.setId(1L);
        job.setJobType("TEST");
        job.setIdempotencyKey("test-key");
        job.setStatus("PENDING");
        job.setParamsJson("{}");
        return job;
    }
}
