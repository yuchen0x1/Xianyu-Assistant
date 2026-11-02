package com.feijimiao.xianyuassistant.aspect;

import com.feijimiao.xianyuassistant.annotation.RateLimit;
import com.feijimiao.xianyuassistant.constants.OperationConstants;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.limit.WriteRateLimiter;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 写操作限流切面。
 *
 * <p>拦截标有 {@link RateLimit} 的方法，从参数中解析账号ID（参数名匹配，
 * 匹配不到时取第一个 Long 类型参数），按 (账号, 桶) 滑动窗口限流，
 * 超限时抛出业务异常，由全局异常处理器转成统一响应，并记录一条失败审计。</p>
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class RateLimitAspect {

    @Autowired
    private WriteRateLimiter writeRateLimiter;

    @Autowired
    private OperationLogService operationLogService;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        Long accountId = resolveAccountId(joinPoint);
        if (accountId == null) {
            // 解析不到账号ID时不做限流，避免误伤非账号维度调用
            return joinPoint.proceed();
        }

        long waitSeconds = writeRateLimiter.tryAcquire(
                accountId, rateLimit.bucket(), rateLimit.limit(), rateLimit.windowSeconds());
        if (waitSeconds > 0) {
            recordRateLimitAudit(accountId, rateLimit, waitSeconds);
            throw new BusinessException(429, rateLimit.message() + "（账号限流保护，约" + waitSeconds + "秒后重试）");
        }
        return joinPoint.proceed();
    }

    /**
     * 记录限流拦截的失败审计；审计异常不影响主流程。
     */
    private void recordRateLimitAudit(Long accountId, RateLimit rateLimit, long waitSeconds) {
        try {
            String limitMessage = rateLimit.message() + "（账号限流保护，约" + waitSeconds + "秒后重试）";
            operationLogService.log(accountId,
                    OperationConstants.Type.RATE_LIMIT,
                    rateLimit.bucket(),
                    "写操作限流拦截: bucket=" + rateLimit.bucket(),
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.ACCOUNT,
                    String.valueOf(accountId),
                    null, null, limitMessage, null);
        } catch (Exception e) {
            // 审计记录失败不影响限流主流程
            log.warn("限流审计记录失败: accountId={}, bucket={}", accountId, rateLimit.bucket());
        }
    }

    private Long resolveAccountId(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        // 1. 参数名匹配（需要 -parameters 编译参数，Spring Boot 默认开启）
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if ("accountId".equals(parameters[i].getName())) {
                return toLong(args[i]);
            }
        }
        // 2. 兜底：第一个 Long 类型参数
        for (Object arg : args) {
            if (arg instanceof Long) {
                return (Long) arg;
            }
            if (arg instanceof Number) {
                return ((Number) arg).longValue();
            }
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
