package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.Optional;

/**
 * 一个已注册的强类型配置键。
 *
 * <p>每个键声明：名称、值类型、缺省值（必须本身合法）以及可选的整数取值范围。
 * 来源原始字符串通过 {@link #parse(String)} 得到明确的三态结论：
 * 合法（RESOLVED）、类型不匹配（TYPE_MISMATCH）、取值非法（INVALID），
 * 三种情况严格可区分，非法值绝不被当作有效值采用。
 */
public final class ConfigKey {

    /** 单个原始值的解析结论。 */
    public record ParseResult(ResolveStatus status, Object value, String detail) {
        static ParseResult ok(Object value) {
            return new ParseResult(ResolveStatus.RESOLVED, value, null);
        }
        static ParseResult typeMismatch(String raw, String detail) {
            return new ParseResult(ResolveStatus.TYPE_MISMATCH, null, detail + "; 原始值=[" + raw + "]");
        }
        static ParseResult invalid(String raw, String detail) {
            return new ParseResult(ResolveStatus.INVALID, null, detail + "; 原始值=[" + raw + "]");
        }
    }

    private final String name;
    private final ValueKind kind;
    private final String defaultValue;
    private final Long min;
    private final Long max;

    public ConfigKey(String name, ValueKind kind, String defaultValue, Long min, Long max) {
        this.name = name;
        this.kind = kind;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        ParseResult def = parse(defaultValue);
        if (def.status() != ResolveStatus.RESOLVED) {
            throw new IllegalArgumentException("键 " + name + " 的缺省值本身不合法: " + def.detail());
        }
    }

    public String name() { return name; }
    public ValueKind kind() { return kind; }
    public String defaultValue() { return defaultValue; }
    public Optional<Long> min() { return Optional.ofNullable(min); }
    public Optional<Long> max() { return Optional.ofNullable(max); }

    /** 缺省值按声明类型解析后的对象。 */
    public Object parsedDefault() {
        return parse(defaultValue).value();
    }

    /**
     * 解析来源原始字符串。
     * 空白字符串视为“未提供”，交由上层走缺省逻辑。
     */
    public ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(ResolveStatus.DEFAULT, null, "值为空，视为未提供");
        }
        String s = raw.trim();
        return switch (kind) {
            case INTEGER -> parseInt(s);
            case BOOLEAN -> parseBoolean(s);
        };
    }

    private ParseResult parseInt(String s) {
        long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException e) {
            return ParseResult.typeMismatch(s, "无法解析为整数");
        }
        if (min != null && v < min) {
            return ParseResult.invalid(s, "整数 " + v + " 小于下限 " + min);
        }
        if (max != null && v > max) {
            return ParseResult.invalid(s, "整数 " + v + " 超过上限 " + max);
        }
        return ParseResult.ok(v);
    }

    private ParseResult parseBoolean(String s) {
        // 严格只接受 true/false（忽略大小写），避免 "yes"、"1" 被静默当作 false。
        if (s.equalsIgnoreCase("true")) {
            return ParseResult.ok(Boolean.TRUE);
        }
        if (s.equalsIgnoreCase("false")) {
            return ParseResult.ok(Boolean.FALSE);
        }
        return ParseResult.typeMismatch(s, "布尔值只允许 true/false");
    }
}
