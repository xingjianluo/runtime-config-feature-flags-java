package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.util.LinkedHashMap;
import java.util.Map;

/** 内存配置来源，用于运行时覆盖。 */
public class InMemoryConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private volatile Map<String, String> values = Map.of();

    public InMemoryConfigSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public void setValues(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, String> load() {
        return values;
    }
}
