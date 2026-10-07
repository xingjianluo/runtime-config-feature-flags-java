package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务内部调用与 HTTP 入口一致性：同一开关同一标识在两入口给出的
 * 放行结果、桶号、判定原因、依据版本号完全一致；未知开关两入口均拒绝；
 * 热更新后两入口同步切换且结论一致；非法提交在 HTTP 层明确失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EntryConsistencyHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigService configService;
    @Autowired
    private FeatureFlagService flagService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private FlagDecision httpDecision(String flag, String id) {
        ResponseEntity<Map> resp = new TestRestTemplate().getForEntity(
                url("/flags/" + flag + "/check?id=" + id), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "判定查询应返回 200");
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        return new FlagDecision(
                (String) body.get("flag"),
                (String) body.get("identifier"),
                Boolean.TRUE.equals(body.get("allowed")),
                ((Number) body.get("bucket")).intValue(),
                body.get("percentage") == null ? null : ((Number) body.get("percentage")).intValue(),
                FlagDecision.Reason.valueOf((String) body.get("reason")),
                (String) body.get("explanation"),
                body.get("version") == null ? null : ((Number) body.get("version")).longValue());
    }

    private void assertDecisionEquals(String tag, FlagDecision internal, FlagDecision http) {
        System.out.printf("[%s] 内部=%s | HTTP=%s%n", tag, internal, http);
        assertEquals(internal.flag(), http.flag());
        assertEquals(internal.identifier(), http.identifier());
        assertEquals(internal.allowed(), http.allowed(), tag + ": 放行结果必须一致");
        assertEquals(internal.bucket(), http.bucket(), tag + ": 桶号必须一致");
        assertEquals(internal.percentage(), http.percentage(), tag + ": 比例档必须一致");
        assertEquals(internal.reason(), http.reason(), tag + ": 判定原因必须一致");
        assertEquals(internal.version(), http.version(), tag + ": 依据版本号必须一致");
        assertNotNull(internal.version(), tag + ": 绑定统一版本后结论必须带版本号");
    }

    @Test
    void internalAndHttpEntriesGiveIdenticalDecisions() {
        List<String> ids = List.of("user-1", "user-42", "user-99", "abc", "用户-张三");
        for (String id : ids) {
            FlagDecision internal = flagService.decide("new-checkout", id);
            FlagDecision http = httpDecision("new-checkout", id);
            assertDecisionEquals("已知开关/" + id, internal, http);
            assertEquals(internal.bucket() < internal.percentage(), internal.allowed());
        }
    }

    @Test
    void unknownFlagIsDeniedAtBothEntries() {
        FlagDecision internal = flagService.decide("no-such-flag", "user-7");
        FlagDecision http = httpDecision("no-such-flag", "user-7");

        assertFalse(internal.allowed(), "未知开关内部入口必须拒绝");
        assertEquals(FlagDecision.Reason.UNKNOWN_FLAG, internal.reason());
        assertFalse(http.allowed(), "未知开关 HTTP 入口必须拒绝，不得悄悄放行");
        assertEquals(FlagDecision.Reason.UNKNOWN_FLAG, http.reason());
        assertDecisionEquals("未知开关", internal, http);
        assertEquals(-1, http.bucket(), "未知开关桶号为 -1");
        assertNull(http.percentage());
    }

    @Test
    void bothEntriesMoveToNewVersionAfterUnifiedCommitAndStayConsistent() throws Exception {
        long vBefore = configService.state().version();
        // 经 HTTP 统一提交：配置与开关一起进入新版本（new-checkout 拉满 100%）
        Map<String, Object> req = Map.of(
                "configOverrides", Map.of("service.greeting", "via-http-commit"),
                "flags", List.of(
                        Map.of("name", "new-checkout", "enabled", true, "percentage", 100),
                        Map.of("name", "beta-search", "enabled", true, "percentage", 0),
                        Map.of("name", "legacy-report", "enabled", false, "percentage", 100)));
        ResponseEntity<Map> resp = new TestRestTemplate().postForEntity(
                url("/runtime/commit"), req, Map.class);
        System.out.printf("[热更新一致] HTTP 统一提交: status=%s, body=%s%n",
                resp.getStatusCode(), resp.getBody());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "合法提交应返回 200");
        assertEquals(vBefore + 1, configService.state().version(), "应进入新版本");
        assertEquals("via-http-commit", configService.state().config().get("service.greeting"));

        // 同一标识在两入口都依据新版本：100% 必然放行，版本号一致
        for (String id : List.of("user-1", "user-42", "user-777")) {
            FlagDecision internal = flagService.decide("new-checkout", id);
            FlagDecision http = httpDecision("new-checkout", id);
            assertTrue(internal.allowed() && http.allowed(), "100% 下两入口都应放行");
            assertDecisionEquals("热更新后/" + id, internal, http);
            assertEquals(vBefore + 1, internal.version(), "依据必须是新版本");
        }

        // 并发判定：热更新进行中，两入口任何结论都必须依据某个完整存在的版本
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < 4; t++) {
            int base = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 200; i++) {
                    int pct = (base * 25 + i) % 101;
                    configService.commit(new com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.CommitRequest(
                            null, List.of(new FeatureFlag("new-checkout", true, pct))), null);
                }
            });
        }
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                TestRestTemplate rest = new TestRestTemplate();
                for (int i = 0; i < 300; i++) {
                    String id = "u-" + i;
                    FlagDecision internal = flagService.decide("new-checkout", id);
                    ResponseEntity<Map> r = rest.getForEntity(
                            url("/flags/new-checkout/check?id=" + id), Map.class);
                    Map<String, Object> b = r.getBody();
                    long hv = ((Number) b.get("version")).longValue();
                    int hbucket = ((Number) b.get("bucket")).intValue();
                    int hpct = ((Number) b.get("percentage")).intValue();
                    boolean hallowed = Boolean.TRUE.equals(b.get("allowed"));
                    String hreason = (String) b.get("reason");

                    // 1) HTTP 结论必须依据一个真实存在的完整版本，且与该版本定义自洽
                    var httpVersion = configService.runtimeVersion(hv);
                    int pctOfHttpVersion = httpVersion.flag("new-checkout").percentage();
                    boolean httpSelfConsistent = httpVersion != null
                            && pctOfHttpVersion == hpct
                            && hallowed == (hbucket < hpct)
                            && (hallowed
                                    ? hreason.equals(FlagDecision.Reason.WITHIN_PERCENTAGE.name())
                                    : hreason.equals(FlagDecision.Reason.OUTSIDE_PERCENTAGE.name()));
                    // 2) 内部结论与其依据版本自洽
                    var internalVersion = configService.runtimeVersion(internal.version());
                    boolean internalSelfConsistent =
                            internalVersion.flag("new-checkout").percentage() == internal.percentage()
                                    && internal.allowed() == (internal.bucket() < internal.percentage());
                    // 3) 桶号只依赖 (flag,id)，任何版本、任何入口都必须相同
                    boolean bucketStable = hbucket == internal.bucket();
                    // 4) 恰好取样到同一版本的样本，两入口必须逐项完全一致
                    boolean sameVersionIdentical = hv != internal.version()
                            || (hallowed == internal.allowed()
                                    && hpct == internal.percentage()
                                    && hreason.equals(internal.reason().name()));
                    if (!httpSelfConsistent || !internalSelfConsistent
                            || !bucketStable || !sameVersionIdentical) {
                        bad.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        System.out.printf("[热更新并发] 两入口并发判定不一致计数=%d, 最终版本=%d%n",
                bad.get(), configService.state().version());
        assertEquals(0, bad.get(), "热更新并发下两入口必须一致且依据真实完整版本");
    }

    @Test
    void invalidCommitOverHttpIsExplicitlyRejectedAndOldValuesStay() {
        long vBefore = configService.state().version();
        Map<String, Object> req = Map.of(
                "configOverrides", Map.of("service.thread-pool.size", "not-a-number"),
                "flags", List.of(
                        Map.of("name", "new-checkout", "enabled", false, "percentage", 25)));
        ResponseEntity<UpdateResult> resp = new TestRestTemplate().postForEntity(
                url("/runtime/commit"), req, UpdateResult.class);

        System.out.printf("[HTTP 拒绝] 非法提交: status=%s, body=%s%n",
                resp.getStatusCode(), resp.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(),
                "非法提交必须在入口上一眼可辨（422），不能像成功返回");
        UpdateResult result = resp.getBody();
        assertNotNull(result);
        assertFalse(result.accepted(), "响应体必须显式标记 accepted=false");
        assertNull(result.version());
        assertEquals(vBefore, configService.state().version(), "拒绝后版本不得变化");
        assertTrue(configService.state().flag("new-checkout").enabled(),
                "非法提交不得只生效一半（开关保持旧值）");
    }
}
