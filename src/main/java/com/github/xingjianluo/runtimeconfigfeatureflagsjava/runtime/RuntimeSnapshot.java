package com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigSnapshot;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;

import java.time.Instant;
import java.util.Map;

/**
 * 运行时统一快照：某一版本下配置与开关的完整状态。
 *
 * 不可变：配置部分为不可变 {@link ConfigSnapshot}，开关集合构造时
 * 做防御性拷贝；读取方一次性拿到整个快照引用，更新只替换引用，
 * 因此任何一次读取只能观察到某个完整版本，配置与开关同属一个版本号，
 * 不可能看到新旧混合的中间态。
 */
public final class RuntimeSnapshot {

    private final ConfigSnapshot config;
    private final Map<String, FeatureFlag> flags;

    public RuntimeSnapshot(ConfigSnapshot config, Map<String, FeatureFlag> flags) {
        this.config = config;
        this.flags = Map.copyOf(flags);
    }

    public long version() {
        return config.version();
    }

    public Instant createdAt() {
        return config.createdAt();
    }

    public ConfigSnapshot config() {
        return config;
    }

    public Map<String, FeatureFlag> flags() {
        return flags;
    }

    public FeatureFlag flag(String name) {
        return flags.get(name);
    }
}
