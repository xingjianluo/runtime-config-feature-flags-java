package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多源装载合并：优先级、缺省值、类型不匹配与取值非法必须可区分，
 * 高优先级非法值不得半生效或静默覆盖低优先级有效值。
 */
class ConfigMergeTest {

    private ConfigService serviceWith(List<InMemoryConfigSource> sources) {
        ConfigService service = new ConfigService(ConfigKeys.defaultRegistry(), List.copyOf(sources));
        service.initialize();
        return service;
    }

    private void log(String title, ResolvedValue v) {
        System.out.printf("[%s] key=%s value=%s status=%s source=%s detail=%s%n",
                title, v.key(), v.value(), v.status(), v.source(), v.detail());
    }

    @Test
    void higherPrioritySourceWins() {
        InMemoryConfigSource low = new InMemoryConfigSource("low", 100,
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "1500"));
        InMemoryConfigSource high = new InMemoryConfigSource("high", 200,
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "3000"));
        ConfigService service = serviceWith(List.of(low, high));

        ResolvedValue v = service.current().get(ConfigKeys.HTTP_TIMEOUT_MS);
        System.out.println("输入: low(100)=1500, high(200)=3000 => 期望采用 high");
        log("优先级合并", v);
        assertEquals(3000, ((Long) v.value()).intValue());
        assertEquals(ResolveStatus.RESOLVED, v.status());
        assertEquals("high", v.source());
    }

    @Test
    void illegalHighValueDoesNotClobberValidLowValue() {
        InMemoryConfigSource low = new InMemoryConfigSource("low", 100,
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "1500"));
        // 高优先级给越界非法值（上限 60000）
        InMemoryConfigSource high = new InMemoryConfigSource("high", 200,
                Map.of(ConfigKeys.HTTP_TIMEOUT_MS, "99999"));
        ConfigService service = serviceWith(List.of(low, high));

        ResolvedValue v = service.current().get(ConfigKeys.HTTP_TIMEOUT_MS);
        System.out.println("输入: low(100)=1500(合法), high(200)=99999(非法越界) => 期望降级采用 1500");
        log("非法值降级", v);
        assertEquals(1500, ((Long) v.value()).intValue());
        assertEquals("low", v.source());
        // 非法尝试被明确记录
        KeyIssue issue = service.current().issues().stream()
                .filter(i -> i.key().equals(ConfigKeys.HTTP_TIMEOUT_MS))
                .findFirst().orElseThrow();
        System.out.println("问题记录: source=" + issue.source() + " status=" + issue.status()
                + " raw=" + issue.rawValue() + " detail=" + issue.detail());
        assertEquals(ResolveStatus.INVALID, issue.status());
        assertEquals("high", issue.source());
        assertEquals(ResolveStatus.INVALID, v.status());
    }

    @Test
    void typeMismatchIsDistinguishableFromInvalidAndDefault() {
        InMemoryConfigSource typeBad = new InMemoryConfigSource("typeBad", 300,
                Map.of(ConfigKeys.MAX_RETRIES, "abc"));
        InMemoryConfigSource rangeBad = new InMemoryConfigSource("rangeBad", 200,
                Map.of(ConfigKeys.MAX_RETRIES, "999"));
        InMemoryConfigSource ok = new InMemoryConfigSource("ok", 100, Map.of());
        ConfigService service = serviceWith(List.of(typeBad, rangeBad, ok));

        ResolvedValue v = service.current().get(ConfigKeys.MAX_RETRIES);
        System.out.println("输入: typeBad=abc(类型错误), rangeBad=999(越界), ok 无值 => 期望缺省 3");
        log("缺省回退", v);
        assertEquals(3, ((Long) v.value()).intValue());
        assertEquals("default", v.source());
        assertEquals(ResolveStatus.TYPE_MISMATCH, v.status(),
                "最先遇到的问题类型应被保留为最终状态");
        List<KeyIssue> issues = service.current().issues().stream()
                .filter(i -> i.key().equals(ConfigKeys.MAX_RETRIES)).toList();
        assertEquals(2, issues.size());
        assertEquals(ResolveStatus.TYPE_MISMATCH, issues.get(0).status());
        assertEquals(ResolveStatus.INVALID, issues.get(1).status());
        System.out.println("两条问题依据: " + issues);
    }

    @Test
    void pureDefaultWhenNoSourceProvidesValue() {
        ConfigService service = serviceWith(List.of(
                new InMemoryConfigSource("empty", 100, Map.of())));
        ResolvedValue v = service.current().get(ConfigKeys.CIRCUIT_ENABLED);
        System.out.println("输入: 无来源提供 app.resilience.circuit-enabled => 期望缺省 true");
        log("纯缺省", v);
        assertTrue((Boolean) v.value());
        assertEquals(ResolveStatus.DEFAULT, v.status());
    }

    @Test
    void unknownKeyAtLoadTimeIsRecordedButNeverEffective() {
        InMemoryConfigSource src = new InMemoryConfigSource("s", 100,
                Map.of("not.a.real.key", "xxx", ConfigKeys.MAX_RETRIES, "5"));
        ConfigService service = serviceWith(List.of(src));
        System.out.println("输入: 未知键 not.a.real.key=xxx（装载阶段应记录但不生效）");
        KeyIssue issue = service.current().issues().stream()
                .filter(i -> i.status() == ResolveStatus.UNKNOWN_KEY).findFirst().orElseThrow();
        System.out.println("未知键问题记录: " + issue);
        assertFalse(service.current().values().containsKey("not.a.real.key"));
        assertEquals(5, service.current().getInt(ConfigKeys.MAX_RETRIES));
    }
}
