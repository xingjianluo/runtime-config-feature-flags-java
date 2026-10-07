package com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.RuntimeSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 功能开关服务：基于稳定哈希的按比例灰度分流。
 *
 * 分流算法：bucket = SHA-256(flagName + ":" + identifier) 前 8 字节
 * 取无符号 long 后对 100 取模，得到 [0,100) 的桶号；
 * bucket &lt; percentage 时放行。哈希只依赖 (flag, identifier)，
 * 因此同一标识在不同入口、不同时间点的结论一致且可复现。
 *
 * 边界语义：percentage = 0 一律不放行，100 一律放行；
 * 未知开关一律不放行并显式给出 UNKNOWN_FLAG，绝不静默放行。
 *
 * 两种装配方式：
 * <ul>
 *   <li>绑定统一版本容器（{@link #FeatureFlagService(Supplier)}）：开关定义
 *       与判定依据的版本号均来自 {@link RuntimeSnapshot}，配置/开关热更新时
 *       判定只会看到某个完整版本；HTTP 入口与服务内部调用都委托
 *       {@link #decide(String, String)}，因此同一标识两入口结论逐项一致。</li>
 *   <li>独立模式（{@link #FeatureFlagService()}）：仅用于轻量单测，
 *       {@link #upsert(FeatureFlag)} 直接写本地定义，结论不带版本号。</li>
 * </ul>
 */
public class FeatureFlagService {

    private final Map<String, FeatureFlag> flags = new ConcurrentHashMap<>();
    private final Supplier<RuntimeSnapshot> snapshotSupplier;

    /** 独立模式：开关定义经 {@link #upsert(FeatureFlag)} 直接维护。 */
    public FeatureFlagService() {
        this.snapshotSupplier = null;
    }

    /**
     * 绑定模式：开关定义与版本号全部取自统一快照提供者。
     * 绑定后不得再调用 {@link #upsert(FeatureFlag)}，开关变更必须经统一提交。
     */
    public FeatureFlagService(Supplier<RuntimeSnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    public boolean isBound() {
        return snapshotSupplier != null;
    }

    /** 仅独立模式可用；绑定模式下必须经统一版本提交，禁止旁路写入。 */
    public void upsert(FeatureFlag flag) {
        if (snapshotSupplier != null) {
            throw new UnsupportedOperationException(
                    "bound FeatureFlagService cannot upsert directly; submit through the unified runtime commit");
        }
        flags.put(flag.name(), flag);
    }

    public FeatureFlag get(String name) {
        if (snapshotSupplier != null) {
            return snapshotSupplier.get().flag(name);
        }
        return flags.get(name);
    }

    public FlagDecision decide(String flagName, String identifier) {
        if (snapshotSupplier != null) {
            RuntimeSnapshot snapshot = snapshotSupplier.get();
            return decideAgainst(flagName, identifier, snapshot.flag(flagName))
                    .withVersion(snapshot.version());
        }
        return decideAgainst(flagName, identifier, flags.get(flagName));
    }

    /**
     * 对一份给定的开关定义执行判定。这是唯一的判定逻辑入口，
     * 内部调用与 HTTP 入口、绑定模式与独立模式全部复用，保证结论一致。
     */
    private static FlagDecision decideAgainst(String flagName, String identifier, FeatureFlag flag) {
        if (flag == null) {
            return new FlagDecision(flagName, identifier, false, -1, null,
                    FlagDecision.Reason.UNKNOWN_FLAG,
                    "flag '" + flagName + "' is not defined; denied by default");
        }
        if (!flag.enabled()) {
            return new FlagDecision(flagName, identifier, false, -1, flag.percentage(),
                    FlagDecision.Reason.FLAG_DISABLED,
                    "flag '" + flagName + "' is disabled; denied");
        }
        int bucket = bucketOf(flagName, identifier);
        boolean allowed = bucket < flag.percentage();
        FlagDecision.Reason reason = allowed
                ? FlagDecision.Reason.WITHIN_PERCENTAGE
                : FlagDecision.Reason.OUTSIDE_PERCENTAGE;
        String explanation = "bucket(" + bucket + ") "
                + (allowed ? "< " : ">= ")
                + "percentage(" + flag.percentage() + ")";
        return new FlagDecision(flagName, identifier, allowed, bucket, flag.percentage(),
                reason, explanation);
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
