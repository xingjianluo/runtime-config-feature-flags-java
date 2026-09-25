package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发读写一致性：
 * 读者在任意时刻读到的快照都必须包含全部固定键且取值合法；
 * 并发提交要么整体成功推进版本，要么因版本冲突/非法被拒绝，旧版本始终可用。
 */
class ConfigConcurrencyTest {

    @Test
    void concurrentReadsAlwaysObserveACompleteVersion() throws Exception {
        InMemoryConfigSource file = new InMemoryConfigSource("file", 100,
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "1000",
                        ConfigKeys.MAX_RETRIES, "2",
                        ConfigKeys.CIRCUIT_ENABLED, "true"));
        ConfigService service = new ConfigService(ConfigKeys.defaultRegistry(), List.of(file));
        service.initialize();

        int readerCount = 6;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(readerCount + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong totalReads = new AtomicLong();
        Map<Long, Long> observedVersions = new ConcurrentHashMap<>();

        // 读线程：每次循环先取一次快照引用，再从同一个快照读全部键。
        for (int r = 0; r < readerCount; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        ConfigSnapshot snap = service.current();
                        observedVersions.merge(snap.version(), 1L, Long::sum);
                        int timeout = snap.getInt(ConfigKeys.HTTP_TIMEOUT_MS);
                        int retries = snap.getInt(ConfigKeys.MAX_RETRIES);
                        boolean circuit = snap.getBoolean(ConfigKeys.CIRCUIT_ENABLED);
                        // 断言该完整版本内部取值始终合法（不会读到半更新）
                        assertTrue(timeout >= 1 && timeout <= 60000,
                                "timeout 越界，观察到不完整/非法版本: " + timeout);
                        assertTrue(retries >= 0 && retries <= 10);
                        assertNotNull(circuit);
                        totalReads.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        AtomicLong accepted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        // 写线程：合法与非法交替，全部携带其开始时看到的版本号（乐观并发）。
        Runnable writer = () -> {
            try {
                start.await();
                for (int i = 0; i < 200 && !stop.get(); i++) {
                    long expected = service.current().version();
                    Map<String, String> payload = (i % 2 == 0)
                            ? Map.of(ConfigKeys.HTTP_TIMEOUT_MS, String.valueOf(1000 + i),
                                    ConfigKeys.MAX_RETRIES, String.valueOf(i % 11))
                            : Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "ILLEGAL-" + i);
                    UpdateResult result = service.submit(payload, expected);
                    if (result.accepted()) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        pool.submit(writer);
        pool.submit(writer);

        start.countDown();
        Thread.sleep(3000);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

        System.out.printf("并发输入: %d 读线程 3s 共完成 %d 次完整快照读取；观察到版本=%s%n",
                readerCount, totalReads.get(), new HashMap<>(observedVersions));
        System.out.printf("写结果: accepted=%d rejected(非法或版本冲突，旧版本继续可用)=%d%n",
                accepted.get(), rejected.get());
        System.out.printf("最终版本 v%d: timeout=%d retries=%d circuit=%s%n",
                service.current().version(),
                service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS),
                service.current().getInt(ConfigKeys.MAX_RETRIES),
                service.current().getBoolean(ConfigKeys.CIRCUIT_ENABLED));

        assertTrue(totalReads.get() > 10_000, "应产生大量读取，实际=" + totalReads.get());
        assertTrue(accepted.get() > 0, "应有部分合法提交成功");
        assertTrue(rejected.get() > 0, "并发冲突与非法提交必须被拒绝");
        // 最终版本自身必须合法完整：三个固定键全部存在且取值合法。
        ConfigSnapshot last = service.current();
        assertTrue(last.getInt(ConfigKeys.HTTP_TIMEOUT_MS) >= 1
                && last.getInt(ConfigKeys.HTTP_TIMEOUT_MS) <= 60000);
        assertTrue(last.getInt(ConfigKeys.MAX_RETRIES) >= 0
                && last.getInt(ConfigKeys.MAX_RETRIES) <= 10);
        assertTrue(last.values().containsKey(ConfigKeys.CIRCUIT_ENABLED));
    }
}
