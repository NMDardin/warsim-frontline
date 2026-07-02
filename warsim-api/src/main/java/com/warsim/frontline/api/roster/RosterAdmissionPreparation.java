package com.warsim.frontline.api.roster;

public record RosterAdmissionPreparation(
    boolean successful,
    RosterFailure failure,
    String message,
    RosterAdmissionPlan plan
) {
    public static RosterAdmissionPreparation success(RosterAdmissionPlan plan) {
        return new RosterAdmissionPreparation(true, RosterFailure.NONE, "接纳计划已准备", plan);
    }

    public static RosterAdmissionPreparation rejected(RosterFailure failure, String message) {
        return new RosterAdmissionPreparation(false, failure, message, null);
    }
}
