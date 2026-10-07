package com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.CommitRequest;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.RuntimeSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一入口：配置与开关同一版本提交，查询当前统一状态。
 * 被拒绝的提交在 HTTP 层明确给出 4xx 状态，不会表现成成功返回：
 * VALIDATION_FAILED -&gt; 422，VERSION_CONFLICT -&gt; 409，
 * UNKNOWN_ROLLBACK_VERSION -&gt; 404。
 */
@RestController
@RequestMapping("/runtime")
public class RuntimeController {

    private final ConfigService configService;

    public RuntimeController(ConfigService configService) {
        this.configService = configService;
    }

    /** 统一提交：配置覆盖与开关定义原子生效。 */
    @PostMapping("/commit")
    public ResponseEntity<UpdateResult> commit(
            @RequestBody CommitRequest request,
            @RequestParam(required = false) Long expectedVersion) {
        UpdateResult result = configService.commit(request, expectedVersion);
        return ResponseEntity.status(httpStatusOf(result.reason())).body(result);
    }

    /** 当前统一版本：版本号、配置值/问题、开关定义。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        RuntimeSnapshot snapshot = configService.state();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", snapshot.version());
        body.put("createdAt", snapshot.createdAt().toString());
        body.put("values", snapshot.config().values());
        body.put("issues", snapshot.config().issues());
        body.put("flags", snapshot.flags());
        return body;
    }

    public static HttpStatus httpStatusOf(UpdateResult.RejectionReason reason) {
        return switch (reason) {
            case NONE -> HttpStatus.OK;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case UNKNOWN_ROLLBACK_VERSION -> HttpStatus.NOT_FOUND;
        };
    }
}
