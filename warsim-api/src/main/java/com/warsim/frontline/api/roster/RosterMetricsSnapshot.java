package com.warsim.frontline.api.roster;

public record RosterMetricsSnapshot(
    int activeAssignments,
    int disconnectedReservations,
    int attackersMembers,
    int defendersMembers,
    int balanceDifference,
    int squadsWithMembers,
    int fullSquads,
    int squadLeaders,
    long automaticAssignments,
    long reconnectRestores,
    long assignmentFailures,
    long capacityRejections,
    long squadSwitches,
    long squadSwitchFailures,
    long administratorMoves,
    long administratorRebalances,
    long leaderTransfers,
    long leaderAutomaticTransfers,
    long staleReservationRemovals,
    long rollbacks,
    long invariantViolations
) {
}
