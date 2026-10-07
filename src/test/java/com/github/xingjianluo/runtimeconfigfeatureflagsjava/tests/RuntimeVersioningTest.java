package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.ConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.CommitRequest;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.RuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置与开关统一版本：同版本生效、拒绝不半生效、按版本一起回滚、
 * 遗留非法值不再阻塞合法提交、并发只观察到完整版本。
 */
class RuntimeVersioningTest {

    private static final String KEY = "pool.size";
    private static final String FLAG = "new-checkout";

    /** 构造：base(10, 可被测试塞入遗留非法值) + overrides(20, 提交通道)。 */
    private record Harness(ConfigService config,
                           FeatureFlagService flags,
                           InMemoryConfigSource base,
                           InMemoryConfigSource overrides) {
        RuntimeSnapshot state() {
            return config.state();
        }
    }

    private Harness newHarness(String baseValue, List<FeatureFlag> initialFlags) {
        ConfigRegistry registry = new ConfigRegistry()
                .register(new ConfigDefinition(KEY, ConfigValueType.INTEGER, "8",
                        v -> Integer.parseInt(v.trim()) >= 1 && Integer.parseInt(v.trim()) <= 512, "pool size"))
                .register(new ConfigDefinition("label", ConfigValueType.STRING, "default-label", null, "label"));
        InMemoryConfigSource base = new InMemoryConfigSource("base", 10);
        if (baseValue != null) {
            base.setValues(Map.of(KEY, baseValue, "label", "v-" + baseValue));
        }
        InMemoryConfigSource overrides = new InMemoryConfigSource("overrides", 20);
        ConfigService config = new ConfigService(
                new ConfigLoader(registry, List.of(base, overrides)), overrides, initialFlags);
        config.init();
        return new Harness(config, new FeatureFlagService(config::state), base, overrides);
    }

    @Test
    void configAndFlagsCommitApplyUnderSameVersion() {
        Harness h = newHarness("4", List.of(new FeatureFlag(FLAG, false, 0)));
        long v1 = h.state().version();

        UpdateResult result = h.config().commit(
                new CommitRequest(Map.of(KEY, "64"), List.of(new FeatureFlag(FLAG, true, 25))), null);

        System.out.printf("[统一版本] 输入: config={pool.size=64}, flags={new-checkout enabled=true,25%%}; "
                + "判定依据: 一次统一提交, 配置与开关同属新版本%n");
        System.out.printf("[统一版本] 结果: accepted=%s, version=%s, config=%s, flags=%s%n",
                result.accepted(), result.version(),
                h.state().config().values(), h.state().flags());

        assertTrue(result.accepted(), "合法统一提交应生效: " + result.message());
        assertEquals(v1 + 1, result.version(), "版本应递增");
        assertEquals(v1 + 1, h.state().version());
        assertEquals("64", h.state().config().get(KEY));
        FeatureFlag flag = h.state().flag(FLAG);
        assertTrue(flag.enabled());
        assertEquals(25, flag.percentage());

        FlagDecision decision = h.flags().decide(FLAG, "user-42");
        System.out.printf("[统一版本] 分流判定: %s%n", decision);
        assertEquals(v1 + 1, decision.version(), "判定结论必须带依据的统一版本号");
    }

