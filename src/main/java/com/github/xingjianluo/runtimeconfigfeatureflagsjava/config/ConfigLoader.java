package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.ConfigSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置装载器：从多个来源读取并按优先级合并，
 * 对每个已注册配置项解析出有效值，同时收集可区分的问题。
 *
 * 合并规则：优先级数值大的来源覆盖小的；某来源的值非法时，
 * 该来源的该键被丢弃并记录问题，不会半生效，也不会覆盖掉
 * 低优先级来源或缺省值中的有效值。
 */
public class ConfigLoader {

    /** 开关键前缀：flag.&lt;name&gt;.enabled / flag.&lt;name&gt;.percentage */
    public static final String FLAG_PREFIX = "flag.";

    private final ConfigRegistry registry;
    private final List<ConfigSource> sources;

    public ConfigLoader(ConfigRegistry registry, List<ConfigSource> sources) {
        this.registry = registry;
        this.sources = sources.stream()
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();
    }

    public List<ConfigSource> sources() {
        return sources;
    }

    /**
     * 装载一个完整快照。缺省值、类型不匹配与取值非法分别产生
     * 不同的处理结果：缺省值静默采用（定义保证合法），
     * 类型不匹配记录 TYPE_MISMATCH，取值非法记录 INVALID_VALUE，
     * 后两者均丢弃该来源的该键并回退到下一优先级的有效值。
     */
    public ConfigSnapshot load(long version) {
        // 1. 逐来源读取；来源失败整体记为 SOURCE_UNREACHABLE，不影响其他来源
        Map<String, Map<String, String>> perSource = new LinkedHashMap<>();
        List<LoadIssue> issues = new ArrayList<>();
        for (ConfigSource source : sources) {
            try {
                perSource.put(source.name(), source.load());
            } catch (Exception e) {
                issues.add(new LoadIssue(LoadIssue.Kind.SOURCE_UNREACHABLE, "*", source.name(), null,
                        "source failed to load: " + e.getMessage()));
                perSource.put(source.name(), Map.of());
            }
        }

        // 2. 对每个已注册键，从低优先级到高优先级依次尝试，取第一个有效值
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigDefinition def : registry.all()) {
            values.put(def.key(), resolve(def, perSource, issues));
        }

        // 3. 解析开关键 flag.<name>.enabled / flag.<name>.percentage：
        //    开关与配置项走同一条装载/校验路径，因此进入同一个版本快照
        for (ConfigDefinition flagDef : discoverFlagDefinitions(perSource, issues)) {
            values.put(flagDef.key(), resolve(flagDef, perSource, issues));
        }
        return new ConfigSnapshot(version, java.time.Instant.now(), values, issues);
    }

    /** 从各来源发现开关键并生成对应的合成定义（含内置校验规则）。 */
    private List<ConfigDefinition> discoverFlagDefinitions(Map<String, Map<String, String>> perSource,
                                                           List<LoadIssue> issues) {
        Map<String, ConfigDefinition> flagDefs = new LinkedHashMap<>();
        for (ConfigSource source : sources) {
            for (String key : perSource.get(source.name()).keySet()) {
                if (!key.startsWith(FLAG_PREFIX)) {
                    continue;
                }
                String rest = key.substring(FLAG_PREFIX.length());
                int dot = rest.lastIndexOf('.');
                String field = dot > 0 ? rest.substring(dot + 1) : "";
                if (dot <= 0 || dot == rest.length() - 1
                        || (!field.equals("enabled") && !field.equals("percentage"))) {
                    issues.add(new LoadIssue(LoadIssue.Kind.INVALID_VALUE, key, source.name(),
                            perSource.get(source.name()).get(key),
                            "malformed flag key, expected flag.<name>.enabled|percentage"));
                    continue;
                }
                flagDefs.putIfAbsent(key, flagDefinition(key, field));
            }
        }
        return List.copyOf(flagDefs.values());
    }

    /** 开关键的合成定义：enabled 为 BOOLEAN，percentage 为 0..100 的 INTEGER。 */
    private ConfigDefinition flagDefinition(String key, String field) {
        if (field.equals("enabled")) {
            return new ConfigDefinition(key, ConfigValueType.BOOLEAN, "false", null,
                    "flag enabled switch, default false");
        }
        return new ConfigDefinition(key, ConfigValueType.INTEGER, "0",
                v -> {
                    int p = Integer.parseInt(v.trim());
                    return p >= 0 && p <= 100;
                }, "flag rollout percentage, 0..100, default 0");
    }

    /** 按来源优先级从低到高解析单个键，非法值丢弃并记录问题，回退到下一优先级的有效值。 */
    private String resolve(ConfigDefinition def, Map<String, Map<String, String>> perSource,
                           List<LoadIssue> issues) {
        String resolved = def.defaultValue();
        for (ConfigSource source : sources) {
            String raw = perSource.get(source.name()).get(def.key());
            if (raw == null) {
                continue;
            }
            LoadIssue problem = validate(def, raw, source.name());
            if (problem != null) {
                // 非法值：丢弃并记录，绝不半生效或静默覆盖已有的有效值
                issues.add(problem);
                continue;
            }
            resolved = raw;
        }
        return resolved;
    }

    /** 校验单个原始值；合法返回 null，否则返回对应类别的问题。 */
    private LoadIssue validate(ConfigDefinition def, String raw, String sourceName) {
        if (!typeMatches(def.type(), raw)) {
            return new LoadIssue(LoadIssue.Kind.TYPE_MISMATCH, def.key(), sourceName, raw,
                    "value cannot be parsed as " + def.type());
        }
        if (def.validator() != null && !def.validator().test(raw)) {
            return new LoadIssue(LoadIssue.Kind.INVALID_VALUE, def.key(), sourceName, raw,
                    "value rejected by validator of " + def.key());
        }
        return null;
    }

    private boolean typeMatches(ConfigValueType type, String raw) {
        try {
            switch (type) {
                case STRING -> {
                    return true;
                }
                case INTEGER -> Integer.parseInt(raw.trim());
                case LONG -> Long.parseLong(raw.trim());
                case DOUBLE -> Double.parseDouble(raw.trim());
                case BOOLEAN -> {
                    String v = raw.trim().toLowerCase();
                    return v.equals("true") || v.equals("false");
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
