package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 本地 .properties 文件来源（UTF-8 读取）。
 *
 * <p>文件不存在视为“该来源未提供任何值”（返回空 Map），不抛异常，
 * 以便只用部分文件即可启动；读取错误向上抛出由调用方决定。
 */
public final class PropertiesFileConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private final Path path;

    public PropertiesFileConfigSource(String name, int priority, Path path) {
        this.name = name;
        this.priority = priority;
        this.path = path;
    }

    @Override public String name() { return name; }

    @Override public int priority() { return priority; }

    @Override
    public Map<String, String> load() {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("读取配置文件失败 [" + name + "] " + path, e);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            result.put(key, props.getProperty(key));
        }
        return result;
    }
}
