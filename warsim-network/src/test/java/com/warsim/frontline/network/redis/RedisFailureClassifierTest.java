package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class RedisFailureClassifierTest {
    @Test void classifiesAuthenticationFailureWithoutEchoingCredentials() {
        assertEquals(
            "Redis认证失败",
            RedisFailureClassifier.safeSummary(
                new IllegalStateException("WRONGPASS invalid username-password pair secret-value")
            )
        );
    }

    @Test void classifiesDnsFailure() {
        assertEquals(
            "Redis地址解析失败",
            RedisFailureClassifier.safeSummary(new UnknownHostException("redis.internal"))
        );
    }

    @Test void classifiesTlsFailure() {
        assertEquals(
            "Redis TLS连接失败",
            RedisFailureClassifier.safeSummary(new SSLException("certificate rejected"))
        );
    }

    @Test void fallsBackToConnectionFailure() {
        assertEquals(
            "Redis连接不可用",
            RedisFailureClassifier.safeSummary(new IllegalStateException("connection refused"))
        );
    }
}
