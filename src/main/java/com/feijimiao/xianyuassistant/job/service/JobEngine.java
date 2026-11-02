package com.feijimiao.xianyuassistant.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.job.entity.JobDeadLetter;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.entity.JobExecution;
import com.feijimiao.xianyuassistant.job.mapper.JobDeadLetterMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobDefinitionMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobExecutionMapper;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * 统一任务引擎：幂等创建、持久化调度、指数退避重试、死信归档。
 * <p>
 * 任务流：createJob (幂等) → schedule (按nextRunTime轮询) → execute (Worker) → 
 *        成功→标记SUCCESS / 失败→重试退避 / 超限→死信
 * </p>
 */
@Slf4j
@Service
public class JobEngine {

    private final JobDefinitionMapper jobDefMapper;
    private final JobExecutionMapper jobExecMapper;
    private final JobDeadLetterMapper deadLetterMapper;
    private final ObjectMapper objectMapper;
    private final RbacService rbacService;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public JobEngine(JobDefinitionMapper jobDefMapper, JobExecutionMapper jobExecMapper,
                     JobDeadLetterMapper deadLetterMapper, ObjectMapper objectMapper,
                     RbacService rbacService, PlatformTransactionManager transactionManager) {
        this.jobDefMapper = jobDefMapper;
        this.jobExecMapper = jobExecMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.objectMapper = objectMapper;
        this.rbacService = rbacService;
        this.transactionManager = transactionManager;
    }

    /** Backward-compatible constructor for lightweight callers/tests without RBAC. */
    public JobEngine(JobDefinitionMapper jobDefMapper, JobExecutionMapper jobExecMapper,
                     JobDeadLetterMapper deadLetterMapper, ObjectMapper objectMapper) {
        this(jobDefMapper, jobExecMapper, deadLetterMapper, objectMapper, null, null);
    }

    /** 注册的 Worker 映射：jobType → (jobDef, params) → 是否成功 */
    private final Map<String, BiFunction<JobDefinition, Map<String, Object>, Boolean>> workers = new ConcurrentHashMap<>();

