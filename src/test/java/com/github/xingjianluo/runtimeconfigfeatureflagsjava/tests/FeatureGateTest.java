package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature.FeatureGateService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature.GateDecision;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 功能开关分流：0/100/未知开关边界、确定性复现、比例近似。
 */
class FeatureGateTest {

    private static final String P = ConfigKeys.FEATURE_FLAG_PREFIX;
    private static final String S = ConfigKeys.FEATURE_FLAG_SUFFIX;

    private FeatureGateService gateWith(Map<String, String> values) {
        InMemoryConfigSource src = new InMemoryConfigSource("test-file", 100, values);
        ConfigService service = new ConfigService(ConfigKeys.defaultRegistry(), List.of(src));
        service.initialize();
        return new FeatureGateService(service);
    }

    private void print(GateDecision d) {
        System.out.printf("输入 flag=%s identity=%s => enabled=%s percentage=%d bucket=%d 依据=%s (configV=%d)%n",
                d.flag(), d.identity(), d.enabled(), d.percentage(), d.bucket(),
                d.reason(), d.configVersion());
    }

    @Test
    void boundaryZeroHundredAndUnknownHaveFixedSemantics() {
        FeatureGateService gate = gateWith(Map.of(
                P + "off" + S, "0",
                P + "on" + S, "100"));

        for (String id : List.of("u1", "u2", "user-2026")) {
            GateDecision off = gate.evaluate("off", id);
            print(off);
            assertFalse(off.enabled());
            assertTrue(off.reason().startsWith("ZERO_PERCENTAGE"));

            GateDecision on = gate.evaluate("on", id);
            print(on);
            assertTrue(on.enabled());
            assertTrue(on.reason().startsWith("FULL_PERCENTAGE"));
        }

        GateDecision unknown = gate.evaluate("does-not-exist", "u1");
        print(unknown);
        assertFalse(unknown.enabled(), "未知开关必须 fail-closed，不得静默放行");
        assertEquals(-1, unknown.percentage());
        assertTrue(unknown.reason().startsWith("UNKNOWN_FLAG"));
    }

    @Test
    void sameFlagAndIdentityAlwaysHashToSameBucket() {
        long first = FeatureGateService.bucket("gradual", "stable-user");
        for (int i = 0; i < 50; i++) {
            assertEquals(first, FeatureGateService.bucket("gradual", "stable-user"),
                    "同 (flag, identity) 必须得到相同桶");
        }
        System.out.println("输入 (gradual, stable-user) 重复 50 次 => 恒定 bucket=" + first
                + " sha256=" + FeatureGateService.hashHex("gradual", "stable-user"));
    }

    @Test
    void decisionsAreReproducibleAcrossTimeAndRepeatedCalls() {
        FeatureGateService gate = gateWith(Map.of(P + "gradual" + S, "37"));
        Map<String, Boolean> firstRound = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            String id = "user-" + i;
            GateDecision d = gate.evaluate("gradual", id);
            firstRound.put(id, d.enabled());
        }
        // 再次判定（模拟不同时间点）必须与首轮完全一致
        for (int i = 0; i < 500; i++) {
            String id = "user-" + i;
            GateDecision d = gate.evaluate("gradual", id);
            assertEquals(firstRound.get(id), d.enabled(),
                    () -> "标识 " + id + " 在不同时间点结论不一致");
        }
        long enabled = firstRound.values().stream().filter(b -> b).count();
        System.out.printf("输入 500 个标识, percentage=37 => 实际开启 %d (%.1f%%)，两轮完全一致%n",
                enabled, enabled * 100.0 / 500);
        // 37% ± 8% 的宽松分布断言，重点是确定性而非均匀性
        assertTrue(enabled > 100 && enabled < 240, "比例应在预期附近，实际=" + enabled);
    }

    @Test
    void boundaryBucketsAt50PercentAreDeterministic() {
        // bucket < 50 开；bucket 恰好 49 开、50 关。用具体标识打印边界依据。
        FeatureGateService gate = gateWith(Map.of(P + "half" + S, "50"));
        GateDecision d49ish = gate.evaluate("half", findIdentityAtBucket("half", 49));
        print(d49ish);
        assertTrue(d49ish.enabled());
        GateDecision d50ish = gate.evaluate("half", findIdentityAtBucket("half", 50));
        print(d50ish);
        assertFalse(d50ish.enabled());
    }

    /** 找一个在指定开关下落到指定桶的标识，用于把边界桶打成可打印的具体输入。 */
    private String findIdentityAtBucket(String flag, long targetBucket) {
        for (long i = 0; i < 1_000_000L; i++) {
            String id = "probe-" + i;
            if (FeatureGateService.bucket(flag, id) == targetBucket) {
                return id;
            }
        }
        throw new AssertionError("未找到桶 " + targetBucket + " 的探针标识");
    }
}
