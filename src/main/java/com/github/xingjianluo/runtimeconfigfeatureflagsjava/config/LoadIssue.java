package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

/**
 * 装载过程中发现的问题。
 *
 * @param kind       问题类别
 * @param key        配置键
 * @param sourceName 来源名称
 * @param rawValue   原始值
 * @param message    说明
 */
public record LoadIssue(Kind kind, String key, String sourceName, String rawValue, String message) {

    public enum Kind {
        /** 来源不可达 / 读取失败 */
        SOURCE_UNREACHABLE,
        /** 值无法解析为目标类型 */
        TYPE_MISMATCH,
        /** 类型正确但取值非法（未通过校验器） */
        INVALID_VALUE
    }
}
