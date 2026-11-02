package com.feijimiao.xianyuassistant.rbac.aspect;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号范围切面：参数解析必须覆盖字符串形态，否则 {@code {"accountId":"7"}}
 * 会被静默忽略，而业务代码照常按 7 执行（字符串绕过 → 写操作越权）。
 */
class AccountScopeAspectTest {

    private RbacService rbacService;
    private AccountScopeAspect aspect;

    @BeforeEach
    void setUp() {
        rbacService = mock(RbacService.class);
        aspect = new AccountScopeAspect(rbacService);
        UserContext.set(1L, "operator");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProceedingJoinPoint joinPoint(String[] names, Object[] args) {
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getParameterNames()).thenReturn(names);
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(args);
        try {
            when(point.proceed()).thenReturn("ok");
        } catch (Throwable ignored) {
            // 不会发生：mock 的 proceed 不抛异常
        }
        return point;
    }

    @Test
    void numericAccountIdOutsideScopeIsRejected() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(joinPoint(new String[]{"accountId"}, new Object[]{7L})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void stringAccountIdIsParsedAndRejected() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(joinPoint(new String[]{"accountId"}, new Object[]{"7"})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void stringAccountIdInsideScopeIsAllowed() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(7L));

        Object result = aspect.enforceAccountScope(
                joinPoint(new String[]{"xianyuAccountId"}, new Object[]{" 7 "}));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void nonNumericAccountIdFailsClosed() {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(7L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(joinPoint(new String[]{"accountId"}, new Object[]{"abc"})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void accountIdInsideRequestBodyMapIsChecked() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(
                joinPoint(new String[]{"body"}, new Object[]{Map.of("accountId", "7")})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void accountIdInsideNestedJdkCollectionsIsChecked() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(
                joinPoint(new String[]{"body"}, new Object[]{List.of(Map.of("accountId", 7L))})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void accountIdInsideObjectArrayIsChecked() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(
                joinPoint(new String[]{"body"}, new Object[]{new Object[]{Map.of("accountId", "7")}})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void accountIdOnRequestBeanIsChecked() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> aspect.enforceAccountScope(
                joinPoint(new String[]{"body"}, new Object[]{new AccountRequest(7L)})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void accountIdOnRequestBeanInsideScopeIsAllowed() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of(7L));

        Object result = aspect.enforceAccountScope(
                joinPoint(new String[]{"body"}, new Object[]{new AccountRequest(7L)}));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void emptyScopeRejectsEveryExplicitAccount() {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> aspect.enforceAccountScope(joinPoint(new String[]{"accountId"}, new Object[]{10L})))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void pointcutIncludesReviewAndCommandControllers() throws Exception {
        var method = AccountScopeAspect.class.getMethod("enforceAccountScope", ProceedingJoinPoint.class);
        var advice = method.getAnnotation(org.aspectj.lang.annotation.Around.class);

        assertThat(advice.value()).contains("review.controller").contains("command..");
    }

    @Test
    void adminScopeSkipsAllChecks() throws Throwable {
        when(rbacService.getVisibleAccountIds(1L)).thenReturn(null);

        Object result = aspect.enforceAccountScope(joinPoint(new String[]{"accountId"}, new Object[]{999L}));

        assertThat(result).isEqualTo("ok");
        verify(rbacService).getVisibleAccountIds(1L);
    }

    /** 模拟请求体 DTO：字段通过 getter 暴露，切面依赖 BeanWrapper 读取。 */
    private static final class AccountRequest {

        private final Long accountId;

        private AccountRequest(Long accountId) {
            this.accountId = accountId;
        }

        public Long getAccountId() {
            return accountId;
        }
    }
}
