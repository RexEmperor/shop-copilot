package com.shopcopilot.business.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void should_return400_when_requestParamViolatesConstraint() throws Exception {
        mockMvc.perform(get("/test/validation").param("page", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void should_return400_when_requestBodyValidationFails() throws Exception {
        mockMvc.perform(post("/test/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("name: 不能为空"));
    }

    @Test
    void should_return400_when_jsonIsMalformed() throws Exception {
        mockMvc.perform(post("/test/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    void should_return404_when_businessExceptionIsThrown() throws Exception {
        mockMvc.perform(get("/test/business"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void should_return500_withoutLeakingExceptionMessage() throws Exception {
        mockMvc.perform(get("/test/system"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/validation")
        String validation(@RequestParam @Min(1) int page) {
            return String.valueOf(page);
        }

        @PostMapping("/test/body")
        String body(@Valid @RequestBody TestRequest request) {
            return request.name();
        }

        @GetMapping("/test/business")
        String business() {
            throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
        }

        @GetMapping("/test/system")
        String system() {
            throw new IllegalStateException("secret");
        }

        record TestRequest(@NotBlank(message = "不能为空") String name) {
        }
    }
}
