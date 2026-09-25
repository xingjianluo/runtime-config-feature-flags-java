package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 配置项注册表：所有受管配置项的定义集合。 */
public class ConfigRegistry {

    private final Map<String, ConfigDefinition> definitions = new LinkedHashMap<>();

    public ConfigRegistry register(ConfigDefinition definition) {
        definitions.put(definition.key(), definition);
        return this;
    }

    public ConfigDefinition get(String key) {
        return definitions.get(key);
    }

    public Collection<ConfigDefinition> all() {
        return definitions.values();
    }
}
