package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理入口：重新装载、原子提交覆盖层、回滚。
 * 拒绝时返回 409，响应体携带逐条、可区分的拒绝原因。
 */
@RestController
@RequestMapping("/api/admin/config")
public final class AdminController {

    private final ConfigService configService;

    public AdminController(ConfigService configService) {
        this.configService = configService;
    }

    public record SubmitRequest(long expectedVersion, Map<String, String> values) {
    }

    @PostMapping("/reload")
    public ResponseEntity<UpdateResult> reload() {
        UpdateResult result = configService.reload();
        return result.accepted() ? ResponseEntity.ok(result) : ResponseEntity.status(409).body(result);
    }

    @PostMapping("/submit")
    public ResponseEntity<UpdateResult> submit(@RequestBody SubmitRequest request) {
        UpdateResult result = configService.submit(request.values(), request.expectedVersion());
        return result.accepted() ? ResponseEntity.ok(result) : ResponseEntity.status(409).body(result);
    }

    @PostMapping("/rollback/{version}")
    public ResponseEntity<UpdateResult> rollback(@PathVariable long version) {
        UpdateResult result = configService.rollback(version);
        return result.accepted() ? ResponseEntity.ok(result) : ResponseEntity.status(409).body(result);
    }
}
