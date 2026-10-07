package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证"来源里遗留非法值不再锁死热更新"：
 * 与本次提交无关的遗留问题不阻塞合法提交；与本次提交直接相关的问题照旧整体拒绝。
 */
class LegacyInvalidValueTest {

    private record Rig(ConfigService configService, FeatureFlagService flagService,
                       InMemoryConfigSource base) {
    }

    /** base 来源里遗留两个非法值：pool.size=not-a-number 与 flag.broken.percentage=150。 */
    private Rig newRigWithLegacyGarbage() {
        ConfigRegistry registry = new ConfigRegistry()
                .register(new ConfigDefinition("pool.size", ConfigValueType.INTEGER, "8",
                        v -> Integer.parseInt(v.trim()) >= 1 && Integer.parseInt(v.trim()) <= 512, "pool size"))
                .register(new ConfigDefinition("label", ConfigValueType.STRING, "default-label", null, "label"));
        InMemoryConfigSource overrides = new InMemoryConfigSource("overrides", 20);
        InMemoryConfigSource base = new InMemoryConfigSource("base", 10);
        base.setValues(Map.of(
                "pool.size", "not-a-number",
                "label", "from-base",
                "flag.broken.enabled", "true",
                "flag.broken.percentage", "150"));
        ConfigService configService = new ConfigService(new ConfigLoader(registry, List.of(base, overrides)),
                overrides);
        configService.init();
        return new Rig(configService, new FeatureFlagService(configService), base);
    }

    @Test
    void legacyInvalidValueDoesNotBlockUnrelatedValidCommit() {
        Rig rig = newRigWithLegacyGarbage();
        assertFalse(rig.configService().current().issues().isEmpty(),
                "初始快照应记录遗留问题: " + rig.configService().current().issues());

        UpdateResult result = rig.configService().applyOverrides(Map.of("label", "fixed-label"), null);

        System.out.printf("[遗留不阻塞] 输入: base 遗留 pool.size=not-a-number, 提交 overrides={label=fixed-label}; "
                + "判定依据: 遗留问题与本次提交无关, 不阻塞合法提交, 只记录在快照中%n");
        System.out.printf("[遗留不阻塞] 结果: accepted=%s, version=%s, label=%s, issues=%s%n",
                result.accepted(), result.version(), rig.configService().current().get("label"),
                rig.configService().current().issues());

        assertTrue(result.accepted(),
                "遗留非法值不得阻塞无关的合法提交: " + result.message());
        assertEquals("fixed-label", rig.configService().current().get("label"),
                "合法提交应正常生效");
        assertEquals("8", rig.configService().current().get("pool.size"),
                "遗留非法值仍被丢弃, 回退缺省值 8, 实际=" + rig.configService().current().get("pool.size"));
        assertTrue(rig.configService().current().issues().stream()
                        .anyMatch(i -> i.key().equals("pool.size")),
                "遗留问题应继续记录在快照中保持可见");
    }

    @Test
    void commitThatFixesTheLegacyInvalidValueIsAccepted() {
        Rig rig = newRigWithLegacyGarbage();

        // 这正是之前被锁死的场景：提交一个合法值去修正遗留的非法键
        UpdateResult result = rig.configService().applyOverrides(Map.of("pool.size", "64"), null);

        System.out.printf("[修正生效] 输入: base 遗留 pool.size=not-a-number, 提交 overrides={pool.size=64}; "
                + "判定依据: 本次提交的值本身合法, 高优先级覆盖生效, 遗留问题不拖累修正%n");
        System.out.printf("[修正生效] 结果: accepted=%s, version=%s, pool.size=%s%n",
                result.accepted(), result.version(), rig.configService().current().get("pool.size"));

        assertTrue(result.accepted(), "用于修正遗留非法值的合法提交必须被接受: " + result.message());
        assertEquals("64", rig.configService().current().get("pool.size"),
                "修正值应正常生效, 实际=" + rig.configService().current().get("pool.size"));
    }

    @Test
    void legacyInvalidFlagValueDoesNotBlockValidFlagCommit() {
        Rig rig = newRigWithLegacyGarbage();

        UpdateResult result = rig.flagService().upsertFlag(new FeatureFlag("new-flag", true, 30), null);

        System.out.printf("[开关遗留不阻塞] 输入: base 遗留 flag.broken.percentage=150, 提交新开关 new-flag=30%%; "
                + "判定依据: 遗留的非法开关值与本次提交无关, 不阻塞%n");
        System.out.printf("[开关遗留不阻塞] 结果: accepted=%s, version=%s, flags=%s%n",
                result.accepted(), result.version(), rig.flagService().currentFlags());

        assertTrue(result.accepted(), "遗留非法开关值不得阻塞合法开关提交: " + result.message());
        assertEquals(new FeatureFlag("new-flag", true, 30),
                rig.flagService().currentFlags().get("new-flag"), "新开关应正常生效");
        // 遗留的非法比例被丢弃, 该开关回退到缺省比例 0（启用但谁都不放行）
        assertEquals(new FeatureFlag("broken", true, 0),
                rig.flagService().currentFlags().get("broken"),
                "遗留非法比例不得半生效, 应回退缺省 0");
    }

    @Test
    void commitRelatedProblemStillRejectsAtomically() {
        Rig rig = newRigWithLegacyGarbage();
        ConfigSnapshot before = rig.configService().current();

        // 与本次提交直接相关的问题（提交的值本身非法）必须照旧整体拒绝
        UpdateResult result = rig.flagService().commitAll(
                Map.of("label", "good-label"),
                Map.of("new-flag", new FeatureFlag("new-flag", true, 30)),
                null);
        // 先构造一个真正非法的提交：直接往覆盖来源提交非法键值
        assertTrue(result.accepted(), "对照组：合法提交应被接受: " + result.message());

        UpdateResult rejected = rig.configService().commit(
                Map.of("label", "another-good-label"),
                Map.of("flag.new-flag.percentage", "not-a-number"), null);

        System.out.printf("[相关仍拒绝] 输入: flag.new-flag.percentage=not-a-number; "
                + "判定依据: 本次提交的值本身非法 -> VALIDATION_FAILED, 整体拒绝, 绝不半生效%n");
        System.out.printf("[相关仍拒绝] 结果: accepted=%s, reason=%s, issues=%s, label=%s, new-flag=%s%n",
                rejected.accepted(), rejected.reason(), rejected.issues(),
                rig.configService().current().get("label"),
                rig.flagService().currentFlags().get("new-flag"));

        assertFalse(rejected.accepted(), "与提交直接相关的非法值必须被拒绝");
        assertEquals(UpdateResult.RejectionReason.VALIDATION_FAILED, rejected.reason());
        assertFalse(rejected.issues().isEmpty(), "拒绝时应携带具体问题");
        assertEquals("good-label", rig.configService().current().get("label"),
                "非法提交不得半生效: label 应保持上一版本的值");
        assertEquals(new FeatureFlag("new-flag", true, 30),
                rig.flagService().currentFlags().get("new-flag"),
                "非法提交不得半生效: 开关应保持上一版本的值");
        assertEquals(before.version() + 1, rig.configService().current().version(),
                "拒绝后版本号不得前进");
    }
}
