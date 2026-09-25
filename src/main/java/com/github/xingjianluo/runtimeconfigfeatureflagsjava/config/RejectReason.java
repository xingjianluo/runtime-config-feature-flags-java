package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

/**
 * 配置版本提交被拒绝的可区分原因。
 */
public enum RejectReason {
    /** 提交的值无法解析为声明的类型 */
    TYPE_MISMATCH,
    /** 值类型正确但不满足合法性约束（如越界） */
    INVALID_VALUE,
    /** 提交包含未注册的未知配置键 */
    UNKNOWN_KEY,
    /** 提交包含空白值 */
    EMPTY_VALUE,
    /** 乐观版本冲突：expectedVersion 与当前版本不一致 */
    VERSION_CONFLICT,
    /** 回滚目标版本不存在于保留的历史中 */
    VERSION_NOT_FOUND,
    /** 重新装载时读取来源发生 I/O 错误 */
    SOURCE_IO_ERROR
}
