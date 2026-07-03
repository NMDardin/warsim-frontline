package com.warsim.frontline.weapons.paper;

enum DamageApplicationResult {
    NOT_APPLICABLE,
    APPLIED,
    BLOCKED_BY_SPAWN_PROTECTION,
    CANCELLED_BY_EVENT,
    STALE_CONTEXT,
    NO_EFFECTIVE_DAMAGE,
    TARGET_INVALID,
    INTERNAL_FAILURE
}
