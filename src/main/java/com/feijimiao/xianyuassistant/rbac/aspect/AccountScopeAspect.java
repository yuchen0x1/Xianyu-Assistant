package com.feijimiao.xianyuassistant.rbac.aspect;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enforces account scope at the controller boundary.
 *
 * <p>List endpoints are not enough for isolation: detail, update, delivery,
 * message, and websocket endpoints can all receive an account id directly.
 * This aspect covers those request shapes consistently without duplicating the
 * same guard in every controller.</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class AccountScopeAspect {

    private static final Set<String> ACCOUNT_PROPERTIES = Set.of("accountId", "xianyuAccountId");

    private final RbacService rbacService;

    @Around(
            "execution(public * com.feijimiao.xianyuassistant.controller..*(..))"
                    + " || execution(public * com.feijimiao.xianyuassistant.job.controller..*(..))"
                    + " || execution(public * com.feijimiao.xianyuassistant.webhook..*(..))"
                    + " || execution(public * com.feijimiao.xianyuassistant.review.controller..*(..))"
                    + " || execution(public * com.feijimiao.xianyuassistant.command..*(..))")
    public Object enforceAccountScope(ProceedingJoinPoint joinPoint) throws Throwable {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return joinPoint.proceed();
        }

        List<Long> visibleAccountIds = rbacService.getVisibleAccountIds(userId);
        if (visibleAccountIds == null) {
            return joinPoint.proceed();
        }

        Set<Long> requestedAccountIds = Collections.newSetFromMap(new IdentityHashMap<>());
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < args.length; i++) {
            if (isAccountParameter(parameterNames, i)) {
                addAccountId(args[i], requestedAccountIds);
            }
            collectAccountIds(args[i], requestedAccountIds,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        if (requestedAccountIds.stream().anyMatch(id -> !visibleAccountIds.contains(id))) {
            throw new BusinessException(403, "无权访问该账号");
        }
        return joinPoint.proceed();
    }

    private boolean isAccountParameter(String[] parameterNames, int index) {
        if (parameterNames == null || index >= parameterNames.length) {
            return false;
        }
        return ACCOUNT_PROPERTIES.contains(parameterNames[index]);
    }

    /**
     * 解析账号 ID，兼容 {@code Number} 与字符串形态。
     *
     * <p>历史实现只识别 {@link Number}，导致 {@code {"accountId":"7"}} 这类
     * JSON 字符串形态被静默忽略，而业务代码 {@code Long.valueOf(...toString())}
     * 照常执行 —— 切面形同虚设，可进一步变成写操作越权。现在字符串同样参与
     * 解析，且解析失败直接拒绝（403），而不是"看不懂就放行"。</p>
     */
    private void addAccountId(Object value, Set<Long> result) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            result.add(number.longValue());
            return;
        }
        if (value instanceof CharSequence text) {
            String trimmed = text.toString().trim();
            if (trimmed.isEmpty()) {
                return;
            }
            try {
                result.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new BusinessException(403, "账号参数格式不合法");
            }
        }
    }

    /**
     * 递归收集参数中的账号 ID。
     *
     * <p>容器判断必须排在"是否叶子值"之前：历史实现把 {@code java.} 前缀的类
     * 一律当叶子值跳过，而 {@code Map.of}/{@code List.of}/{@code ArrayList} 的
     * 实现类同样以 {@code java.} 开头，导致 {@code {"accountId":"7"}} 这类请求体
     * 里的账号 ID 完全不被校验。现在容器优先展开，{@code java.} 前缀只用于
     * 阻止对 JDK 内部类型做反射。</p>
     */
    private void collectAccountIds(Object value, Set<Long> result, Set<Object> visited) {
        if (value == null || !visited.add(value)) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && ACCOUNT_PROPERTIES.contains(String.valueOf(entry.getKey()))) {
                    addAccountId(entry.getValue(), result);
                }
                collectAccountIds(entry.getValue(), result, visited);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectAccountIds(item, result, visited));
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    collectAccountIds(Array.get(value, i), result, visited);
                }
            }
            return;
        }
        if (isLeafValue(value)) {
            return;
        }

        BeanWrapper wrapper = new BeanWrapperImpl(value);
        for (String property : ACCOUNT_PROPERTIES) {
            if (wrapper.isReadableProperty(property)) {
                addAccountId(wrapper.getPropertyValue(property), result);
            }
        }
    }

    /**
     * 不含嵌套结构的叶子值：不做反射，避免误读 JDK 内部字段。
     */
    private boolean isLeafValue(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value.getClass().getName().startsWith("java.");
    }
}
