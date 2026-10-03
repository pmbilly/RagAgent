package com.ragagent.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 契约测试：对照 golden 响应（server/src/test/resources/contracts/）。
 * 每录一条 golden，这里加一条断言。JSON 字符串逐字符比对——契约就是字节级一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void unauthorizedMissingAuthentication() throws Exception {
        // golden: unauthorized-401.json（GET /api/v1/auth/me，无凭据）
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\":\"Unauthorized: missing authentication\"}"));
    }

    @Test
    void unauthorizedInvalidToken() throws Exception {
        // Authorization: Bearer <任意无效> → 401 invalid or expired
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer badtoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\":\"Unauthorized: invalid or expired token\"}"));
    }

    @Test
    void unauthenticatedUnknownPathAlso401() throws Exception {
        // 鉴权全局生效，未匹配路径同样 401（golden 已录）
        mockMvc.perform(get("/no-such-page"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\":\"Unauthorized: missing authentication\"}"));
    }
}
