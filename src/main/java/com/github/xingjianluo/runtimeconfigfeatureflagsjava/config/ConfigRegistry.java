package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已注册配置键的注册表。
 *
 * <p>除固定键外，{@code feature.flags.<name>.percentage} 形态的键被识别为
 * 动态的功能开关比例键（整数 [0,100]）；其余名称为未知键。
 */
public final class ConfigRegistry {

    private final Map<String, ConfigKey> fixedKeys;

    public ConfigRegistry(List<ConfigKey> keys) {
        this.fixedKeys = new LinkedHashMap<>();
        for (ConfigKey key : keys) {
            if (fixedKeys.put(key.name(), key) != null) {
                throw new IllegalArgumentException("重复注册的配置键: " + key.name());
            }
        }
    }

    /** 精确查找：固定键或动态开关比例键；未知名称返回 empty。 */
    public Optional<ConfigKey> find(String name) {
        ConfigKey fixed = fixedKeys.get(name);
        if (fixed != null) {
            return Optional.of(fixed);
        }
        if (ConfigKeys.featureFlagName(name) != null) {
            // 以动态键本名返回一个合法范围 [0,100] 的键描述。
            return Optional.of(new ConfigKey(name, ValueKind.INTEGER, "0", 0L, 100L));
        }
        return Optional.empty();
    }

    /** 是否为注册键（固定键或开关比例键）。 */
    public boolean isRegistered(String name) {
        return find(name).isPresent();
    }

    /** 是否为功能开关比例键。 */
    public static boolean isFeaturePercentageKey(String name) {
        return ConfigKeys.featureFlagName(name) != null;
    }

    public Collection<ConfigKey> fixedKeys() {
        return Collections.unmodifiableCollection(fixedKeys.values());
    }
}
