package com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public class FeatureFlagService {

    private final Map<String, FeatureFlag> flags = new ConcurrentHashMap<>();

    public void upsert(FeatureFlag flag) {
        flags.put(flag.name(), flag);
    }

    public FeatureFlag get(String name) {
        return flags.get(name);
    }

    public FlagDecision decide(String flagName, String identifier) {
        FeatureFlag flag = flags.get(flagName);
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
