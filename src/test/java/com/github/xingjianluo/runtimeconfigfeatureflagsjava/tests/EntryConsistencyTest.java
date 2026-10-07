package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigSnapshot;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagConfigCodec;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证服务内部调用与 HTTP 入口对同一开关、同一标识给出完全一致的结论，
 * 以及热更新并发下两个入口都只能观察到完整版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntryConsistencyTest {

    @Autowired
    private FeatureFlagService flagService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper om = new ObjectMapper();

    private FlagDecision decideViaHttp(String flag, String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/flags/{name}/check", flag).param("id", id))
                .andExpect(status().isOk())
                .andReturn();
        return om.readValue(result.getResponse().getContentAsString(), FlagDecision.class);
    }

    @Test
    void internalAndHttpEntriesGiveIdenticalDecisions() throws Exception {
        // 覆盖已知开关的多个标识：放行与不放行两侧都要验证
        List<String> identifiers = List.of("user-1", "user-42", "user-777", "alice", "bob");
        System.out.printf("[入口一致] 输入: flag=new-checkout, ids=%s; "
                + "判定依据: 两个入口共用同一判定路径与同一快照, 结论逐字段一致%n", identifiers);
        for (String id : identifiers) {
            FlagDecision internal = flagService.decide("new-checkout", id);
            FlagDecision http = decideViaHttp("new-checkout", id);
            System.out.printf("[入口一致] id=%s -> 内部: allowed=%s bucket=%d pct=%s ver=%d reason=%s | "
                            + "HTTP: allowed=%s bucket=%d pct=%s ver=%d reason=%s%n",
                    id, internal.allowed(), internal.bucket(), internal.percentage(), internal.version(),
                    internal.reason(),
                    http.allowed(), http.bucket(), http.percentage(), http.version(), http.reason());
            assertEquals(internal, http,
                    "同一标识在两个入口的结论必须完全一致: internal=" + internal + ", http=" + http);
        }
    }

    @Test
    void unknownFlagIsDeniedInBothEntries() throws Exception {
        FlagDecision internal = flagService.decide("definitely-unknown-flag", "user-1");
        FlagDecision http = decideViaHttp("definitely-unknown-flag", "user-1");

        System.out.printf("[未知开关] 输入: flag=definitely-unknown-flag, id=user-1; "
                + "判定依据: 未知开关在两个入口都必须明确拒绝, 绝不静默放行%n");
        System.out.printf("[未知开关] 结果: 内部 -> %s | HTTP -> %s%n", internal, http);

        assertFalse(internal.allowed(), "内部入口：未知开关不得放行");
        assertFalse(http.allowed(), "HTTP 入口：未知开关不得放行");
        assertEquals(FlagDecision.Reason.UNKNOWN_FLAG, internal.reason());
        assertEquals(FlagDecision.Reason.UNKNOWN_FLAG, http.reason());
        assertEquals(internal, http, "未知开关在两个入口的结论必须一致");
    }

    @Test
    void rejectedCommitIsVisiblyRejectedOverHttpAndOldValuesStayUsable() throws Exception {
        long before = configService.current().version();
        String badBody = "{\"service.thread-pool.size\": \"not-a-number\"}";

        MvcResult rejected = mockMvc.perform(post("/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        Map<?, ?> body = om.readValue(rejected.getResponse().getContentAsString(), Map.class);

        System.out.printf("[拒绝可见] 输入: POST /config/overrides %s; "
                + "判定依据: 非法提交返回 422 且 accepted=false, 一眼能看出没通过%n", badBody);
        System.out.printf("[拒绝可见] 结果: HTTP=422, body.accepted=%s, reason=%s, 当前版本=%d%n",
                body.get("accepted"), body.get("reason"), configService.current().version());

        assertEquals(Boolean.FALSE, body.get("accepted"), "响应体必须明确 accepted=false");
        assertEquals("VALIDATION_FAILED", body.get("reason"));
        assertEquals(before, configService.current().version(), "拒绝后版本不得前进");

        // 旧值仍然可用：两个入口的判定不受影响
        FlagDecision internal = flagService.decide("new-checkout", "user-42");
        FlagDecision http = decideViaHttp("new-checkout", "user-42");
        assertEquals(internal, http, "拒绝非法提交后两个入口结论仍一致");
        assertEquals(before, internal.version(), "判定仍依据旧版本");
    }

    @Test
    void concurrentDecisionsDuringHotUpdateSeeOnlyCompleteVersions() throws Exception {
        // 热更新：每次提交同时改配置与开关比例，两者必须同版本出现
        int commits = 20;
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistencies = new AtomicInteger();
        List<Long> observedVersions = new CopyOnWriteArrayList<>();

        Runnable consistencyCheck = () -> {
            // 判定结论的版本、比例必须与该版本快照中的开关定义一致
            FlagDecision d = flagService.decide("new-checkout", "user-42");
            ConfigSnapshot snap = configService.version(d.version());
            if (snap == null) {
                inconsistencies.incrementAndGet();
                return;
            }
            FeatureFlag flagAtVersion = FlagConfigCodec.decodeOne("new-checkout", snap.values());
            if (flagAtVersion == null || d.percentage() == null
                    || d.percentage() != flagAtVersion.percentage()) {
                inconsistencies.incrementAndGet();
            }
            observedVersions.add(d.version());
        };

        for (int i = 0; i < readers; i++) {
            boolean viaHttp = i % 2 == 0;
            pool.submit(() -> {
                await(start);
                for (int j = 0; j < 300; j++) {
                    try {
                        if (viaHttp) {
                            // HTTP 入口：反序列化后做同样的一致性校验
                            FlagDecision d = decideViaHttp("new-checkout", "user-42");
                            ConfigSnapshot snap = configService.version(d.version());
                            FeatureFlag flagAtVersion = snap == null ? null
                                    : FlagConfigCodec.decodeOne("new-checkout", snap.values());
                            if (flagAtVersion == null || d.percentage() != flagAtVersion.percentage()) {
                                inconsistencies.incrementAndGet();
                            }
                            observedVersions.add(d.version());
                        } else {
                            consistencyCheck.run();
                        }
                    } catch (Exception e) {
                        inconsistencies.incrementAndGet();
                    }
                }
            });
        }
        pool.submit(() -> {
            await(start);
            for (int i = 0; i < commits; i++) {
                int pct = (i % 10) * 10;
                flagService.commitAll(
                        Map.of("service.greeting", "hot-" + i),
                        Map.of("new-checkout", new FeatureFlag("new-checkout", true, pct)),
                        null);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试应在限时内完成");

        System.out.printf("[并发完整版本] 输入: 1 写线程 x %d 次提交(配置+开关同改), "
                        + "%d 读线程(半内部半HTTP) x 300 次判定; "
                        + "判定依据: 每次判定的版本号与比例必须属于同一个完整快照%n", commits, readers);
        System.out.printf("[并发完整版本] 结果: 不一致观察=%d, 观察到的版本数=%d, 最终版本=%d%n",
                inconsistencies.get(), observedVersions.stream().distinct().count(),
                configService.current().version());

        assertEquals(0, inconsistencies.get(),
                "热更新并发下不允许观察到新旧混合的中间态");
        assertTrue(observedVersions.stream().distinct().count() >= 2,
                "应观察到多个版本（更新确实在并发进行）");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
