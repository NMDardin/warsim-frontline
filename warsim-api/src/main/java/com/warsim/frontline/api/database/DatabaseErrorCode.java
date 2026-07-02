package com.warsim.frontline.api.database;

/**
 * Stable categories for database failures.
 */
public enum DatabaseErrorCode {
    NONE,
    CONFIGURATION_ERROR,
    CONNECTION_ERROR,
    AUTHENTICATION_ERROR,
    MIGRATION_ERROR,
    TIMEOUT,
    QUEUE_FULL,
    QUERY_ERROR,
    SHUTTING_DOWN,
    UNKNOWN
}
