package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** properties 文件配置来源，支持 classpath 与文件系统路径。 */
public class PropertiesFileConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private final String classpathResource;
    private final Path filePath;

    private PropertiesFileConfigSource(String name, int priority, String classpathResource, Path filePath) {
        this.name = name;
        this.priority = priority;
        this.classpathResource = classpathResource;
        this.filePath = filePath;
    }

    public static PropertiesFileConfigSource fromClasspath(String name, int priority, String resource) {
        return new PropertiesFileConfigSource(name, priority, resource, null);
    }

    public static PropertiesFileConfigSource fromFile(String name, int priority, Path path) {
        return new PropertiesFileConfigSource(name, priority, null, path);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, String> load() throws IOException {
        Properties props = new Properties();
        if (classpathResource != null) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
                if (in == null) {
                    return Map.of();
                }
                props.load(in);
            }
        } else {
            if (!Files.exists(filePath)) {
                return Map.of();
            }
            try (InputStream in = Files.newInputStream(filePath)) {
                props.load(in);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            result.put(key, props.getProperty(key));
        }
        return result;
    }
}
