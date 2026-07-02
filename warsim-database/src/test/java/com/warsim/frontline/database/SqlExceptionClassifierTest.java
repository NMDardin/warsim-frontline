package com.warsim.frontline.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlExceptionClassifierTest {
    @Test
    void classifiesAuthenticationFailure() {
        assertEquals(
            DatabaseErrorCode.AUTHENTICATION_ERROR,
            SqlExceptionClassifier.classify(new SQLException("bad password", "28P01"))
        );
    }

    @Test
    void classifiesConnectionAndTimeoutFailures() {
        assertEquals(
            DatabaseErrorCode.CONNECTION_ERROR,
            SqlExceptionClassifier.classify(new SQLException("down", "08006"))
        );
        assertEquals(
            DatabaseErrorCode.TIMEOUT,
            SqlExceptionClassifier.classify(new SQLException("timeout", "57014"))
        );
    }

    @Test
    void defaultsToQueryErrorForSqlFailures() {
        assertEquals(
            DatabaseErrorCode.QUERY_ERROR,
            SqlExceptionClassifier.classify(new SQLException("constraint", "23505"))
        );
    }
}
