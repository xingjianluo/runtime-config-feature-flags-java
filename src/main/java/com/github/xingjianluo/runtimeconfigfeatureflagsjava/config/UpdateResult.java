package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.List;

/**
 * 一次更新/回滚的结论。
 *
 * @param accepted       是否生效；false 时当前版本与旧配置保持不变
 * @param currentVersion 操作后（或被拒绝时当前）的版本号
 * @param errors         被拒绝时逐条、可区分原因的错误列表
 * @param detail         人类可读的说明
 */
public record UpdateResult(
        boolean accepted,
        long currentVersion,
        List<ValidationError> errors,
        String detail
) {
    public static UpdateResult accepted(long version, String detail) {
        return new UpdateResult(true, version, List.of(), detail);
    }

    public static UpdateResult rejected(long currentVersion, List<ValidationError> errors) {
        String detail = errors.isEmpty()
                ? "更新被拒绝"
                : "更新被拒绝，共 " + errors.size() + " 项错误，旧版本 " + currentVersion + " 继续可用";
        return new UpdateResult(false, currentVersion, List.copyOf(errors), detail);
    }
}
