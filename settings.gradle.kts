pluginManagement {
    plugins {
        id("com.gradleup.shadow") version "9.4.2"
    }
}

rootProject.name = "warsim-frontline"

include(
    "warsim-api",
    "warsim-common",
    "warsim-network",
    "warsim-database",
    "warsim-resourcepack",
    "warsim-match",
    "warsim-squad",
    "warsim-classes",
    "warsim-weapons",
    "warsim-weapons-paper",
    "warsim-vehicles",
    "warsim-destruction",
    "warsim-progression",
    "warsim-cosmetics",
    "warsim-rental",
    "warsim-anticheat",
    "warsim-admin",
    "warsim-velocity"
)
