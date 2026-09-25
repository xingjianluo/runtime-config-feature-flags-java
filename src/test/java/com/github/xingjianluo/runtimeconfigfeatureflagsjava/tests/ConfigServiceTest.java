package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 验证版本化原子生效、非法更新被拒绝、版本冲突、回滚与并发读写一致性。 */
class ConfigServiceTest {

    private ConfigService newService() {
        ConfigRegistry registry = new ConfigRegistry()
                .register(new ConfigDefinition("pool.size", ConfigValueType.INTEGER, "8",
                        v -> Integer.parseInt(v.trim()) >= 1 && Integer.parseInt(v.trim()) <= 512, "pool size"))
                .register(new ConfigDefinition("label", ConfigValueType.STRING, "default-label", null, "label"));
        InMemoryConfigSource overrides = new InMemoryConfigSource("overrides", 20);
        InMemoryConfigSource base = new InMemoryConfigSource("base", 10);
        base.setValues(Map.of("pool.size", "4"));
        ConfigService service = new ConfigService(new ConfigLoader(registry, List.of(base, overrides)), overrides);
        service.init();
        return service;
    }

    @Test
    void validUpdateAppliesAtomicallyWithNewVersion() {
        ConfigService service = newService();
        long before = service.current().version();

        UpdateResult result = service.applyOverrides(Map.of("pool.size", "64", "label", "v2"), before);

        System.out.printf("[原子生效] 输入: overrides={pool.size=64,label=v2}, expectedVersion=%d; "
                        + "判定依据: 全部合法则整体生效并递增版本%n", before);
        System.out.printf("[原子生效] 结果: accepted=%s, version=%s, values=%s%n",
                result.accepted(), result.version(), service.current().values());

        assertTrue(result.accepted(), "合法更新应被接受: " + result.message());
        assertEquals(before + 1, service.current().version(), "版本应递增");
        assertEquals("64", service.current().get("pool.size"));
        assertEquals("v2", service.current().get("label"));
    }

    @Test
    void invalidUpdateIsRejectedAndOldConfigStays() {
        ConfigService service = newService();
        ConfigSnapshot before = service.current();

        UpdateResult result = service.applyOverrides(Map.of("pool.size", "not-a-number"), null);

        System.out.printf("[非法拒绝] 输入: overrides={pool.size=not-a-number}; "
                        + "判定依据: 类型不匹配 -> VALIDATION_FAILED, 整体拒绝, 旧版本继续可用%n");
        System.out.printf("[非法拒绝] 结果: accepted=%s, reason=%s, 当前版本=%d, pool.size=%s%n",
                result.accepted(), result.reason(), service.current().version(),
                service.current().get("pool.size"));

        assertFalse(result.accepted(), "非法更新必须被拒绝");
        assertEquals(UpdateResult.RejectionReason.VALIDATION_FAILED, result.reason(),
                "拒绝原因应为 VALIDATION_FAILED, 实际=" + result.reason());
        assertFalse(result.issues().isEmpty(), "拒绝时应携带可区分的问题列表");
        assertSame(before, service.current(), "拒绝后旧快照必须原样保留（同一引用）");
        assertEquals("4", service.current().get("pool.size"),
                "旧有效值不得被非法值覆盖, 实际=" + service.current().get("pool.size"));
    }

    @Test
    void staleExpectedVersionIsRejectedAsConflict() {
        ConfigService service = newService();
        long v1 = service.current().version();
        service.applyOverrides(Map.of("label", "v2"), v1);

        UpdateResult result = service.applyOverrides(Map.of("label", "v3"), v1);

        System.out.printf("[版本冲突] 输入: expectedVersion=%d, 当前版本=%d; "
                        + "判定依据: 期望版本落后 -> VERSION_CONFLICT%n", v1, service.current().version());
        System.out.printf("[版本冲突] 结果: accepted=%s, reason=%s, label=%s%n",
                result.accepted(), result.reason(), service.current().get("label"));

        assertFalse(result.accepted());
        assertEquals(UpdateResult.RejectionReason.VERSION_CONFLICT, result.reason(),
                "拒绝原因应为 VERSION_CONFLICT, 实际=" + result.reason());
        assertEquals("v2", service.current().get("label"),
                "冲突拒绝后现有值不得改变, 实际=" + service.current().get("label"));
    }

