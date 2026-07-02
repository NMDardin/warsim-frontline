package com.warsim.frontline.api.database;

import java.time.Instant;
import java.util.Objects;

/**
 * Sanitized database health visible to administrators.
 */
public record DatabaseHealth(
    DatabaseState state,
    DatabaseErrorCode errorCode,
    Instant checkedAt,
    String summary
) {
    public DatabaseHealth {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(summary, "summary");
    }

    public static DatabaseHealth initial(DatabaseState state) {
        return new DatabaseHealth(state, DatabaseErrorCode.NONE, null, "尚未执行健康检查");
    }
}
