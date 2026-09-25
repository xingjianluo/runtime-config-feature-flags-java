package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigSnapshot;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ResolvedValue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 配置只读入口：读取的是调用时刻的完整不可变快照。
 */
@RestController
@RequestMapping("/api/config")
public final class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/snapshot")
    public ConfigSnapshot snapshot() {
        return configService.current();
    }

    @GetMapping("/keys/{name}")
    public ResponseEntity<Object> key(@PathVariable String name) {
        try {
            ResolvedValue v = configService.current().get(name);
            return ResponseEntity.ok(v);
        } catch (NoSuchElementException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("found", false);
            body.put("key", name);
            body.put("detail", e.getMessage());
            return ResponseEntity.status(404).body(body);
        }
    }

    @GetMapping("/history")
    public Map<String, Object> history() {
        List<Long> versions = configService.history();
        return Map.of("currentVersion", configService.current().version(),
                "versions", versions);
    }
}
