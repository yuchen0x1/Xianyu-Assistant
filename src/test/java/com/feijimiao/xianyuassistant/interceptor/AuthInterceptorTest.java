package com.feijimiao.xianyuassistant.interceptor;

import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private AuthService authService;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(interceptor, "authService", authService);
    }

    @Test
    void missingTokenUsesStandardUnauthorizedStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handlerMethod = new HandlerMethod(
                new SecuredHandler(), SecuredHandler.class.getDeclaredMethod("handle"));

        assertFalse(interceptor.preHandle(request, response, handlerMethod));
        assertEquals(401, response.getStatus());
    }

    private static final class SecuredHandler {
        public void handle() {
        }
    }
}
