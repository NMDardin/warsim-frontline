package com.warsim.frontline.admin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for extensions of the single WarSim root command.
 */
public final class WarSimCommandRegistry implements AutoCloseable {
    private final Map<String, WarSimCommandExtension> extensions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public AutoCloseable register(WarSimCommandExtension extension) {
        Objects.requireNonNull(extension, "extension");
        String name = normalize(extension.name());
        if (closed) {
            throw new IllegalStateException("Command registry is closed");
        }
        if (extensions.putIfAbsent(name, extension) != null) {
            throw new IllegalStateException("Command extension already registered: " + name);
        }
        return () -> extensions.remove(name, extension);
    }

    public Optional<WarSimCommandExtension> find(String name) {
        return Optional.ofNullable(extensions.get(normalize(name)));
    }

    public List<String> names() {
        return extensions.keySet().stream().sorted().toList();
    }

    private static String normalize(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid command extension name");
        }
        return normalized;
    }

    @Override
    public void close() {
        closed = true;
        extensions.clear();
    }
}
