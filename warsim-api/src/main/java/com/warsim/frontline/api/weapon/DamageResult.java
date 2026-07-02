package com.warsim.frontline.api.weapon;

public record DamageResult(boolean allowed, double damage, ShotOutcome outcome) {
}
