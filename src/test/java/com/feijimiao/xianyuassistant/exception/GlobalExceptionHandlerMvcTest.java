package com.feijimiao.xianyuassistant.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器 MVC 层集成测试（standalone MockMvc，不启动 Spring 上下文）：
 * 验证异常契约经过真实 HandlerMapping/HttpMessageConverter 后的最终 JSON 形状，
 * 防止"前端把业务码当登录态"一类的契约回归。
 */
class GlobalExceptionHandlerMvcTest {

    private MockMvc mockMvc;

    @RestController
    static class ProbeController {

        @PostMapping("/probe/captcha")
        String captcha() {
            throw new CaptchaRequiredException("https://captcha.example.com/verify");
        }

        @PostMapping("/probe/validate")
        String validate(@Valid @RequestBody ProbeReq req) {
            return "ok";
        }
    }

    static class ProbeReq {

        @NotBlank(message = "名称不能为空")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("验证码异常经 MVC 层输出 code=1001，绝不能是 401")
    void captchaExceptionRespondsWithCode1001() throws Exception {
        mockMvc.perform(post("/probe/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.data.needCaptcha").value(true))
                .andExpect(jsonPath("$.data.captchaUrl").value("https://captcha.example.com/verify"));
    }

    @Test
    @DisplayName("参数校验失败经 MVC 层输出 code=400 而非 404")
    void validationFailureRespondsWithCode400() throws Exception {
        mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("name: 名称不能为空"));
    }

    @Test
    @DisplayName("请求体格式错误输出 code=1002")
    void malformedBodyRespondsWithCode1002() throws Exception {
        mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }
}
