package com.warsim.frontline.api.performance;

import java.nio.file.Path;
import java.util.List;

/** Result of exporting report files. */
public record PerformanceExportResult(
    boolean successful,
    String message,
    List<Path> files
) {
    public PerformanceExportResult {
        files = List.copyOf(files);
    }
}
