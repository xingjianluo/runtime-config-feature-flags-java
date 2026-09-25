package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证多来源合并优先级与缺省值 / 类型不匹配 / 取值非法的可区分处理。 */
class ConfigLoaderTest {

    private ConfigRegistry registry() {
        return new ConfigRegistry()
                .register(new ConfigDefinition("pool.size", ConfigValueType.INTEGER, "8",
                        v -> Integer.parseInt(v.trim()) >= 1 && Integer.parseInt(v.trim()) <= 512, "pool size"))
                .register(new ConfigDefinition("feature.enabled", ConfigValueType.BOOLEAN, "false", null, "toggle"))
                .register(new ConfigDefinition("label", ConfigValueType.STRING, "default-label", null, "label"));
    }

    @Test
    void higherPrioritySourceWinsAndDefaultsApply() {
        InMemoryConfigSource low = new InMemoryConfigSource("low", 10);
        low.setValues(Map.of("pool.size", "4", "label", "from-low"));
        InMemoryConfigSource high = new InMemoryConfigSource("high", 20);
        high.setValues(Map.of("pool.size", "32"));

        ConfigSnapshot snapshot = new ConfigLoader(registry(), List.of(low, high)).load(1);

        System.out.printf("[优先级合并] 输入: low={pool.size=4,label=from-low}, high={pool.size=32}; "
                        + "判定依据: 高优先级覆盖低优先级, 未覆盖键取低优先级, 缺失键取缺省值%n");
        System.out.printf("[优先级合并] 结果: %s%n", snapshot.values());

        assertEquals("32", snapshot.get("pool.size"),
                "pool.size: high(32) 应覆盖 low(4), 实际=" + snapshot.get("pool.size"));
        assertEquals("from-low", snapshot.get("label"),
                "label: high 未提供, 应取 low 的值, 实际=" + snapshot.get("label"));
        assertEquals("false", snapshot.get("feature.enabled"),
                "feature.enabled: 两来源均未提供, 应取缺省值 false, 实际=" + snapshot.get("feature.enabled"));
        assertTrue(snapshot.issues().isEmpty(), "全部合法时不应有问题记录: " + snapshot.issues());
    }

    @Test
    void typeMismatchIsDroppedAndFallsBackToLowerPriorityValue() {
        InMemoryConfigSource low = new InMemoryConfigSource("low", 10);
        low.setValues(Map.of("pool.size", "16"));
        InMemoryConfigSource high = new InMemoryConfigSource("high", 20);
        high.setValues(Map.of("pool.size", "not-a-number"));

        ConfigSnapshot snapshot = new ConfigLoader(registry(), List.of(low, high)).load(1);

        LoadIssue issue = snapshot.issues().stream()
                .filter(i -> i.key().equals("pool.size")).findFirst().orElseThrow();
        System.out.printf("[类型不匹配] 输入: low={pool.size=16}, high={pool.size=not-a-number}; "
                        + "判定依据: high 的值无法解析为 INTEGER -> TYPE_MISMATCH, 丢弃该值并回退 low 的有效值%n");
        System.out.printf("[类型不匹配] 结果: pool.size=%s, issue=%s%n", snapshot.get("pool.size"), issue);

        assertEquals(LoadIssue.Kind.TYPE_MISMATCH, issue.kind(),
                "问题类别应为 TYPE_MISMATCH, 实际=" + issue.kind());
        assertEquals("high", issue.sourceName(), "问题来源应为 high, 实际=" + issue.sourceName());
        assertEquals("16", snapshot.get("pool.size"),
                "非法的高优先级值不得生效, 应回退到 low 的 16, 实际=" + snapshot.get("pool.size"));
    }

    @Test
    void invalidValueIsDroppedAndFallsBackToDefault() {
        InMemoryConfigSource high = new InMemoryConfigSource("high", 20);
        high.setValues(Map.of("pool.size", "99999"));

        ConfigSnapshot snapshot = new ConfigLoader(registry(), List.of(high)).load(1);

        LoadIssue issue = snapshot.issues().stream()
                .filter(i -> i.key().equals("pool.size")).findFirst().orElseThrow();
        System.out.printf("[取值非法] 输入: high={pool.size=99999}; "
                        + "判定依据: 99999 超出校验器范围 1..512 -> INVALID_VALUE, 丢弃并回退缺省值 8%n");
        System.out.printf("[取值非法] 结果: pool.size=%s, issue=%s%n", snapshot.get("pool.size"), issue);

        assertEquals(LoadIssue.Kind.INVALID_VALUE, issue.kind(),
                "问题类别应为 INVALID_VALUE, 实际=" + issue.kind());
        assertEquals("8", snapshot.get("pool.size"),
                "非法值不得半生效, 应回退缺省值 8, 实际=" + snapshot.get("pool.size"));
    }

    @Test
    void unreachableSourceIsRecordedButOthersStillApply() {
        InMemoryConfigSource good = new InMemoryConfigSource("good", 10);
        good.setValues(Map.of("label", "from-good"));
        var broken = new com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.ConfigSource() {
            public String name() { return "broken"; }
            public int priority() { return 20; }
            public Map<String, String> load() { throw new RuntimeException("io error"); }
        };

        ConfigSnapshot snapshot = new ConfigLoader(registry(), List.of(good, broken)).load(1);

        LoadIssue issue = snapshot.issues().stream()
                .filter(i -> i.kind() == LoadIssue.Kind.SOURCE_UNREACHABLE).findFirst().orElseThrow();
        System.out.printf("[来源不可达] 输入: good={label=from-good}, broken 抛异常; "
                        + "判定依据: 来源读取失败 -> SOURCE_UNREACHABLE, 不影响其他来源%n");
        System.out.printf("[来源不可达] 结果: label=%s, issue=%s%n", snapshot.get("label"), issue);

        assertEquals("broken", issue.sourceName(), "问题来源应为 broken, 实际=" + issue.sourceName());
        assertEquals("from-good", snapshot.get("label"),
                "其他来源应正常生效, 实际=" + snapshot.get("label"));
    }
}
