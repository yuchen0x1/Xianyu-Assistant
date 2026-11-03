package com.feijimiao.xianyuassistant.config;

import com.feijimiao.xianyuassistant.controller.SystemController;
import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import com.feijimiao.xianyuassistant.interceptor.AuthInterceptor;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.service.update.UpdateCheckService;
import com.feijimiao.xianyuassistant.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = SystemController.class,
        properties = {
                "app.version=2.1.8",
                "app.update-check.enabled=false",
                "app.update-check.cache-seconds=0"
        })
@Import({WebMvcConfig.class, AuthInterceptor.class})
class WebMvcUpdateAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private com.feijimiao.xianyuassistant.service.update.SelfUpdateService selfUpdateService;

    @MockitoBean
    private UpdateCheckService updateCheckService;

    @Test
    void anonymousUpdateCheckIsRejected() throws Exception {
        mockMvc.perform(get("/api/system/checkUpdate"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("暂未登录或token已经过期"));
    }

    @Test
    void authenticatedUpdateCheckStillReachesController() throws Exception {
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(authService.isTokenValid("valid-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(7L);
        when(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("operator");

        VersionInfoRespDTO disabled = new VersionInfoRespDTO();
        disabled.setCurrentVersion("2.1.8");
        disabled.setLatestVersion("2.1.8");
        disabled.setHasUpdate(false);
        disabled.setCheckStatus("disabled");
        when(updateCheckService.checkUpdate()).thenReturn(disabled);

        mockMvc.perform(get("/api/system/checkUpdate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.currentVersion").value("2.1.8"))
                .andExpect(jsonPath("$.data.checkStatus").value("disabled"));

        verify(jwtUtil).validateToken("valid-token");
        verify(authService).isTokenValid("valid-token");
    }
}
