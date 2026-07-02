package com.warsim.frontline.network.redis;

import java.net.URI;

public final class RedisUriSanitizer {
    private RedisUriSanitizer() {
    }

    public static String sanitize(String value) {
        try {
            URI uri = URI.create(value);
            return new URI(
                uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null
            ).toString();
        } catch (RuntimeException | java.net.URISyntaxException exception) {
            return "<invalid-redis-uri>";
        }
    }
}
