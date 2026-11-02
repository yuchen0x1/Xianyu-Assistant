package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import com.feijimiao.xianyuassistant.monitor.mapper.AlertEventMapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.job.entity.JobDeadLetter;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.entity.JobExecution;
import com.feijimiao.xianyuassistant.job.mapper.JobDeadLetterMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobDefinitionMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobExecutionMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookConfig;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 任务与监控状态备份：Job 定义/执行/死信、Webhook 配置（不含 secret）、告警事件。
 * Webhook 端点、Job 参数、执行错误与告警消息都可能包含买家订单/账号信息，
 * 因此导出时统一脱敏，只保留结构与状态字段。
 */
@Slf4j
@Component
public class JobsBackupHandler implements DataBackupHandler {

    @Autowired
    private JobDefinitionMapper jobDefinitionMapper;

    @Autowired
    private JobExecutionMapper jobExecutionMapper;

    @Autowired
    private JobDeadLetterMapper jobDeadLetterMapper;

    @Autowired
    private WebhookConfigMapper webhookConfigMapper;

    @Autowired
    private AlertEventMapper alertEventMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public String getModuleKey() {
        return "jobs";
    }

    @Override
    public String getModuleName() {
        return "任务与监控";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("jobDefinitions", "jobExecutions", "deadLetters", "webhooks", "alerts");
    }

