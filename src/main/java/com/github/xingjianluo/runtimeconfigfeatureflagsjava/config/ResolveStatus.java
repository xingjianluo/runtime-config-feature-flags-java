package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

/**
 * 单个配置项最终取值的来源状态。
 */
public enum ResolveStatus {
    /** 取到了来源中类型与取值均合法的值 */
    RESOLVED,
    /** 所有来源均未提供有效值，使用键上声明的缺省值（此前无非法尝试） */
    DEFAULT,
    /** 来源提供了值，但类型不匹配，已被拒绝并降级 */
    TYPE_MISMATCH,
    /** 来源提供了类型正确的值，但取值非法（越界等），已被拒绝并降级 */
    INVALID,
    /** 装载阶段出现未注册键（仅出现在问题记录中，快照值不包含该键） */
    UNKNOWN_KEY
}
