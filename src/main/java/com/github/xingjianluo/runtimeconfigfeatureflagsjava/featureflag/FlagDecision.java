package com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag;

/**
 * 一次分流判定的结论，包含可解释的判定依据。
 *
 * @param flag        开关名
 * @param identifier  分流标识（如用户 id）
 * @param allowed     是否放行
 * @param bucket      标识落入的桶号 [0,100)；未知开关时为 -1
 * @param percentage  开关当前比例；未知开关时为 null
 * @param reason      判定原因
 * @param explanation 可读说明
 */
public record FlagDecision(String flag,
                           String identifier,
                           boolean allowed,
                           int bucket,
                           Integer percentage,
                           Reason reason,
                           String explanation) {

    public enum Reason {
        /** 开关不存在，一律不放行，绝不静默放行 */
        UNKNOWN_FLAG,
        /** 开关被禁用 */
        FLAG_DISABLED,
        /** 桶号落在比例之内 */
        WITHIN_PERCENTAGE,
        /** 桶号落在比例之外 */
        OUTSIDE_PERCENTAGE
    }
}
