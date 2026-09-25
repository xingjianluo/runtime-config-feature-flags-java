package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.util.Map;

/** 配置来源。优先级数值越大越优先。 */
public interface ConfigSource {

    String name();

    int priority();

    /** 读取该来源的全部键值；读取失败时抛出异常，由装载器记录为 SOURCE_UNREACHABLE。 */
    Map<String, String> load() throws Exception;
}
