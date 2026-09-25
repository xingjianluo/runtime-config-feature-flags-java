package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 验证灰度分流的一致性、可复现性与边界语义。 */
class FeatureFlagServiceTest {

    private FeatureFlagService newService() {
        FeatureFlagService service = new FeatureFlagService();
        service.upsert(new FeatureFlag("new-checkout", true, 25));
        service.upsert(new FeatureFlag("all-on", true, 100));
        service.upsert(new FeatureFlag("all-off", true, 0));
        service.upsert(new FeatureFlag("disabled-flag", false, 100));
        return service;
    }

    @Test
    void sameIdentifierGetsSameDecisionAcrossEntriesAndTime() throws Exception {
        FeatureFlagService service = newService();
        FlagDecision first = service.decide("new-checkout", "user-42");

        // 模拟不同入口（并发线程）与不同时间点（重复判定）
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<FlagDecision>> futures = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            futures.add(pool.submit(() -> service.decide("new-checkout", "user-42")));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.printf("[分流一致性] 输入: flag=new-checkout(25%%), id=user-42; "
                + "判定依据: bucket 仅由 SHA-256(flag:id) 决定, 与入口和时间无关%n");
        System.out.printf("[分流一致性] 首次判定: %s%n", first);
        for (Future<FlagDecision> f : futures) {
            FlagDecision d = f.get();
            assertEquals(first.allowed(), d.allowed(),
                    "同一标识在并发入口下结论必须一致: first=" + first + ", other=" + d);
            assertEquals(first.bucket(), d.bucket(), "桶号必须可复现: first=" + first + ", other=" + d);
        }
        assertEquals(first.bucket() < 25, first.allowed(),
                "放行结论必须等于 bucket(" + first.bucket() + ") < 25");
    }

    @Test
    void percentageZeroAndHundredHaveDeterministicSemantics() {
        FeatureFlagService service = newService();
        FlagDecision off = service.decide("all-off", "user-1");
        FlagDecision on = service.decide("all-on", "user-1");

        System.out.printf("[边界 0/100] 输入: all-off(0%%) 与 all-on(100%%), id=user-1; "
                + "判定依据: bucket 恒 >= 0 且恒 < 100%n");
        System.out.printf("[边界 0/100] 结果: all-off -> %s; all-on -> %s%n", off, on);

        assertFalse(off.allowed(), "比例 0 必须一律不放行: " + off);
        assertEquals(FlagDecision.Reason.OUTSIDE_PERCENTAGE, off.reason());
        assertTrue(on.allowed(), "比例 100 必须一律放行: " + on);
        assertEquals(FlagDecision.Reason.WITHIN_PERCENTAGE, on.reason());
    }

    @Test
    void unknownFlagIsDeniedExplicitlyAndDisabledFlagIsDenied() {
        FeatureFlagService service = newService();
        FlagDecision unknown = service.decide("no-such-flag", "user-1");
        FlagDecision disabled = service.decide("disabled-flag", "user-1");

        System.out.printf("[未知/禁用] 输入: no-such-flag 与 disabled-flag(enabled=false, 100%%); "
                + "判定依据: 未知开关绝不静默放行, 禁用开关一律拒绝%n");
        System.out.printf("[未知/禁用] 结果: unknown -> %s; disabled -> %s%n", unknown, disabled);

        assertFalse(unknown.allowed(), "未知开关不得放行: " + unknown);
        assertEquals(FlagDecision.Reason.UNKNOWN_FLAG, unknown.reason(),
                "未知开关必须显式标记 UNKNOWN_FLAG, 实际=" + unknown.reason());
        assertFalse(disabled.allowed(), "禁用开关不得放行: " + disabled);
        assertEquals(FlagDecision.Reason.FLAG_DISABLED, disabled.reason());
    }

    @Test
    void distributionRoughlyMatchesPercentage() {
        FeatureFlagService service = newService();
        int total = 20_000;
        int allowed = 0;
        for (int i = 0; i < total; i++) {
            if (service.decide("new-checkout", "user-" + i).allowed()) {
                allowed++;
            }
        }
        double ratio = allowed * 100.0 / total;
        System.out.printf("[比例校验] 输入: flag=new-checkout(25%%), %d 个不同标识; "
                + "判定依据: 稳定哈希近似均匀分布, 放行比例应接近 25%%%n", total);
        System.out.printf("[比例校验] 结果: 放行 %d/%d = %.2f%%%n", allowed, total, ratio);

        assertTrue(ratio > 22.0 && ratio < 28.0,
                "放行比例应接近 25%, 实际=" + ratio + "%");
    }

    @Test
    void invalidPercentageIsRejectedAtDefinition() {
        System.out.println("[非法比例] 输入: percentage=-1 与 101; 判定依据: 定义期即拒绝, 不进入运行态");
        assertThrows(IllegalArgumentException.class, () -> new FeatureFlag("bad-low", true, -1));
        assertThrows(IllegalArgumentException.class, () -> new FeatureFlag("bad-high", true, 101));
        System.out.println("[非法比例] 结果: 两者均抛出 IllegalArgumentException");
    }
}