    /** Job 轮询调度线程池 */
    private ScheduledExecutorService scheduler;
    /** Worker 执行线程池 */
    private ExecutorService workerPool;
    private final Set<Long> queuedJobIds = ConcurrentHashMap.newKeySet();

    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final int WORKER_POOL_SIZE = 4;
    private static final int WORKER_QUEUE_CAPACITY = 32;
    private static final int CLAIM_BATCH_SIZE = 8;
    private static final long RUNNING_LEASE_SECONDS = 300;
    private static final long STALE_RUNNING_AFTER_SECONDS = 360;
    /** 长任务租约续期间隔：必须明显小于 RUNNING_LEASE_SECONDS，防止执行中被误判死任务二次派发 */
    private static final long LEASE_RENEW_INTERVAL_SECONDS = 120;
    private static final int DEFAULT_MAX_RETRIES = 3;
    /** 指数退避基数（秒） */
    private static final long BACKOFF_BASE_SECONDS = 30;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-scheduler");
            t.setDaemon(true);
            return t;
        });
        workerPool = new ThreadPoolExecutor(
                WORKER_POOL_SIZE, WORKER_POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "job-worker");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        scheduler.scheduleWithFixedDelay(this::pollAndExecute, 10, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("任务引擎已启动: workerPool={}, pollInterval={}s", WORKER_POOL_SIZE, POLL_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (workerPool != null) workerPool.shutdown();
        log.info("任务引擎已关闭");
    }

    // ==================== 注册 Worker ====================

    /**
     * 注册一种任务类型的处理器。
     * @param jobType 任务类型
     * @param handler 处理器，(jobDef, params) -> 是否成功
     */
    public void registerWorker(String jobType, BiFunction<JobDefinition, Map<String, Object>, Boolean> handler) {
        workers.put(jobType, handler);
        log.debug("Worker 已注册: jobType={}", jobType);
    }

    // ==================== 创建任务（幂等） ====================

    /**
     * 幂等创建任务：idempotencyKey 相同时不会重复创建。
     * 如果任务已存在且处于 PENDING / FAILED 状态，则更新参数并重置重试。
     */
    public JobDefinition createJob(String jobType, String idempotencyKey, Map<String, Object> params,
                                    Long accountId, LocalDateTime nextRunTime) {
        return createJob(jobType, idempotencyKey, params, accountId, nextRunTime, DEFAULT_MAX_RETRIES, 0);
    }

    /**
     * SQLite 在 WAL 模式下，事务先读（幂等检查）后写（INSERT），期间其他连接一旦提交写入
     * 就会报 SQLITE_BUSY_SNAPSHOT——这类冲突等锁无效，必须重开事务整体重试。
     * 消息事件监听线程与任务轮询线程高频并发写入，这里统一做忙碌重试。
     */
    public JobDefinition createJob(String jobType, String idempotencyKey, Map<String, Object> params,
                                    Long accountId, LocalDateTime nextRunTime, int maxRetries, int priority) {
        return createJob(jobType, idempotencyKey, params, accountId, nextRunTime, maxRetries, priority, false);
    }

    /**
     * rearmOnSuccess=true 时，同键任务若已 SUCCESS 则重新置为 PENDING 并重置计时。
     * 用于"同一会话每条新消息都要重新触发"的场景（如自动回复延时任务）。
     */
    public JobDefinition createJob(String jobType, String idempotencyKey, Map<String, Object> params,
                                    Long accountId, LocalDateTime nextRunTime, int maxRetries, int priority,
                                    boolean rearmOnSuccess) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeInTransaction(() ->
                        doCreateJob(jobType, idempotencyKey, params, accountId, nextRunTime,
                                maxRetries, priority, rearmOnSuccess));
            } catch (DataAccessException e) {
                if (attempt >= 3 || !isSqliteBusy(e)) {
                    throw e;
                }
                log.warn("任务创建遇到数据库忙碌冲突，重试第{}次: key={}", attempt, idempotencyKey);
                try {
                    Thread.sleep(120L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private JobDefinition executeInTransaction(java.util.function.Supplier<JobDefinition> action) {
        if (transactionManager == null) {
            // 轻量测试路径：无事务管理器时直接执行
            return action.get();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private static boolean isSqliteBusy(Throwable e) {
        while (e != null) {
            String message = e.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY") || message.contains("database is locked"))) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private JobDefinition doCreateJob(String jobType, String idempotencyKey, Map<String, Object> params,
                                       Long accountId, LocalDateTime nextRunTime, int maxRetries, int priority,
                                       boolean rearmOnSuccess) {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        maxRetries = Math.max(0, Math.min(maxRetries, 10));
        // 幂等检查
        JobDefinition existing = jobDefMapper.selectOne(
                new LambdaQueryWrapper<JobDefinition>().eq(JobDefinition::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            // 可运行状态（PENDING/FAILED）直接重置计时；rearmOnSuccess 时 SUCCESS 任务
            // 也重新入队——同一会话每条新消息都需要重新触发延时回复。
            boolean runnable = "PENDING".equals(existing.getStatus()) || "FAILED".equals(existing.getStatus());
            boolean rearm = rearmOnSuccess && "SUCCESS".equals(existing.getStatus());
            if (runnable || rearm) {
                String previousStatus = existing.getStatus();
                existing.setStatus("PENDING");
                existing.setParamsJson(toJson(params));
                existing.setRetryCount(0);
                existing.setLastError(null);
                existing.setNextRunTime(nextRunTime);
                existing.setLeaseUntil(null);
                existing.setMaxRetries(maxRetries);
                existing.setPriority(priority);
                existing.setUpdatedTime(LocalDateTime.now());
                jobDefMapper.updateById(existing);
                log.info("任务重新计时: key={}, nextRun={}, 原状态={}", idempotencyKey, nextRunTime, previousStatus);
            }
            return existing;
        }

        JobDefinition job = new JobDefinition();
        job.setJobType(jobType);
        job.setIdempotencyKey(idempotencyKey);
        job.setPriority(priority);
        job.setStatus("PENDING");
        job.setParamsJson(toJson(params));
        job.setAccountId(accountId);
        job.setMaxRetries(maxRetries);
        job.setRetryCount(0);
        job.setNextRunTime(nextRunTime);
        job.setLeaseUntil(null);
        try {
            jobDefMapper.insert(job);
            log.info("任务已创建: type={}, key={}, nextRun={}", jobType, idempotencyKey, nextRunTime);
            return job;
        } catch (DataIntegrityViolationException e) {
            // A concurrent creator may win between the read and insert. Re-read only
            // on the unique-key violation and return the canonical existing task.
            JobDefinition concurrent = jobDefMapper.selectOne(
                    new LambdaQueryWrapper<JobDefinition>()
                            .eq(JobDefinition::getIdempotencyKey, idempotencyKey));
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
    }

    // ==================== 调度执行 ====================

    /**
     * 构建到期任务查询（PENDING / FAILED 且到执行时间）。
     * 注意：MyBatis-Plus apply 占位符必须使用 {0} 形式，使用 ? 会在真实 SQL 绑定时报错。
     */
    QueryWrapper<JobDefinition> dueJobQuery(LocalDateTime now) {
        return new QueryWrapper<JobDefinition>()
                .in("status", "PENDING", "FAILED")
                .apply("next_run_time IS NULL OR next_run_time <= {0}", now)
                .orderByDesc("priority")
                .last("LIMIT " + CLAIM_BATCH_SIZE);
    }

    /** 调度线程：扫描到期任务并提交到有界 Worker 池。 */
    private void pollAndExecute() {
        try {
            LocalDateTime now = LocalDateTime.now();
            recoverStaleRunningJobs(now);
            if (!(workerPool instanceof ThreadPoolExecutor executor)) return;
            int capacity = executor.getQueue().remainingCapacity() +
                    Math.max(0, WORKER_POOL_SIZE - executor.getActiveCount());
            if (capacity <= 0) return;
            List<JobDefinition> dueJobs = jobDefMapper.selectList(dueJobQuery(now));
            int submitted = 0;
            for (JobDefinition job : dueJobs) {
                if (submitted >= capacity || job.getId() == null || !queuedJobIds.add(job.getId())) continue;
                try {
                    executor.execute(() -> {
                        try {
                            executeJob(job);
                        } finally {
                            queuedJobIds.remove(job.getId());
                        }
                    });
                    submitted++;
                } catch (RejectedExecutionException e) {
                    queuedJobIds.remove(job.getId());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("任务轮询异常", e);
        } catch (Throwable e) {
            // 调度循环抛出 Error 会让 scheduleWithFixedDelay 静默取消后续所有轮询；
            // 记录后继续循环，避免一次 OOM/类加载失败使整个任务引擎停摆。
            log.error("任务轮询发生严重错误", e);
        }
    }

    /** 将丢失 Worker 的 RUNNING 任务恢复为可重试状态。 */
    private void recoverStaleRunningJobs(LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(STALE_RUNNING_AFTER_SECONDS);
        UpdateWrapper<JobDefinition> wrapper = new UpdateWrapper<JobDefinition>()
                .eq("status", "RUNNING")
                .and(w -> w.lt("lease_until", now).or().isNull("lease_until"))
                .lt("updated_time", cutoff)
                .set("status", "FAILED")
                .set("next_run_time", now)
                .set("last_error", "Worker lease expired; task recovered")
                .set("lease_until", null)
                .set("updated_time", now);
        int recovered = jobDefMapper.update(null, wrapper);
        if (recovered > 0) log.warn("已恢复过期 RUNNING 任务: count={}", recovered);
    }

    /** Worker 线程：执行任务并更新状态 */
    private void executeJob(JobDefinition job) {
        // 乐观锁：CAS 更新状态为 RUNNING，防止重复执行。
        // 同时以 updated_time 作为快照校验：轮询领取与实际执行之间若任务被
        // createJob 重排（新参数/新时间），本快照视为过期，放弃执行旧参数。
        UpdateWrapper<JobDefinition> claimWrapper = new UpdateWrapper<JobDefinition>()
                .eq("id", job.getId())
                .in("status", "PENDING", "FAILED");
        if (job.getUpdatedTime() != null) {
            claimWrapper.eq("updated_time", job.getUpdatedTime());
        }
        claimWrapper.set("status", "RUNNING")
                .set("lease_until", LocalDateTime.now().plusSeconds(RUNNING_LEASE_SECONDS))
                .set("updated_time", LocalDateTime.now());
        int updated = jobDefMapper.update(null, claimWrapper);
        if (updated == 0) {
            return; // 被其他 Worker 抢走，或快照已被重排失效
        }
        job.setStatus("RUNNING");

        // 长任务租约续期：执行期间周期性续 lease_until，避免执行超过
        // STALE_RUNNING_AFTER_SECONDS 后被 recoverStaleRunningJobs 判死并二次派发。
        ScheduledFuture<?> leaseRenewal = startLeaseRenewal(job.getId());
        try {
            runJob(job);
        } catch (Throwable e) {
            // 兜底：DB 写失败等未捕获异常不能让任务卡在 RUNNING 直到租约过期
            log.error("任务执行流程异常: jobId={}, type={}", job.getId(), job.getJobType(), e);
            try {
                UpdateWrapper<JobDefinition> failWrapper = new UpdateWrapper<JobDefinition>()
                        .eq("id", job.getId())
                        .eq("status", "RUNNING")
                        .set("status", "FAILED")
                        .set("next_run_time", LocalDateTime.now().plusSeconds(BACKOFF_BASE_SECONDS))
                        .set("last_error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        .set("lease_until", null)
                        .set("updated_time", LocalDateTime.now());
                jobDefMapper.update(null, failWrapper);
            } catch (Throwable fatal) {
                log.error("任务失败状态回写异常，等待租约恢复: jobId={}", job.getId(), fatal);
            }
        } finally {
            if (leaseRenewal != null) {
                leaseRenewal.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> startLeaseRenewal(Long jobId) {
        if (scheduler == null || scheduler.isShutdown()) {
            // 轻量调用路径（部分测试）未初始化调度器；无续期时退回租约自然过期语义
            return null;
        }
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                jobDefMapper.update(null, new UpdateWrapper<JobDefinition>()
                        .eq("id", jobId)
                        .eq("status", "RUNNING")
                        .set("lease_until", LocalDateTime.now().plusSeconds(RUNNING_LEASE_SECONDS))
                        .set("updated_time", LocalDateTime.now()));
            } catch (Exception e) {
                log.warn("租约续期失败: jobId={}, errorType={}", jobId, e.getClass().getSimpleName());
            }
        }, LEASE_RENEW_INTERVAL_SECONDS, LEASE_RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void runJob(JobDefinition job) {
        // 创建执行记录
        JobExecution exec = new JobExecution();
        exec.setJobId(job.getId());
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        int maxRetries = job.getMaxRetries() == null ? DEFAULT_MAX_RETRIES : job.getMaxRetries();
        job.setRetryCount(retryCount);
        job.setMaxRetries(maxRetries);
        exec.setAttempt(retryCount + 1);
        exec.setStatus("RUNNING");
        exec.setStartedAt(LocalDateTime.now());
        exec.setWorkerId("worker-" + Thread.currentThread().getId());
        jobExecMapper.insert(exec);

        boolean success = false;
        String errorMsg = null;
        try {
            BiFunction<JobDefinition, Map<String, Object>, Boolean> worker = workers.get(job.getJobType());
            if (worker == null) {
                errorMsg = "未注册的 Worker: " + job.getJobType();
                log.warn(errorMsg);
            } else {
                Map<String, Object> params = parseJson(job.getParamsJson());
                success = worker.apply(job, params);
                if (!success) {
                    errorMsg = "Worker 返回失败";
                }
            }
        } catch (Exception e) {
            errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("任务执行异常: jobId={}, type={}", job.getId(), job.getJobType(), e);
        }

        // 更新执行记录
        exec.setStatus(success ? "SUCCESS" : "FAILED");
        exec.setFinishedAt(LocalDateTime.now());
        exec.setErrorMessage(errorMsg);
        jobExecMapper.updateById(exec);

        // 更新任务状态
        job.setRetryCount(retryCount + 1);
        job.setLastError(errorMsg);
        if (success) {
            job.setStatus("SUCCESS");
            job.setRetryCount(0);
            job.setNextRunTime(null);
            job.setLeaseUntil(null);
            log.info("任务执行成功: id={}, type={}, key={}", job.getId(), job.getJobType(), job.getIdempotencyKey());
        } else {
            if (job.getRetryCount() < maxRetries) {
                job.setStatus("FAILED");
                job.setLeaseUntil(null);
                // 指数退避：30s, 1min, 2min, 4min...
                long backoffSeconds = BACKOFF_BASE_SECONDS * (long) Math.pow(2, job.getRetryCount() - 1);
                job.setNextRunTime(LocalDateTime.now().plusSeconds(backoffSeconds));
                log.warn("任务执行失败，{}次后重试: id={}, type={}, backoff={}s",
                        job.getRetryCount(), job.getId(), job.getJobType(), backoffSeconds);
            } else {
                // 超过最大重试，进死信
                job.setStatus("DEAD_LETTER");
                job.setNextRunTime(null);
                moveToDeadLetter(job);
                log.warn("任务超过最大重试次数，已转入死信: id={}, type={}, key={}",
                        job.getId(), job.getJobType(), job.getIdempotencyKey());
            }
        }
        job.setUpdatedTime(LocalDateTime.now());
        jobDefMapper.updateById(job);
    }

    /** 将失败任务移入死信表（单条插入，SQLite 自动提交；本方法为内部调用，事务注解不会生效） */
    protected void moveToDeadLetter(JobDefinition job) {
        JobDeadLetter dl = new JobDeadLetter();
        dl.setJobId(job.getId());
        dl.setJobType(job.getJobType());
        dl.setIdempotencyKey(job.getIdempotencyKey());
        dl.setParamsJson(job.getParamsJson());
        dl.setAccountId(job.getAccountId());
        dl.setMaxRetries(job.getMaxRetries());
        dl.setFinalRetryCount(job.getRetryCount());
        dl.setLastError(job.getLastError());
        dl.setDeadAt(LocalDateTime.now());
        dl.setResolved(0);
        deadLetterMapper.insert(dl);
    }

    // ==================== 查询接口 ====================

    public List<JobDefinition> listJobs(String status, String jobType, int limit) {
        LambdaQueryWrapper<JobDefinition> qw = new LambdaQueryWrapper<>();
        if (status != null) qw.eq(JobDefinition::getStatus, status);
        if (jobType != null) qw.eq(JobDefinition::getJobType, jobType);
        applyJobAccountScope(qw);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        qw.orderByDesc(JobDefinition::getCreatedTime).last("LIMIT " + safeLimit);
        return jobDefMapper.selectList(qw);
    }

    public List<JobDeadLetter> listDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<JobDeadLetter> qw = new LambdaQueryWrapper<>();
        applyAccountScope(qw);
        return deadLetterMapper.selectList(qw.orderByDesc(JobDeadLetter::getDeadAt)
                .last("LIMIT " + safeLimit));
    }

    public List<JobExecution> getJobExecutions(Long jobId, int limit) {
        if (jobId == null) return List.of();
        JobDefinition job = jobDefMapper.selectById(jobId);
        if (job == null || !canManageAccount(job.getAccountId())) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jobExecMapper.selectList(
                new LambdaQueryWrapper<JobExecution>()
                        .eq(JobExecution::getJobId, jobId)
                        .orderByDesc(JobExecution::getId)
                        .last("LIMIT " + safeLimit));
    }

    /**
     * 取消未执行的持久化任务（PENDING / FAILED 置为 CANCELLED）。
     * 已开始执行（RUNNING）或已完成（SUCCESS / DEAD_LETTER）的任务不受影响。
     *
     * @param idempotencyKey 幂等键
     * @return 是否成功取消
     */
    @Transactional
    public boolean cancelJob(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        if (rbacService == null || UserContext.getUserId() == null) {
            return false;
        }
        JobDefinition job = jobDefMapper.selectOne(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getIdempotencyKey, idempotencyKey));
        if (job == null || !canManageAccount(job.getAccountId())) return false;
        UpdateWrapper<JobDefinition> cancelWrapper = new UpdateWrapper<JobDefinition>()
                .eq("id", job.getId())
                .in("status", "PENDING", "FAILED")
                .set("status", "CANCELLED")
                .set("lease_until", null)
                .set("updated_time", LocalDateTime.now());
        int updated = jobDefMapper.update(null, cancelWrapper);
        if (updated > 0) {
            log.info("任务已取消: key={}", idempotencyKey);
        }
        return updated > 0;
    }

    @Transactional
    public void resolveDeadLetter(Long id, String note, Long userId) {
        JobDeadLetter dl = deadLetterMapper.selectById(id);
        if (dl == null || !Integer.valueOf(0).equals(dl.getResolved()) || !canManageAccount(dl.getAccountId())) return;
        dl.setResolved(1);
        dl.setResolvedBy(userId);
        dl.setResolvedAt(LocalDateTime.now());
        dl.setResolutionNote(note);
        deadLetterMapper.updateById(dl);
    }

    @Transactional
    public void retryDeadLetter(Long id, Long userId) {
        JobDeadLetter dl = deadLetterMapper.selectById(id);
        if (dl == null || !Integer.valueOf(0).equals(dl.getResolved()) || !canManageAccount(dl.getAccountId())) return;
        JobDefinition job = jobDefMapper.selectById(dl.getJobId());
        if (job == null || !canManageAccount(job.getAccountId())) {
            log.warn("Cannot retry inaccessible/missing job: deadLetterId={}, jobId={}", id, dl.getJobId());
            return;
        }
        job.setStatus("PENDING");
        job.setRetryCount(0);
        job.setLastError(null);
        job.setNextRunTime(LocalDateTime.now());
        job.setLeaseUntil(null);
        job.setUpdatedTime(LocalDateTime.now());
        jobDefMapper.updateById(job);
        resolveDeadLetter(id, "人工重试", userId);
        log.info("死信任务已重新调度: deadLetterId={}, jobId={}", id, dl.getJobId());
    }

    private void applyJobAccountScope(LambdaQueryWrapper<JobDefinition> wrapper) {
        if (rbacService == null || UserContext.getUserId() == null) return;
        Long userId = UserContext.getUserId();
        if (rbacService.getUserRoleNames(userId).contains("ADMIN")) return;
        List<Long> visible = rbacService.getVisibleAccountIds(userId);
        if (visible == null || visible.isEmpty()) {
            wrapper.isNotNull(JobDefinition::getAccountId).eq(JobDefinition::getAccountId, Long.MIN_VALUE);
        } else {
            wrapper.in(JobDefinition::getAccountId, visible);
        }
    }

    private void applyAccountScope(LambdaQueryWrapper<JobDeadLetter> wrapper) {
        if (rbacService == null || UserContext.getUserId() == null) return;
        Long userId = UserContext.getUserId();
        if (rbacService.getUserRoleNames(userId).contains("ADMIN")) return;
        List<Long> visible = rbacService.getVisibleAccountIds(userId);
        if (visible == null || visible.isEmpty()) {
            wrapper.isNotNull(JobDeadLetter::getAccountId).eq(JobDeadLetter::getAccountId, Long.MIN_VALUE);
        } else {
            wrapper.in(JobDeadLetter::getAccountId, visible);
        }
    }

    private boolean canManageAccount(Long accountId) {
        if (rbacService == null || UserContext.getUserId() == null) return true;
        Long userId = UserContext.getUserId();
        boolean admin = rbacService.getUserRoleNames(userId).contains("ADMIN");
        if (accountId == null) return admin;
        if (admin) return true;
        List<Long> visible = rbacService.getVisibleAccountIds(userId);
        return visible != null && visible.contains(accountId);
    }

    // ==================== 工具 ====================

    private String toJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        try { return objectMapper.writeValueAsString(params); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }
}
