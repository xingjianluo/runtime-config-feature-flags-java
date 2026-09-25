package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

/**
 * 提交版本校验阶段的单个错误。
 */
public record ValidationError(
        String key,
        String rawValue,
        RejectReason reason,
        String detail
) {
}
