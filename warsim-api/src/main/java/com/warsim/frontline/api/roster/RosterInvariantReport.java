package com.warsim.frontline.api.roster;

import java.util.List;

public record RosterInvariantReport(boolean valid, List<String> violations) {
    public RosterInvariantReport {
        violations = List.copyOf(violations);
    }

    public static RosterInvariantReport validReport() {
        return new RosterInvariantReport(true, List.of());
    }
}
