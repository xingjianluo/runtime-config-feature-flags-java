package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 把更新结果映射为 HTTP 响应：被接受的提交返回 200；
 * 被拒绝的提交返回明确的 4xx，入口上一眼就能看出没通过，
 * 而不是看着像成功返回。
 */
final class UpdateResponses {

    private UpdateResponses() {
    }

    static ResponseEntity<UpdateResult> of(UpdateResult result) {
        if (result.accepted()) {
            return ResponseEntity.ok(result);
        }
        HttpStatus status = switch (result.reason()) {
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;   // 422 提交的值未通过校验
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;                // 409 并发版本冲突
            case UNKNOWN_ROLLBACK_VERSION -> HttpStatus.NOT_FOUND;       // 404 回滚目标不存在
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(result);
    }
}
