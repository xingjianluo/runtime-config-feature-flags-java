package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本化原子生效 / 拒绝 / 回滚。
 */
class ConfigVersioningTest {

    private ConfigService newService() {
        InMemoryConfigSource file = new InMemoryConfigSource("default-file", 100,
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "1500",
                        ConfigKeys.MAX_RETRIES, "3"));
        ConfigService service = new ConfigService(ConfigKeys.defaultRegistry(), List.of(file));
        service.initialize();
        return service;
    }

    @Test
    void validSubmitPublishesNewAtomicVersion() {
        ConfigService service = newService();
        long v1 = service.current().version();
        UpdateResult r = service.submit(
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "4321"), v1);
        System.out.println("输入 submit(version=" + v1 + ", timeout=4321) => "
                + r.accepted() + ", newVersion=" + r.currentVersion() + ", " + r.detail());
        assertTrue(r.accepted());
        assertEquals(v1 + 1, r.currentVersion());
        assertEquals(4321, service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS));
        assertEquals("runtime-override", service.current().get(ConfigKeys.HTTP_TIMEOUT_MS).source());
    }

    @Test
    void invalidSubmitIsRejectedWholesaleAndOldVersionStays() {
        ConfigService service = newService();
        long v1 = service.current().version();

        Map<String, String> bad = Map.of(
                ConfigKeys.HTTP_TIMEOUT_MS, "not-a-number",      // 类型不匹配
                ConfigKeys.MAX_RETRIES, "999",                    // 越界非法
                ConfigKeys.CIRCUIT_ENABLED, "maybe",              // 布尔类型不匹配
                "totally.unknown.key", "1"                        // 未知键
        );
        UpdateResult r = service.submit(bad, v1);
        System.out.println("输入 submit(version=" + v1 + ", " + bad + ")");
        r.errors().forEach(e -> System.out.println("  拒绝依据: key=" + e.key()
                + " raw=" + e.rawValue() + " reason=" + e.reason() + " detail=" + e.detail()));
        assertFalse(r.accepted());
        assertEquals(v1, service.current().version(), "被拒绝后版本不得推进");
        assertEquals(1500, service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS), "旧值继续可用");
        assertEquals(3, service.current().getInt(ConfigKeys.MAX_RETRIES));

        // 四种原因必须逐条可区分
        assertEquals(4, r.errors().size());
        assertEquals(RejectReason.TYPE_MISMATCH,
                error(r, ConfigKeys.HTTP_TIMEOUT_MS).reason());
        assertEquals(RejectReason.INVALID_VALUE,
                error(r, ConfigKeys.MAX_RETRIES).reason());
        assertEquals(RejectReason.TYPE_MISMATCH,
                error(r, ConfigKeys.CIRCUIT_ENABLED).reason());
        assertEquals(RejectReason.UNKNOWN_KEY,
                error(r, "totally.unknown.key").reason());
    }

    @Test
    void optimisticVersionConflictIsRejected() {
        ConfigService service = newService();
        long v1 = service.current().version();
        // 第一次成功推进到 v1+1
        assertTrue(service.submit(Map.of(ConfigKeys.MAX_RETRIES, "4"), v1).accepted());
        // 用过期版本号提交必须冲突拒绝
        UpdateResult stale = service.submit(Map.of(ConfigKeys.MAX_RETRIES, "5"), v1);
        System.out.println("输入 submit(expectedVersion=" + v1 + " 已过期) => accepted="
                + stale.accepted() + " reason=" + stale.errors().get(0).reason());
        assertFalse(stale.accepted());
        assertEquals(RejectReason.VERSION_CONFLICT, stale.errors().get(0).reason());
        assertEquals(4, service.current().getInt(ConfigKeys.MAX_RETRIES));
    }

    @Test
    void rollbackRestoresOlderContentAsNewVersion() {
        ConfigService service = newService();
        long v1 = service.current().version();
        assertEquals(1500, service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS));

        service.submit(Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "5000"), v1);
        long v2 = service.current().version();
        assertEquals(5000, service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS));
        System.out.println("v" + v1 + " timeout=1500 -> v" + v2 + " timeout=5000");

        UpdateResult rb = service.rollback(v1);
        System.out.println("回滚输入 targetVersion=" + v1 + " => " + rb.accepted()
                + " newVersion=" + rb.currentVersion() + " " + rb.detail());
        assertTrue(rb.accepted());
        assertTrue(rb.currentVersion() > v2, "回滚产生新版本号，不复用历史号");
        assertEquals(1500, service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS));
        assertTrue(service.history().contains(v1));
    }

    @Test
    void rollbackToUnknownVersionIsRejectedWithReason() {
        ConfigService service = newService();
        UpdateResult rb = service.rollback(999_999);
        System.out.println("回滚输入 targetVersion=999999 => accepted=" + rb.accepted()
                + " reason=" + rb.errors().get(0).reason());
        assertFalse(rb.accepted());
        assertEquals(RejectReason.VERSION_NOT_FOUND, rb.errors().get(0).reason());
    }

    @Test
    void reloadKeepsPerKeyDegradationSemantics() {
        ConfigService service = newService();
        long v1 = service.current().version();
        UpdateResult r = service.reload();
        System.out.println("reload 输入: 重新读取全部来源 => accepted=" + r.accepted()
                + " version=" + r.currentVersion());
        assertTrue(r.accepted());
        assertTrue(r.currentVersion() > v1);
        assertEquals(1500, service.current().getInt(ConfigKeys.HTTP_TIMEOUT_MS));
    }

    private ValidationError error(UpdateResult r, String key) {
        return r.errors().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }
}
