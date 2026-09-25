package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

/**
 * 装载/合并过程中单个来源单个键出现的问题，用于可解释输出。
 */
public record KeyIssue(
        String source,
        String key,
        String rawValue,
        ResolveStatus status,
        String detail
) {
}
