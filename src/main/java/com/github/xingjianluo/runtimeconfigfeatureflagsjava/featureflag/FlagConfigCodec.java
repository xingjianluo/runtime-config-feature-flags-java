package com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 开关与配置键之间的编解码器。
 *
 * 开关以配置键形式纳入统一的版本快照：
 *   flag.&lt;name&gt;.enabled    = true|false（缺省 false）
 *   flag.&lt;name&gt;.percentage = 0..100（缺省 0）
 *
 * 因此开关的启用状态与灰度比例跟配置项共享同一套版本、
 * 同一次原子提交与同一条回滚路径。
 */
public final class FlagConfigCodec {

    private FlagConfigCodec() {
    }

    public static String enabledKey(String flagName) {
        return ConfigLoader.FLAG_PREFIX + flagName + ".enabled";
    }

    public static String percentageKey(String flagName) {
        return ConfigLoader.FLAG_PREFIX + flagName + ".percentage";
    }

    /** 把一个开关定义编码为两个配置键。 */
    public static Map<String, String> encode(FeatureFlag flag) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(enabledKey(flag.name()), Boolean.toString(flag.enabled()));
        map.put(percentageKey(flag.name()), Integer.toString(flag.percentage()));
        return map;
    }

    /** 从一份已解析的值集合（通常是快照 values）中解码出全部开关。 */
    public static Map<String, FeatureFlag> decodeAll(Map<String, String> values) {
        Map<String, FeatureFlag> flags = new TreeMap<>();
        for (String key : values.keySet()) {
            String name = flagNameOf(key);
            if (name == null) {
                continue;
            }
            boolean enabled = Boolean.parseBoolean(values.getOrDefault(enabledKey(name), "false"));
            int percentage = Integer.parseInt(values.getOrDefault(percentageKey(name), "0").trim());
            flags.put(name, new FeatureFlag(name, enabled, percentage));
        }
        return flags;
    }

    /** 从值集合中解码单个开关；不存在时返回 null。 */
    public static FeatureFlag decodeOne(String flagName, Map<String, String> values) {
        if (!values.containsKey(enabledKey(flagName)) && !values.containsKey(percentageKey(flagName))) {
            return null;
        }
        boolean enabled = Boolean.parseBoolean(values.getOrDefault(enabledKey(flagName), "false"));
        int percentage = Integer.parseInt(values.getOrDefault(percentageKey(flagName), "0").trim());
        return new FeatureFlag(flagName, enabled, percentage);
    }

    /** 从 flag.&lt;name&gt;.enabled|percentage 键中提取开关名；非开关键返回 null。 */
    private static String flagNameOf(String key) {
        if (!key.startsWith(ConfigLoader.FLAG_PREFIX)) {
            return null;
        }
        String rest = key.substring(ConfigLoader.FLAG_PREFIX.length());
        int dot = rest.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String field = rest.substring(dot + 1);
        if (!field.equals("enabled") && !field.equals("percentage")) {
            return null;
        }
        return rest.substring(0, dot);
    }
}