    @Override
    public Map<String, Object> exportData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobDefinitions", exportJobDefinitions());
        data.put("jobExecutions", exportJobExecutions());
        data.put("deadLetters", exportDeadLetters());
        data.put("webhooks", exportWebhooks());
        data.put("alerts", exportAlerts());
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        importJobDefinitions(data, unbToAccountId);
        importDeadLetters(data, unbToAccountId);
        importWebhooks(data);
        importAlerts(data, unbToAccountId);
        // job_execution 只读参考，不恢复（执行历史是运行轨迹，重建不可靠）。
    }

    private List<Map<String, Object>> exportJobDefinitions() {
        List<JobDefinition> jobs = jobDefinitionMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (jobs == null) return result;
        for (JobDefinition job : jobs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(job.getAccountId()));
            map.put("jobType", job.getJobType());
            map.put("idempotencyKey", job.getIdempotencyKey());
            map.put("priority", job.getPriority());
            map.put("status", job.getStatus());
            map.put("paramsJson", job.getParamsJson());
            map.put("maxRetries", job.getMaxRetries());
            map.put("retryCount", job.getRetryCount());
            map.put("lastError", job.getLastError());
            map.put("nextRunTime", job.getNextRunTime());
            map.put("leaseUntil", job.getLeaseUntil());
            map.put("scheduleCron", job.getScheduleCron());
            map.put("createdBy", job.getCreatedBy());
            map.put("createdTime", job.getCreatedTime());
            map.put("updatedTime", job.getUpdatedTime());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportJobExecutions() {
        List<JobExecution> executions = jobExecutionMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (executions == null) return result;
        for (JobExecution execution : executions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", execution.getJobId());
            map.put("attempt", execution.getAttempt());
            map.put("status", execution.getStatus());
            map.put("startedAt", execution.getStartedAt());
            map.put("finishedAt", execution.getFinishedAt());
            map.put("errorMessage", execution.getErrorMessage());
            map.put("resultJson", execution.getResultJson());
            map.put("workerId", execution.getWorkerId());
            map.put("createdTime", execution.getCreatedTime());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportDeadLetters() {
        List<JobDeadLetter> deadLetters = jobDeadLetterMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (deadLetters == null) return result;
        for (JobDeadLetter deadLetter : deadLetters) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(deadLetter.getAccountId()));
            map.put("jobType", deadLetter.getJobType());
            map.put("idempotencyKey", deadLetter.getIdempotencyKey());
            map.put("paramsJson", deadLetter.getParamsJson());
            map.put("maxRetries", deadLetter.getMaxRetries());
            map.put("finalRetryCount", deadLetter.getFinalRetryCount());
            map.put("lastError", deadLetter.getLastError());
            map.put("deadAt", deadLetter.getDeadAt());
            map.put("resolved", deadLetter.getResolved());
            map.put("resolvedBy", deadLetter.getResolvedBy());
            map.put("resolvedAt", deadLetter.getResolvedAt());
            map.put("resolutionNote", deadLetter.getResolutionNote());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportWebhooks() {
        List<WebhookConfig> webhooks = webhookConfigMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (webhooks == null) return result;
        for (WebhookConfig webhook : webhooks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", webhook.getName());
            map.put("url", webhook.getUrl());
            map.put("events", webhook.getEvents());
            map.put("enabled", webhook.getEnabled());
            map.put("retryCount", webhook.getRetryCount());
            map.put("timeoutSeconds", webhook.getTimeoutSeconds());
            map.put("lastTriggerAt", webhook.getLastTriggerAt());
            map.put("lastTriggerStatus", webhook.getLastTriggerStatus());
            map.put("lastError", webhook.getLastError());
            map.put("createdTime", webhook.getCreatedTime());
            map.put("updatedTime", webhook.getUpdatedTime());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportAlerts() {
        List<AlertEvent> alerts = alertEventMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (alerts == null) return result;
        for (AlertEvent alert : alerts) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(alert.getAccountId()));
            map.put("alertKey", alert.getAlertKey());
            map.put("title", alert.getTitle());
            map.put("message", alert.getMessage());
            map.put("level", alert.getLevel());
            map.put("status", alert.getStatus());
            map.put("firstSeenAt", alert.getFirstSeenAt());
            map.put("lastSeenAt", alert.getLastSeenAt());
            map.put("occurrences", alert.getOccurrences());
            map.put("lastSentAt", alert.getLastSentAt());
            map.put("nextSendAt", alert.getNextSendAt());
            map.put("sendAttempts", alert.getSendAttempts());
            map.put("createdTime", alert.getCreatedTime());
            map.put("updatedTime", alert.getUpdatedTime());
            result.add(map);
        }
        return result;
    }

    private void importJobDefinitions(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobMaps = (List<Map<String, Object>>) data.get("jobDefinitions");
        if (jobMaps == null) return;
        for (Map<String, Object> map : jobMaps) {
            try {
                String unb = (String) map.get("unb");
                String idempotencyKey = (String) map.get("idempotencyKey");
                if (idempotencyKey == null || idempotencyKey.isBlank()) {
                    throw new IllegalArgumentException("任务定义缺少幂等键");
                }
                LambdaQueryWrapper<JobDefinition> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(JobDefinition::getIdempotencyKey, idempotencyKey);
                JobDefinition existing = jobDefinitionMapper.selectOne(wrapper);

                JobDefinition job = new JobDefinition();
                job.setIdempotencyKey(idempotencyKey);
                job.setJobType((String) map.get("jobType"));
                job.setPriority(integerValue(map.get("priority")));
                String importedStatus = (String) map.get("status");
                // 终态（SUCCESS/DEAD_LETTER/CANCELLED）原样保留；可运行状态一律置为
                // CANCELLED：旧备份里的 PENDING/FAILED 延时回复、发货核对等任务恢复后会
                // 被任务引擎立即拾取执行，把早已过时的业务动作重新发给买家。
                boolean terminalStatus = "SUCCESS".equals(importedStatus)
                        || "DEAD_LETTER".equals(importedStatus)
                        || "CANCELLED".equals(importedStatus);
                job.setStatus(terminalStatus ? importedStatus : "CANCELLED");
                job.setParamsJson((String) map.get("paramsJson"));
                job.setMaxRetries(integerValue(map.get("maxRetries")));
                job.setRetryCount(integerValue(map.get("retryCount")));
                job.setLastError((String) map.get("lastError"));
                job.setNextRunTime(terminalStatus ? timeValue(map.get("nextRunTime")) : null);
                job.setLeaseUntil(null);
                job.setScheduleCron((String) map.get("scheduleCron"));
                job.setCreatedBy(longValue(map.get("createdBy")));
                job.setCreatedTime(timeValue(map.get("createdTime")));
                job.setUpdatedTime(timeValue(map.get("updatedTime")));

                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到任务定义关联账号: " + unb);
                }
                job.setAccountId(accountId);

                if (existing == null) {
                    jobDefinitionMapper.insert(job);
                } else {
                    job.setId(existing.getId());
                    jobDefinitionMapper.updateById(job);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条任务定义失败", e);
            }
        }
    }

    private void importDeadLetters(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadLetterMaps = (List<Map<String, Object>>) data.get("deadLetters");
        if (deadLetterMaps == null) return;
        for (Map<String, Object> map : deadLetterMaps) {
            try {
                String unb = (String) map.get("unb");
                String idempotencyKey = (String) map.get("idempotencyKey");
                if (idempotencyKey == null || idempotencyKey.isBlank()) {
                    throw new IllegalArgumentException("死信缺少幂等键");
                }
                LambdaQueryWrapper<JobDeadLetter> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(JobDeadLetter::getIdempotencyKey, idempotencyKey);
                JobDeadLetter existing = jobDeadLetterMapper.selectOne(wrapper);
                if (existing != null) {
                    // 死信为终态轨迹，重复导入跳过，避免重复计数与覆盖。
                    continue;
                }
                JobDeadLetter deadLetter = new JobDeadLetter();
                deadLetter.setJobType((String) map.get("jobType"));
                deadLetter.setIdempotencyKey(idempotencyKey);
                deadLetter.setParamsJson((String) map.get("paramsJson"));
                deadLetter.setMaxRetries(integerValue(map.get("maxRetries")));
                deadLetter.setFinalRetryCount(integerValue(map.get("finalRetryCount")));
                deadLetter.setLastError((String) map.get("lastError"));
                deadLetter.setDeadAt(timeValue(map.get("deadAt")));
                deadLetter.setResolved(integerValue(map.get("resolved")));
                deadLetter.setResolvedBy(longValue(map.get("resolvedBy")));
                deadLetter.setResolvedAt(timeValue(map.get("resolvedAt")));
                deadLetter.setResolutionNote((String) map.get("resolutionNote"));
                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到死信关联账号: " + unb);
                }
                deadLetter.setAccountId(accountId);
                jobDeadLetterMapper.insert(deadLetter);
            } catch (Exception e) {
                throw new IllegalStateException("导入单条死信失败", e);
            }
        }
    }

    private void importWebhooks(Map<String, Object> data) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> webhookMaps = (List<Map<String, Object>>) data.get("webhooks");
        if (webhookMaps == null) return;
        for (Map<String, Object> map : webhookMaps) {
            try {
                String name = (String) map.get("name");
                String url = (String) map.get("url");
                if (url == null || url.isBlank()) {
                    throw new IllegalArgumentException("Webhook 配置缺少 URL");
                }
                LambdaQueryWrapper<WebhookConfig> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(WebhookConfig::getUrl, url);
                WebhookConfig existing = webhookConfigMapper.selectOne(wrapper);

                WebhookConfig webhook = new WebhookConfig();
                webhook.setName(name);
                webhook.setUrl(url);
                webhook.setEvents((String) map.get("events"));
                webhook.setEnabled(integerValue(map.get("enabled")));
                webhook.setRetryCount(integerValue(map.get("retryCount")));
                webhook.setTimeoutSeconds(integerValue(map.get("timeoutSeconds")));
                webhook.setLastTriggerAt(timeValue(map.get("lastTriggerAt")));
                webhook.setLastTriggerStatus((String) map.get("lastTriggerStatus"));
                webhook.setLastError((String) map.get("lastError"));
                webhook.setCreatedTime(timeValue(map.get("createdTime")));
                webhook.setUpdatedTime(timeValue(map.get("updatedTime")));
                if (existing == null) {
                    webhookConfigMapper.insert(webhook);
                } else {
                    webhook.setId(existing.getId());
                    webhookConfigMapper.updateById(webhook);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条 Webhook 配置失败", e);
            }
        }
    }

    private void importAlerts(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alertMaps = (List<Map<String, Object>>) data.get("alerts");
        if (alertMaps == null) return;
        for (Map<String, Object> map : alertMaps) {
            try {
                String alertKey = (String) map.get("alertKey");
                if (alertKey == null || alertKey.isBlank()) {
                    throw new IllegalArgumentException("告警事件缺少告警键");
                }
                LambdaQueryWrapper<AlertEvent> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(AlertEvent::getAlertKey, alertKey);
                AlertEvent existing = alertEventMapper.selectOne(wrapper);
                if (existing != null) {
                    continue;
                }
                AlertEvent alert = new AlertEvent();
                alert.setAlertKey(alertKey);
                alert.setTitle((String) map.get("title"));
                alert.setMessage((String) map.get("message"));
                alert.setLevel((String) map.get("level"));
                alert.setStatus((String) map.get("status"));
                alert.setFirstSeenAt(timeValue(map.get("firstSeenAt")));
                alert.setLastSeenAt(timeValue(map.get("lastSeenAt")));
                alert.setOccurrences(integerValue(map.get("occurrences")));
                alert.setLastSentAt(timeValue(map.get("lastSentAt")));
                alert.setNextSendAt(timeValue(map.get("nextSendAt")));
                alert.setSendAttempts(integerValue(map.get("sendAttempts")));
                alert.setCreatedTime(timeValue(map.get("createdTime")));
                alert.setUpdatedTime(timeValue(map.get("updatedTime")));
                String unb = (String) map.get("unb");
                if (unb != null && !unb.isBlank()) {
                    Long accountId = unbToAccountId.get(unb);
                    if (accountId == null) {
                        throw new IllegalStateException("找不到告警关联账号: " + unb);
                    }
                    alert.setAccountId(accountId);
                }
                alertEventMapper.insert(alert);
            } catch (Exception e) {
                throw new IllegalStateException("导入单条告警事件失败", e);
            }
        }
    }

    private String findAccountUnb(Long accountId) {
        if (accountId == null) return null;
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
            return null;
        }
        return account.getUnb();
    }

    private Integer integerValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        // 手工编辑的备份可能出现字符串数字，直接强转 Number 会 CCE 导致整包导入回滚
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("数值字段格式无效: " + value, e);
        }
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(value.toString());
    }

    private java.time.LocalDateTime timeValue(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.LocalDateTime ldt) return ldt;
        String str = value.toString();
        if (str.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(str);
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(str,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                return null;
            }
        }
    }
}