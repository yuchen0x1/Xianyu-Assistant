package com.feijimiao.xianyuassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步配置
 * 启用Spring的异步方法支持
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Value("${app.async.core-pool-size:5}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:100}")
    private int queueCapacity;

    @Value("${app.async.shutdown-await-seconds:60}")
    private int shutdownAwaitSeconds;

    @Value("${app.qr-login.executor.core-pool-size:2}")
    private int qrLoginCorePoolSize;

    @Value("${app.qr-login.executor.max-pool-size:4}")
    private int qrLoginMaxPoolSize;

    @Value("${app.qr-login.executor.queue-capacity:0}")
    private int qrLoginQueueCapacity;

    @Value("${app.qr-login.executor.shutdown-await-seconds:5}")
    private int qrLoginShutdownAwaitSeconds;
    
    /**
     * 自定义异步任务线程池
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(corePoolSize);
        
        // 最大线程数
        executor.setMaxPoolSize(maxPoolSize);
        
        // 队列容量
        executor.setQueueCapacity(queueCapacity);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("async-event-");
        
        // 拒绝策略：由调用线程处理
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        
        executor.initialize();
        return executor;
    }

    /**
     * QR login polling performs blocking network calls and must not occupy the shared async pool.
     */
    @Bean(name = "qrLoginExecutor")
    public ThreadPoolTaskExecutor qrLoginExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(qrLoginCorePoolSize);
        executor.setMaxPoolSize(qrLoginMaxPoolSize);
        executor.setQueueCapacity(qrLoginQueueCapacity);
        executor.setThreadNamePrefix("qr-login-monitor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(qrLoginShutdownAwaitSeconds);
        return executor;
    }

    /**
     * 捕获 void 异步任务的异常，避免异常只被线程池吞掉而没有可追踪日志。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> log.error(
                "异步任务执行失败: {}.{} (参数数量: {})",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                params == null ? 0 : params.length,
                throwable
        );
    }
}
