package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.function.Predicate;

/**
 * 配置项定义：键、类型、缺省值与取值校验器。
 *
 * @param key          配置键
 * @param type         值类型
 * @param defaultValue 缺省值（字符串形式，缺省值本身必须合法）
 * @param validator    取值校验器，对解析后的字符串形式再校验；为 null 表示只校验类型
 * @param description  说明
 */
public record ConfigDefinition(String key,
                               ConfigValueType type,
                               String defaultValue,
                               Predicate<String> validator,
                               String description) {
}
