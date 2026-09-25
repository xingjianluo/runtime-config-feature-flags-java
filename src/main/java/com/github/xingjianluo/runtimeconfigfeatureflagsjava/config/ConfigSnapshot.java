package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不可变配置快照：某一次装载/生效的完整结果。
 * 读取方要么看到整个旧快照，要么看到整个新快照。
 */
public final class ConfigSnapshot {

    private final long version;
    private final Instant createdAt;
    private final Map<String, String> values;
    private final List<LoadIssue> issues;

    public ConfigSnapshot(long version, Instant createdAt, Map<String, String> values, List<LoadIssue> issues) {
        this.version = version;
        this.createdAt = createdAt;
        this.values = Map.copyOf(values);
        this.issues = List.copyOf(issues);
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<String, String> values() {
        return values;
    }

    public List<LoadIssue> issues() {
        return issues;
    }

    public String get(String key) {
        return values.get(key);
    }

    public int getInt(String key) {
        return Integer.parseInt(values.get(key));
    }

    public long getLong(String key) {
        return Long.parseLong(values.get(key));
    }

    public double getDouble(String key) {
        return Double.parseDouble(values.get(key));
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(values.get(key));
    }
}
