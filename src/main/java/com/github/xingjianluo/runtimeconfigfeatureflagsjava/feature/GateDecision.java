package com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature;

/**
 * 功能开关分流结论（含判定依据）。
 */
public record GateDecision(
        String flag,
        String identity,
        boolean enabled,
        int percentage,
        long bucket,
        String reason,
        long configVersion
) {
}