    @Test
    void rollbackRestoresPreviousValuesAndUnknownVersionIsRejected() {
        ConfigService service = newService();
        long v1 = service.current().version();
        String labelV1 = service.current().get("label");
        service.applyOverrides(Map.of("label", "v2", "pool.size", "64"), v1);

        UpdateResult rollback = service.rollback(v1);

        System.out.printf("[回滚] 输入: rollback 到版本 %d; 判定依据: 恢复该版本的覆盖集合并重新装载%n", v1);
        System.out.printf("[回滚] 结果: accepted=%s, version=%s, label=%s, pool.size=%s%n",
                rollback.accepted(), rollback.version(),
                service.current().get("label"), service.current().get("pool.size"));

        assertTrue(rollback.accepted(), "回滚应成功: " + rollback.message());
        assertEquals(labelV1, service.current().get("label"),
                "回滚后 label 应恢复为版本 " + v1 + " 的值 " + labelV1 + ", 实际=" + service.current().get("label"));
        assertEquals("4", service.current().get("pool.size"),
                "回滚后 pool.size 应恢复为 base 来源的 4, 实际=" + service.current().get("pool.size"));

        UpdateResult unknown = service.rollback(9999L);
        System.out.printf("[回滚-未知版本] 输入: rollback 到 9999; 结果: accepted=%s, reason=%s%n",
                unknown.accepted(), unknown.reason());
        assertFalse(unknown.accepted());
        assertEquals(UpdateResult.RejectionReason.UNKNOWN_ROLLBACK_VERSION, unknown.reason(),
                "未知版本应给出 UNKNOWN_ROLLBACK_VERSION, 实际=" + unknown.reason());
    }

    @Test
    void concurrentReadersAlwaysObserveAConsistentVersion() throws Exception {
        ConfigService service = newService();
        int writers = 4;
        int readers = 8;
        int writesPerWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistencies = new AtomicInteger();
        AtomicInteger acceptedWrites = new AtomicInteger();

        // 写线程：各自提交不同的合法覆盖
        for (int w = 0; w < writers; w++) {
            int id = w;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < writesPerWriter; i++) {
                    UpdateResult r = service.applyOverrides(
                            Map.of("pool.size", String.valueOf(10 + id), "label", "w" + id + "-" + i), null);
                    if (r.accepted()) {
                        acceptedWrites.incrementAndGet();
                    }
                }
            });
        }
        // 读线程：校验观察到的快照内部一致 —— label 与 pool.size 必须来自同一次生效
        Set<String> validPoolSizes = Set.of("4", "10", "11", "12", "13");
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 2000; i++) {
                    ConfigSnapshot snap = service.current();
                    String label = snap.get("label");
                    String poolSize = snap.get("pool.size");
                    // 快照是不可变整体：版本号与值必然同属一个版本
                    if (!validPoolSizes.contains(poolSize) || label == null
                            || snap.version() != service.version(snap.version()).version()) {
                        inconsistencies.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试应在限时内完成");

        System.out.printf("[并发读写] 输入: %d 写线程 x %d 次, %d 读线程 x 2000 次; "
                        + "判定依据: 每次读取只能看到某个完整版本, 值集合合法且版本可追溯%n",
                writers, writesPerWriter, readers);
        System.out.printf("[并发读写] 结果: 成功写入=%d, 不一致观察=%d, 最终版本=%d, 最终值=%s%n",
                acceptedWrites.get(), inconsistencies.get(),
                service.current().version(), service.current().values());

        assertEquals(0, inconsistencies.get(), "并发下不允许观察到不一致快照");
        assertTrue(acceptedWrites.get() > 0, "应存在成功生效的写入");
        assertEquals(1L + acceptedWrites.get(), service.current().version(),
                "每次成功写入恰好递增一个版本");
    }

    @Test
    void concurrentInvalidUpdatesNeverCorruptCurrentConfig() throws Exception {
        ConfigService service = newService();
        String goodLabel = service.current().get("label");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            boolean bad = i % 2 == 0;
            pool.submit(() -> {
                await(start);
                for (int j = 0; j < 50; j++) {
                    service.applyOverrides(bad
                            ? Map.of("pool.size", "boom")
                            : Map.of("pool.size", "32"), null);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        System.out.printf("[并发非法更新] 输入: 3 线程发非法值, 3 线程发合法值, 各 50 次; "
                + "判定依据: 非法更新整体拒绝, 最终配置只能是某个完整的合法版本%n");
        System.out.printf("[并发非法更新] 结果: pool.size=%s, label=%s, issues=%s%n",
                service.current().get("pool.size"), service.current().get("label"),
                service.current().issues());

        assertTrue(Set.of("4", "32").contains(service.current().get("pool.size")),
                "最终 pool.size 只能是 base 的 4 或合法覆盖 32, 实际=" + service.current().get("pool.size"));
        assertEquals(goodLabel, service.current().get("label"));
        assertTrue(service.current().issues().isEmpty(), "生效版本不得携带问题记录");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
