package com.feijimiao.xianyuassistant.exception;

import com.feijimiao.xianyuassistant.common.ResultObject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器单元测试。
 *
 * <p>覆盖 7 类异常的 code 与 msg 字段，确保前后端契约稳定。</p>
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("BusinessException 使用其自身 code")
    void handleBusinessExceptionUsesItsCode() {
        BusinessException ex = new BusinessException(401, "账号不存在");

        ResultObject<Void> result = handler.handleBusinessException(ex);

        assertNotNull(result);
        assertEquals(401, result.getCode());
        assertEquals("账号不存在", result.getMsg());
    }

    @Test
    @DisplayName("BusinessException 默认 code 为 500")
    void handleBusinessExceptionDefaultCode() {
        BusinessException ex = new BusinessException("通用错误");

        ResultObject<Void> result = handler.handleBusinessException(ex);

        assertEquals(500, result.getCode());
        assertEquals("通用错误", result.getMsg());
    }

    @Test
    @DisplayName("MethodArgumentNotValid 返回 400 并拼接字段错误")
    void handleMethodArgumentNotValidJoinsFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "username", "用户名不能为空"));
        bindingResult.addError(new FieldError("target", "password", "密码长度6-64"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResultObject<Void> result = handler.handleMethodArgumentNotValid(ex);

        assertEquals(400, result.getCode());
        assertTrue(result.getMsg().contains("username: 用户名不能为空"));
        assertTrue(result.getMsg().contains("password: 密码长度6-64"));
    }

    @Test
    @DisplayName("验证码异常返回 1001 而非 401，避免被前端当作登出处理")
    void handleCaptchaRequiredReturns1001Not401() {
        CaptchaRequiredException ex = new CaptchaRequiredException("https://example.com/captcha");

        ResultObject<java.util.Map<String, Object>> result = handler.handleCaptchaRequiredException(ex);

        assertEquals(1001, result.getCode());
        assertNotNull(result.getData());
        assertEquals(Boolean.TRUE, result.getData().get("needCaptcha"));
        assertEquals("https://example.com/captcha", result.getData().get("captchaUrl"));
    }

    @Test
    @DisplayName("验证码异常在无验证链接时仍返回 1001 与 needCaptcha 标记")
    void handleCaptchaRequiredWithoutUrlStillUses1001() {
        CaptchaRequiredException ex = new CaptchaRequiredException("");

        ResultObject<java.util.Map<String, Object>> result = handler.handleCaptchaRequiredException(ex);

        assertEquals(1001, result.getCode());
        assertEquals(Boolean.TRUE, result.getData().get("needCaptcha"));
    }

    @Test
    @DisplayName("MissingServletRequestParameter 返回 1002 + 参数名")
    void handleMissingParamReturnsCode1002() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("cookieId", "String");

        ResultObject<Void> result = handler.handleMissingParam(ex);

        assertEquals(1002, result.getCode());
        assertTrue(result.getMsg().contains("cookieId"));
    }

    @Test
    @DisplayName("HttpMessageNotReadable 返回 1002 + 通用提示")
    void handleNotReadableReturnsCode1002() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("malformed body");

        ResultObject<Void> result = handler.handleNotReadable(ex);

        assertEquals(1002, result.getCode());
        assertEquals("请求体格式错误", result.getMsg());
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupported 返回 500 + 方法名")
    void handleMethodNotSupportedReturnsMethodName() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE");

        ResultObject<Void> result = handler.handleMethodNotSupported(ex);

        assertEquals(500, result.getCode());
        assertTrue(result.getMsg().contains("DELETE"));
    }

    @Test
    @DisplayName("ConstraintViolation 返回 400 + 拼接消息")
    void handleConstraintViolationJoinsMessages() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> v = mock(ConstraintViolation.class);
        when(v.getMessage()).thenReturn("不能为空");
        Path path = mock(Path.class);
        when(v.getPropertyPath()).thenReturn(path);
        Set<ConstraintViolation<?>> set = new HashSet<>(Collections.singletonList(v));

        ConstraintViolationException ex = new ConstraintViolationException("invalid", set);

        ResultObject<Void> result = handler.handleConstraintViolation(ex);

        assertEquals(400, result.getCode());
        assertEquals("不能为空", result.getMsg());
    }

    @Test
    @DisplayName("未捕获异常返回 500 + 友好提示")
    void handleUnexpectedExceptionReturnsFriendlyMessage() {
        Exception ex = new RuntimeException("DB down");

        ResultObject<Void> result = handler.handleException(ex);

        assertEquals(500, result.getCode());
        assertEquals("系统繁忙，请稍后再试", result.getMsg());
    }
}
