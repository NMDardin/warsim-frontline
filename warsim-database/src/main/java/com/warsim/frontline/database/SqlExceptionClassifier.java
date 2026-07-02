package com.warsim.frontline.database;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import java.sql.SQLException;

public final class SqlExceptionClassifier {
    private SqlExceptionClassifier() {
    }

    public static DatabaseErrorCode classify(SQLException exception) {
        String state = exception.getSQLState();
        if (state == null) {
            return DatabaseErrorCode.UNKNOWN;
        }
        if (state.startsWith("28")) {
            return DatabaseErrorCode.AUTHENTICATION_ERROR;
        }
        if (state.startsWith("08")) {
            return DatabaseErrorCode.CONNECTION_ERROR;
        }
        if ("57014".equals(state) || "55P03".equals(state)) {
            return DatabaseErrorCode.TIMEOUT;
        }
        return DatabaseErrorCode.QUERY_ERROR;
    }
}
