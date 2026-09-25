package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.util.Map;

/**
 * 内存来源：表示运行时 {@code submit} 提交的最高优先级覆盖层。
 *
 * <p>{@link #replace(Map)} 以不可变副本整体替换，读取永远拿到一个完整 Map，
 * 不会观察到半更新状态。
 */
public final class InMemoryConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private volatile Map<String, String> values;

    public InMemoryConfigSource(String name, int priority, Map<String, String> values) {
        this.name = name;
        this.priority = priority;
        this.values = Map.copyOf(values);
    }

    @Override public String name() { return name; }

    @Override public int priority() { return priority; }

    @Override public Map<String, String> load() {
        return values;
    }

    /** 原子替换本来源的全部内容。 */
    public void replace(Map<String, String> next) {
        this.values = Map.copyOf(next);
    }
}
