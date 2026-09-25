package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

/**
 * 单个配置项的解析结论：值 + 判定依据。
 */
public record ResolvedValue(
        String key,
        Object value,
        ValueKind kind,
        ResolveStatus status,
        String source,
        String detail
) {
}