    @Test
    void invalidCommitIsRejectedAsAWholeAndOldValuesRemain() {
        Harness h = newHarness("4", List.of(new FeatureFlag(FLAG, true, 25)));
        RuntimeSnapshot before = h.state();

        // 一半合法（开关要关）、一半非法（配置给类型错误的值）：必须整体拒绝
        UpdateResult result = h.config().commit(
                new CommitRequest(Map.of(KEY, "not-a-number"),
                        List.of(new FeatureFlag(FLAG, false, 25))), null);

        System.out.printf("[整体拒绝] 输入: config={pool.size=not-a-number}, flags={new-checkout enabled=false}; "
                        + "判定依据: 覆盖来源出现 TYPE_MISMATCH -> VALIDATION_FAILED, 整体拒绝%n");
        System.out.printf("[整体拒绝] 结果: accepted=%s, reason=%s, 当前版本=%d%n",
                result.accepted(), result.reason(), h.state().version());

        assertFalse(result.accepted(), "非法提交必须被拒绝");
        assertEquals(UpdateResult.RejectionReason.VALIDATION_FAILED, result.reason());
        assertFalse(result.issues().isEmpty(), "拒绝结果应携带直接相关的问题");
        assertSame(before, h.state(), "旧统一快照必须原样保留（同一引用）");
        assertEquals("4", h.state().config().get(KEY), "旧配置仍可用");
        assertTrue(h.state().flag(FLAG).enabled(), "开关不得只改成一半（仍为旧的启用态）");
        FlagDecision decision = h.flags().decide(FLAG, "user-1");
        assertEquals(before.version(), decision.version(), "判定依据必须仍是旧版本");
    }

    @Test
    void rollbackRestoresConfigAndFlagsTogether() {
        Harness h = newHarness("4", List.of(new FeatureFlag(FLAG, false, 0)));
        long v1 = h.state().version();
        // v2：配置 64 + 开关启用 25%
        h.config().commit(new CommitRequest(Map.of(KEY, "64"),
                List.of(new FeatureFlag(FLAG, true, 25))), null);
        // v3：配置 128 + 开关 100%
        h.config().commit(new CommitRequest(Map.of(KEY, "128"),
                List.of(new FeatureFlag(FLAG, true, 100))), null);
        assertEquals(100, h.state().flag(FLAG).percentage());

        UpdateResult rollback = h.config().rollback(v1);

        System.out.printf("[统一回滚] 输入: rollback 到版本 %d; "
                + "判定依据: 配置与开关一起回到该版本取值, 回滚本身生成新版本%n", v1);
        System.out.printf("[统一回滚] 结果: accepted=%s, newVersion=%s, pool.size=%s, flag=%s%n",
                rollback.accepted(), rollback.version(),
                h.state().config().get(KEY), h.state().flag(FLAG));

        assertTrue(rollback.accepted(), "回滚应成功: " + rollback.message());
        assertEquals(3L + 1, rollback.version(), "回滚生成新版本");
        assertEquals("4", h.state().config().get(KEY), "配置应恢复为 v1 的 4");
        FeatureFlag restored = h.state().flag(FLAG);
        assertFalse(restored.enabled(), "开关应恢复为 v1 的禁用态");
        assertEquals(0, restored.percentage());

        FlagDecision decision = h.flags().decide(FLAG, "user-9");
        assertEquals(rollback.version(), decision.version(), "判定依据必须是回滚后的版本");
        assertEquals(FlagDecision.Reason.FLAG_DISABLED, decision.reason(),
                "v1 开关禁用, 回滚后判定应为 FLAG_DISABLED");
    }

    @Test
    void staleInvalidValueInAnotherSourceDoesNotBlockValidCommit() {
        // base 来源里遗留一个非法值（历史坑：此后合法提交也被锁死）
        Harness h = newHarness("99999", List.of(new FeatureFlag(FLAG, true, 25)));
        System.out.printf("[遗留问题] 输入: base 来源遗留 pool.size=99999(非法, 回退缺省值 8)%n");
        assertEquals("8", h.state().config().get(KEY), "初始状态下遗留非法值应回退缺省值");
        assertFalse(h.state().config().issues().isEmpty(), "遗留问题仍应记录在快照中可查");

        // 之前的坑：连用来修正的合法提交也被整体拒绝，热更新被锁死
        UpdateResult fix = h.config().applyOverrides(Map.of(KEY, "64"), null);

        System.out.printf("[遗留问题] 修正提交: overrides={pool.size=64}; "
                + "判定依据: 覆盖来源无问题, base 的遗留问题与本次提交无关 -> 应接受%n");
        System.out.printf("[遗留问题] 结果: accepted=%s, version=%s, pool.size=%s%n",
                fix.accepted(), fix.version(), h.state().config().get(KEY));

        assertTrue(fix.accepted(), "遗留问题不该阻塞与它无关的合法提交: " + fix.message());
        assertEquals("64", h.state().config().get(KEY), "本次修正值必须正常生效");
        assertEquals(2L, h.state().version());

        // 与本次提交直接相关的非法值，照旧整体拒绝
        UpdateResult bad = h.config().applyOverrides(Map.of(KEY, "boom"), null);
        System.out.printf("[遗留问题] 非法提交: overrides={pool.size=boom}; "
                + "判定依据: 问题就在本次提交写入的覆盖来源 -> 必须拒绝%n");
        System.out.printf("[遗留问题] 结果: accepted=%s, pool.size=%s%n",
                bad.accepted(), h.state().config().get(KEY));
        assertFalse(bad.accepted(), "与提交直接相关的问题照旧整体拒绝");
        assertEquals(UpdateResult.RejectionReason.VALIDATION_FAILED, bad.reason());
        assertEquals("64", h.state().config().get(KEY), "拒绝后已生效的修正值继续可用");
    }

