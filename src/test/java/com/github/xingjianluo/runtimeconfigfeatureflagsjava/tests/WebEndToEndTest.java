package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature.FeatureGateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨入口一致性与管理端端到端验证（使用仓库 config/ 下的示例文件）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebEndToEndTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigService configService;
    @Autowired private FeatureGateService featureGateService;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void httpEntryAndInternalServiceProduceIdenticalDecision() throws Exception {
        long v = configService.current().version();
        for (String flagAndId : new String[]{"new-checkout|user-1", "ai-search|user-2", "ghost|user-3"}) {
            String[] parts = flagAndId.split("\\|");
            String flag = parts[0], identity = parts[1];

            JsonNode http = json.readTree(mockMvc.perform(
                            get("/api/flags/{flag}/check", flag).param("identity", identity))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var internal = featureGateService.evaluate(flag, identity);

            System.out.printf("输入 flag=%s identity=%s | HTTP: enabled=%s bucket=%d reason=%s | 内部: enabled=%s bucket=%d%n",
                    flag, identity, http.get("enabled").asBoolean(), http.get("bucket").asLong(),
                    http.get("reason").asText(), internal.enabled(), internal.bucket());

            assertEquals(internal.enabled(), http.get("enabled").asBoolean());
            assertEquals(internal.bucket(), http.get("bucket").asLong());
            assertEquals(internal.reason(), http.get("reason").asText());
            assertEquals(v, http.get("configVersion").asLong(),
                    "同一请求窗口内 HTTP 与内部读取的应是同一版本");
        }
    }

    @Test
    void submitRejectionOverHttpKeepsOldConfigAndReturnsReasons() throws Exception {
        long v = configService.current().version();
        int before = configService.current().getInt("app.http.max-retries");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", v);
        body.put("values", Map.of(
                "app.http.max-retries", "999",
                "app.http.timeout-ms", "oops",
                "mystery.key", "1"));
        String resp = mockMvc.perform(post("/api/admin/config/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(resp);
        System.out.println("非法提交输入=" + body.get("values") + " => HTTP 409, 响应=" + resp);
        assertFalse(node.get("accepted").asBoolean());
        assertEquals(3, node.get("errors").size());
        assertEquals(v, configService.current().version(), "被拒绝后线上版本保持不变");
        assertEquals(before, configService.current().getInt("app.http.max-retries"),
                "非法配置不得半生效");
    }

    @Test
    void submitAndRollbackOverHttp() throws Exception {
        long v = configService.current().version();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", v);
        body.put("values", Map.of("app.http.max-retries", "7"));
        mockMvc.perform(post("/api/admin/config/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        assertEquals(7, configService.current().getInt("app.http.max-retries"));
        long afterSubmit = configService.current().version();
        System.out.println("HTTP 提交成功: v" + v + " -> v" + afterSubmit);

        mockMvc.perform(post("/api/admin/config/rollback/{version}", v))
                .andExpect(status().isOk());
        System.out.println("HTTP 回滚到 v" + v + " => 当前 v" + configService.current().version()
                + " max-retries=" + configService.current().getInt("app.http.max-retries"));
        assertNotEquals(7, configService.current().getInt("app.http.max-retries"));
    }

    @Test
    void snapshotEndpointExposesEvidence() throws Exception {
        String resp = mockMvc.perform(get("/api/config/snapshot"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(resp);
        System.out.println("快照版本=" + node.get("version") + " 键数量=" + node.get("values").size()
                + " 问题数=" + node.get("issues").size());
        assertNotNull(node.get("values").get("app.http.timeout-ms").get("detail").asText());
    }
}
