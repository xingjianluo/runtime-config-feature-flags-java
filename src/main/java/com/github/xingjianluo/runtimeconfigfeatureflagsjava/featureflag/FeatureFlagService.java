package com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigSnapshot;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 功能开关服务：基于稳定哈希的按比例灰度分流。
 *
 * 开关状态来自 {@link ConfigService} 的当前快照，与配置项同属一个版本：
 * 每次判定先一次性拿到整个快照引用，因此并发热更新下任何一次判定
 * 只能观察到某一个完整版本，不会看到新旧混合的中间态。
 *
 * 分流算法：bucket = SHA-256(flagName + ":" + identifier) 前 8 字节
 * 取无符号 long 后对 100 取模，得到 [0,100) 的桶号；
 * bucket &lt; percentage 时放行。哈希只依赖 (flag, identifier)，
 * 因此同一标识在服务内部调用与 HTTP 入口、不同时间点的结论一致且可复现。
 *
 * 边界语义：percentage = 0 一律不放行，100 一律放行；
 * 未知开关一律不放行并显式给出 UNKNOWN_FLAG，绝不静默放行。
 */
public class FeatureFlagService {

    private final ConfigService configService;

    public FeatureFlagService(ConfigService configService) {
        this.configService = configService;
    }

    /** 当前生效的全部开关（来自当前版本快照）。 */
    public Map<String, FeatureFlag> currentFlags() {
        return FlagConfigCodec.decodeAll(configService.current().values());
    }

    /**
     * 分流判定。服务内部调用与 HTTP 入口共用本方法，
     * 结论中的版本号、比例、桶号、原因完全一致。
     */
    public FlagDecision decide(String flagName, String identifier) {
        ConfigSnapshot snapshot = configService.current();
        FeatureFlag flag = FlagConfigCodec.decodeOne(flagName, snapshot.values());
        long version = snapshot.version();
        if (flag == null) {
            return new FlagDecision(flagName, identifier, false, -1, null, version,
                    FlagDecision.Reason.UNKNOWN_FLAG,
                    "flag '" + flagName + "' is not defined in version " + version
                            + "; denied by default");
        }
        if (!flag.enabled()) {
            return new FlagDecision(flagName, identifier, false, -1, flag.percentage(), version,
                    FlagDecision.Reason.FLAG_DISABLED,
                    "flag '" + flagName + "' is disabled in version " + version + "; denied");
        }
        int bucket = bucketOf(flagName, identifier);
        boolean allowed = bucket < flag.percentage();
        FlagDecision.Reason reason = allowed
                ? FlagDecision.Reason.WITHIN_PERCENTAGE
                : FlagDecision.Reason.OUTSIDE_PERCENTAGE;
        String explanation = "bucket(" + bucket + ") "
                + (allowed ? "< " : ">= ")
                + "percentage(" + flag.percentage() + ") @version " + version;
        return new FlagDecision(flagName, identifier, allowed, bucket, flag.percentage(), version,
                reason, explanation);
    }

    /**
     * 以版本化提交的方式新增/更新开关：与配置覆盖走同一条原子提交路径，
     * 要么进入新版本，要么整体不变。
     */
    public UpdateResult upsertFlag(FeatureFlag flag, Long expectedVersion) {
        return configService.commit(Map.of(), FlagConfigCodec.encode(flag), expectedVersion);
    }

    /**
     * 一次提交配置覆盖与多个开关变更，整体原子生效。
     *
     * @param configOverrides 配置覆盖（整体替换）
     * @param flags           开关变更（在当前版本开关集合上增量合并）
     */
    public UpdateResult commitAll(Map<String, String> configOverrides, Map<String, FeatureFlag> flags,
                                  Long expectedVersion) {
        Map<String, String> flagOverrides = new LinkedHashMap<>();
        flags.values().forEach(f -> flagOverrides.putAll(FlagConfigCodec.encode(f)));
        return configService.commit(configOverrides, flagOverrides, expectedVersion);
    }

    /** 稳定分桶：只依赖输入，与进程、时间、入口无关。 */
    static int bucketOf(String flagName, String identifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((flagName + ":" + identifier).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFFL);
            }
            return (int) Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
