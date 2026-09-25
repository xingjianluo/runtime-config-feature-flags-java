package com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag;

/**
 * 功能开关定义。
 *
 * @param name       开关名
 * @param enabled    是否启用
 * @param percentage 灰度比例 0-100：0 表示谁都不放行，100 表示全部放行
 */
public record FeatureFlag(String name, boolean enabled, int percentage) {

    public FeatureFlag {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be in [0,100], got " + percentage);
        }
    }
}
