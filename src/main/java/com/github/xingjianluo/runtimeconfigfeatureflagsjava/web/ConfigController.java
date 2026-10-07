package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import org.springframework.http.ResponseEntity;
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

    /** 提交配置覆盖（开关自动保留）；被拒绝时返回明确 4xx。 */
    @PostMapping("/overrides")
    public ResponseEntity<UpdateResult> applyOverrides(@RequestBody Map<String, String> overrides,
                                                       @RequestParam(required = false) Long expectedVersion) {
        return UpdateResponses.of(configService.applyOverrides(overrides, expectedVersion));
    }

    /** 回滚到指定版本：配置与开关一起回到该版本当时的取值。 */
    @PostMapping("/rollback/{version}")
    public ResponseEntity<UpdateResult> rollback(@PathVariable long version) {
        return UpdateResponses.of(configService.rollback(version));
    }
}
