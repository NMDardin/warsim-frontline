package com.warsim.frontline.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {
    @Test
    void removesPasswordsFromJdbcText() {
        String redacted = SecretRedactor.redact(
            "jdbc:postgresql://localhost/frontline?user=alice&password=topsecret&ssl=true"
        );
        assertFalse(redacted.contains("topsecret"));
        assertTrue(redacted.contains("password=***"));
    }
}
