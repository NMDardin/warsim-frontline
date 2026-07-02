package com.warsim.frontline.api.database;

/**
 * Database failure with a stable machine-readable category.
 */
public final class DatabaseOperationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final DatabaseErrorCode code;

    public DatabaseOperationException(DatabaseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public DatabaseOperationException(DatabaseErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DatabaseErrorCode code() {
        return code;
    }
}
