package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.web.RuntimeController;
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

    @PostMapping("/overrides")
    public ResponseEntity<UpdateResult> applyOverrides(@RequestBody Map<String, String> overrides,
                                                       @RequestParam(required = false) Long expectedVersion) {
        UpdateResult result = configService.applyOverrides(overrides, expectedVersion);
        return ResponseEntity.status(RuntimeController.httpStatusOf(result.reason())).body(result);
    }

    @PostMapping("/rollback/{version}")
    public ResponseEntity<UpdateResult> rollback(@PathVariable long version) {
        UpdateResult result = configService.rollback(version);
        return ResponseEntity.status(RuntimeController.httpStatusOf(result.reason())).body(result);
    }
}
