package com.warsim.frontline.database;

import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern PASSWORD = Pattern.compile(
        "(?i)(password=)[^&;\\s]+"
    );

    private SecretRedactor() {
    }

    public static String redact(String value) {
        return value == null ? null : PASSWORD.matcher(value).replaceAll("$1***");
    }
}
