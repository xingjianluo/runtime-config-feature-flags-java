package com.github.xingjianluo.runtimeconfigfeatureflagsjava.tests;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.*;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证配置与开关纳入同一套版本：同版本生效、按版本回滚两者一起恢复。 */
class UnifiedVersioningTest {

    private record Rig(ConfigService configService, FeatureFlagService flagService) {
    }

    private Rig newRig() {
        ConfigRegistry registry = new ConfigRegistry()
                .register(new ConfigDefinition("pool.size", ConfigValueType.INTEGER, "8",
                        v -> Integer.parseInt(v.trim()) >= 1 && Integer.parseInt(v.trim()) <= 512, "pool size"));
        InMemoryConfigSource overrides = new InMemoryConfigSource("overrides", 20);
        InMemoryConfigSource base = new InMemoryConfigSource("base", 10);
        base.setValues(Map.of(
                "pool.size", "4",
                "flag.new-checkout.enabled", "true",
                "flag.new-checkout.percentage", "25"));
        ConfigService configService = new ConfigService(new ConfigLoader(registry, List.of(base, overrides)),
                overrides);
        configService.init();
        return new Rig(configService, new FeatureFlagService(configService));
    }

    @Test
    void configAndFlagsEnterTheSameVersionAtomically() {
        Rig rig = newRig();
        long v1 = rig.configService().current().version();

        UpdateResult result = rig.flagService().commitAll(
                Map.of("pool.size", "64"),
                Map.of("new-checkout", new FeatureFlag("new-checkout", true, 80),
                        "brand-new", new FeatureFlag("brand-new", true, 50)),
                v1);

        System.out.printf("[同版本生效] 输入: config={pool.size=64}, flags={new-checkout=80%%, brand-new=50%%}, "
                        + "expectedVersion=%d; 判定依据: 一次提交要么配置和开关一起进入新版本, 要么整体不变%n", v1);
        System.out.printf("[同版本生效] 结果: accepted=%s, version=%s, pool.size=%s, flags=%s%n",
                result.accepted(), result.version(), rig.configService().current().get("pool.size"),
                rig.flagService().currentFlags());

        assertTrue(result.accepted(), "合法提交应被接受: " + result.message());
        long v2 = rig.configService().current().version();
        assertEquals(v1 + 1, v2, "版本应恰好递增 1");
        assertEquals("64", rig.configService().current().get("pool.size"),
                "配置应在新版本生效");
        assertEquals(new FeatureFlag("new-checkout", true, 80),
                rig.flagService().currentFlags().get("new-checkout"),
                "开关比例应与配置同版本生效");
        assertEquals(new FeatureFlag("brand-new", true, 50),
                rig.flagService().currentFlags().get("brand-new"), "新开关应同版本出现");

        // 判定结论必须能看出依据的版本、比例与桶号
        FlagDecision decision = rig.flagService().decide("new-checkout", "user-42");
        System.out.printf("[判定依据] 结果: allowed=%s, bucket=%d, percentage=%d, version=%d, reason=%s%n",
                decision.allowed(), decision.bucket(), decision.percentage(), decision.version(),
                decision.reason());
        assertEquals(v2, decision.version(), "判定结论必须携带其依据的版本号");
        assertEquals(80, decision.percentage(), "判定结论必须携带生效比例");
        assertEquals(decision.bucket() < 80, decision.allowed(), "放行结论必须与桶号和比例一致");
    }

    @Test
    void rollbackRestoresConfigAndFlagsTogether() {
        Rig rig = newRig();
        long v1 = rig.configService().current().version();
        FlagDecision beforeV1 = rig.flagService().decide("new-checkout", "user-42");

        rig.flagService().commitAll(
                Map.of("pool.size", "64"),
                Map.of("new-checkout", new FeatureFlag("new-checkout", false, 90)),
                v1);
        long v2 = rig.configService().current().version();
        assertEquals(FlagDecision.Reason.FLAG_DISABLED, rig.flagService().decide("new-checkout", "user-42").reason(),
                "v2 中开关应被禁用");

        UpdateResult rollback = rig.configService().rollback(v1);

        System.out.printf("[整体回滚] 输入: rollback 到版本 %d; 判定依据: 配置和开关一起回到该版本当时的取值%n", v1);
        System.out.printf("[整体回滚] 结果: accepted=%s, version=%s, pool.size=%s, flags=%s%n",
                rollback.accepted(), rollback.version(),
                rig.configService().current().get("pool.size"), rig.flagService().currentFlags());

        assertTrue(rollback.accepted(), "回滚应成功: " + rollback.message());
        assertEquals("4", rig.configService().current().get("pool.size"),
                "回滚后配置应恢复为 v1 取值");
        assertEquals(new FeatureFlag("new-checkout", true, 25),
                rig.flagService().currentFlags().get("new-checkout"),
                "回滚后开关应恢复为 v1 取值");
        FlagDecision afterRollback = rig.flagService().decide("new-checkout", "user-42");
        assertEquals(beforeV1.allowed(), afterRollback.allowed(), "回滚后同一标识的放行结论应恢复");
        assertEquals(beforeV1.bucket(), afterRollback.bucket(), "回滚后桶号应一致");
        assertEquals(beforeV1.percentage(), afterRollback.percentage(), "回滚后比例应恢复");
        assertEquals(rollback.version(), afterRollback.version(),
                "回滚生成新版本号, 判定应依据该新版本");
        assertTrue(afterRollback.version() > v2, "回滚本身也生成递增版本号");
    }

    @Test
    void configOnlyCommitKeepsFlagsUntouched() {
        Rig rig = newRig();
        long v1 = rig.configService().current().version();

        UpdateResult result = rig.configService().applyOverrides(Map.of("pool.size", "32"), v1);

        System.out.printf("[纯配置提交] 输入: overrides={pool.size=32}; 判定依据: 未涉及的开关原样保留%n");
        System.out.printf("[纯配置提交] 结果: accepted=%s, flags=%s%n",
                result.accepted(), rig.flagService().currentFlags());

        assertTrue(result.accepted(), "纯配置提交应被接受: " + result.message());
        assertEquals(new FeatureFlag("new-checkout", true, 25),
                rig.flagService().currentFlags().get("new-checkout"),
                "纯配置提交不得改动既有开关");
    }
}
