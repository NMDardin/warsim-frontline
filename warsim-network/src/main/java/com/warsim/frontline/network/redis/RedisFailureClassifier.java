package com.warsim.frontline.network.redis;

import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

final class RedisFailureClassifier {
    private RedisFailureClassifier() {
    }

    static String safeSummary(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnknownHostException) {
                return "Redis地址解析失败";
            }
            if (current instanceof SSLException) {
                return "Redis TLS连接失败";
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toUpperCase(Locale.ROOT);
                if (normalized.contains("WRONGPASS")
                    || normalized.contains("NOAUTH")
                    || normalized.contains("AUTHENTICATION")) {
                    return "Redis认证失败";
                }
            }
            current = current.getCause();
        }
        return "Redis连接不可用";
    }
}