    @Test
    void concurrentDecisionsAlwaysObserveOneCompleteVersion() throws Exception {
        // 初始版本同样满足配套不变量：pct == pool.size == 4，label=v-4
        Harness h = newHarness("4", List.of(new FeatureFlag(FLAG, true, 4)));
        int writers = 4;
        int decisionsPerReader = 3000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        java.util.Set<Long> observedVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // 写线程：配置与开关在同一提交内成对变化（pool.size 与 percentage 始终相等）
        for (int w = 0; w < writers; w++) {
            int id = w;
            pool.submit(() -> {
                await(start);
                for (int i = 1; i <= 20; i++) {
                    int pct = (id * 40 + i) % 101; // 0..100
                    UpdateResult r = h.config().commit(new CommitRequest(
                            Map.of(KEY, String.valueOf(pct), "label", "v-" + pct),
                            List.of(new FeatureFlag(FLAG, true, pct))), null);
                    if (!r.accepted()) {
                        rejected.incrementAndGet();
                    }
                }
            });
        }
        // 判定线程：每次判定依据的版本，其配置值、label、比例必须同属一个完整版本
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < decisionsPerReader; i++) {
                    String id = "user-" + i;
                    FlagDecision d = h.flags().decide(FLAG, id);
                    observedVersions.add(d.version());
                    RuntimeSnapshot snap = h.config.runtimeVersion(d.version());
                    if (snap == null) {
                        mismatches.incrementAndGet(); // 依据版本必须真实存在
                        continue;
                    }
                    int pct = snap.flag(FLAG).percentage();
                    String poolSize = snap.config().get(KEY);
                    // 完整版本不变量：同一版本内 percentage == pool.size，且 label 与之配套
                    boolean sameVersionInvariant =
                            String.valueOf(pct).equals(poolSize)
                                    && snap.config().get("label").equals("v-" + pct);
                    // 判定内部不变量：放行结论必须与该版本的比例档一致
                    boolean decisionInvariant =
                            d.reason() == FlagDecision.Reason.WITHIN_PERCENTAGE
                                    || d.reason() == FlagDecision.Reason.OUTSIDE_PERCENTAGE;
                    boolean allowedMatches = d.allowed() == (d.bucket() < pct)
                            && Integer.valueOf(pct).equals(d.percentage());
                    if (!sameVersionInvariant || !decisionInvariant || !allowedMatches) {
                        mismatches.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试应在限时内完成");

        System.out.printf("[并发完整版本] 输入: %d 写线程成对提交(配置+开关), 4 判定线程各 %d 次; "
                        + "判定依据: 每次判定只能看到某一完整版本%n", writers, decisionsPerReader);
        System.out.printf("[并发完整版本] 结果: 观察到版本数=%d, 不匹配=%d, 被拒提交=%d, 最终版本=%d%n",
                observedVersions.size(), mismatches.get(), rejected.get(), h.state().version());

        assertEquals(0, mismatches.get(), "并发下不允许看到配置/开关新旧混合的中间态");
        assertTrue(observedVersions.size() > 1, "应确实观察到热更新带来的多个版本");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
