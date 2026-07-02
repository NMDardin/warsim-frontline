package com.warsim.frontline.api.weapon;

public record AmmoConfiguration(int magazineSize, int reserveAmmo, long reloadMillis) {
    public AmmoConfiguration {
        if (magazineSize < 1 || magazineSize > 200
            || reserveAmmo < 0 || reserveAmmo > 1000
            || reloadMillis < 100 || reloadMillis > 15000) {
            throw new IllegalArgumentException("Invalid ammunition configuration");
        }
    }
}
