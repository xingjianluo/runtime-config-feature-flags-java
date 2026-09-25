package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.List;

/**
 * 内置注册键定义与功能开关键约定。
 *
 * <p>功能开关比例通过动态键配置：{@code feature.flags.<开关名>.percentage}，
 * 取值为 [0,100] 的整数。开关名非空、只允许字母数字、点、下划线与短横线。
 * 未配置的开关属于“未知开关”，分流时一律 fail-closed（不放行）。
 */
public final class ConfigKeys {

    public static final String HTTP_TIMEOUT_MS = "app.http.timeout-ms";
    public static final String MAX_RETRIES = "app.http.max-retries";
    public static final String CIRCUIT_ENABLED = "app.resilience.circuit-enabled";
    public static final String FEATURE_FLAG_PREFIX = "feature.flags.";
    public static final String FEATURE_FLAG_SUFFIX = ".percentage";

    private ConfigKeys() {
    }

    public static ConfigRegistry defaultRegistry() {
        List<ConfigKey> fixed = List.of(
                new ConfigKey(HTTP_TIMEOUT_MS, ValueKind.INTEGER, "1000", 1L, 60000L),
                new ConfigKey(MAX_RETRIES, ValueKind.INTEGER, "3", 0L, 10L),
                new ConfigKey(CIRCUIT_ENABLED, ValueKind.BOOLEAN, "true", null, null)
        );
        return new ConfigRegistry(fixed);
    }

    /** 识别功能开关比例键，返回开关名；非该类键返回 null。 */
    public static String featureFlagName(String key) {
        if (key == null
                || !key.startsWith(FEATURE_FLAG_PREFIX)
                || !key.endsWith(FEATURE_FLAG_SUFFIX)) {
            return null;
        }
        String name = key.substring(
                FEATURE_FLAG_PREFIX.length(),
                key.length() - FEATURE_FLAG_SUFFIX.length());
        if (name.isEmpty()) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }
        return name;
    }
}
