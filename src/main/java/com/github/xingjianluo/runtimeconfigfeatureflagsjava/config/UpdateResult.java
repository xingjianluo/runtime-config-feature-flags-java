package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.List;

/**
 * 一次更新/回滚的结果。
 *
 * @param accepted 是否被接受并生效
 * @param version  生效后的版本号；被拒绝时为 null
 * @param reason   拒绝原因；被接受时为 NONE
 * @param issues   装载/校验过程中发现的问题
 * @param message  可读的判定依据
 */
public record UpdateResult(boolean accepted,
                           Long version,
                           RejectionReason reason,
                           List<LoadIssue> issues,
                           String message) {

    public enum RejectionReason {
        NONE,
        /** 新配置校验失败（类型不匹配 / 取值非法 / 来源不可达） */
        VALIDATION_FAILED,
        /** 期望版本与当前版本不一致，存在并发更新冲突 */
        VERSION_CONFLICT,
        /** 要回滚到的版本不存在 */
        UNKNOWN_ROLLBACK_VERSION
    }

    public static UpdateResult ok(long version, List<LoadIssue> issues, String message) {
        return new UpdateResult(true, version, RejectionReason.NONE, List.copyOf(issues), message);
    }

    public static UpdateResult rejected(RejectionReason reason, List<LoadIssue> issues, String message) {
        return new UpdateResult(false, null, reason, List.copyOf(issues), message);
    }
}
