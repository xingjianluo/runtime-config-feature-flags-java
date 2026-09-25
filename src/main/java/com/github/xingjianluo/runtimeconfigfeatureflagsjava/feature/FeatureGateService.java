package com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigKeys;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigSnapshot;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ResolvedValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 功能开关灰度分流服务。
 *
 * <h3>分流算法（确定性、可复现）</h3>
 * <ol>
 *   <li>键名固定为 {@code feature.flags.<flag>.percentage}，取自当前配置版本，
 *       值为 [0,100] 的整数。</li>
 *   <li>分桶值 {@code bucket = floorMod(SHA-256(flag + ":" + identity) 前8字节大端长整数, 100)}，
 *       取值 0..99。哈希输入只取决于 (开关名, 标识)，因此与入口、时间、线程无关。</li>
 *   <li>{@code bucket < percentage} 判定为开，否则关。</li>
 * </ol>
 *
 * <h3>边界语义（不静默放行）</h3>
 * <ul>
 *   <li>未知开关（未配置比例）：fail-closed，恒为关，原因 UNKNOWN_FLAG。</li>
 *   <li>比例 0：恒为关，原因 ZERO_PERCENTAGE。</li>
 *   <li>比例 100：恒为开，原因 FULL_PERCENTAGE。</li>
 * </ul>
 *
 * <p>所有 HTTP/内部入口都只通过本服务做判定，保证跨入口一致且可解释。
 */
public final class FeatureGateService {

    private static final String UNKNOWN_FLAG = "UNKNOWN_FLAG";
    private static final String ZERO = "ZERO_PERCENTAGE";
    private static final String FULL = "FULL_PERCENTAGE";
    private static final String IN = "GRAY_IN";
    private static final String OUT = "GRAY_OUT";

    private final ConfigService configService;

    public FeatureGateService(ConfigService configService) {
        this.configService = configService;
    }

    /** 对某标识在指定开关上做分流判定；identity 不允许为空。 */
    public GateDecision evaluate(String flag, String identity) {
        if (flag == null || flag.isBlank()) {
            throw new IllegalArgumentException("开关名不能为空");
        }
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("分流标识不能为空");
        }
        // 一次判定只取一次快照：同一请求内结论永远对应同一个完整版本。
        ConfigSnapshot snapshot = configService.current();
        String key = ConfigKeys.FEATURE_FLAG_PREFIX + flag + ConfigKeys.FEATURE_FLAG_SUFFIX;
        ResolvedValue resolved = snapshot.values().get(key);

        long bucket = bucket(flag, identity);

        if (resolved == null) {
            return new GateDecision(flag, identity, false, -1, bucket,
                    UNKNOWN_FLAG + ": 开关 [" + flag + "] 未配置比例，fail-closed 不放行；"
                            + "bucket=" + bucket, snapshot.version());
        }
        int percentage = ((Long) resolved.value()).intValue();
        if (percentage == 0) {
            return new GateDecision(flag, identity, false, 0, bucket,
                    ZERO + ": 比例为 0，全量关闭；bucket=" + bucket, snapshot.version());
        }
        if (percentage == 100) {
            return new GateDecision(flag, identity, true, 100, bucket,
                    FULL + ": 比例为 100，全量开启；bucket=" + bucket, snapshot.version());
        }
        boolean enabled = bucket < percentage;
        return new GateDecision(flag, identity, enabled, percentage, bucket,
                (enabled ? IN : OUT) + ": bucket=" + bucket + (enabled ? " < " : " >= ")
                        + "percentage=" + percentage
                        + "；配置来源状态=" + resolved.status() + " [" + resolved.source() + "]",
                snapshot.version());
    }

    /**
     * 稳定哈希分桶：SHA-256(flag + ":" + identity)，前 8 字节大端转 long，floorMod 100。
     * 同 (flag, identity) 在任何 JVM、任何入口、任何时间点结果相同。
     */
    public static long bucket(String flag, String identity) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((flag + ":" + identity).getBytes(StandardCharsets.UTF_8));
            long h = 0L;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xffL);
            }
            return Math.floorMod(h, 100L);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 便于测试/文档输出哈希摘要。 */
    public static String hashHex(String flag, String identity) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest((flag + ":" + identity).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
