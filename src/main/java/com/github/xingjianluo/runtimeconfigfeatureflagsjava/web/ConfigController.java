package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 配置入口：查询当前生效配置、提交覆盖更新、回滚。 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public Map<String, Object> current() {
        var snapshot = configService.current();
        return Map.of(
                "version", snapshot.version(),
                "createdAt", snapshot.createdAt().toString(),
                "values", snapshot.values(),
                "issues", snapshot.issues());
    }

    @PostMapping("/overrides")
    public UpdateResult applyOverrides(@RequestBody Map<String, String> overrides,
                                       @RequestParam(required = false) Long expectedVersion) {
        return configService.applyOverrides(overrides, expectedVersion);
    }

    @PostMapping("/rollback/{version}")
    public UpdateResult rollback(@PathVariable long version) {
        return configService.rollback(version);
    }
}
