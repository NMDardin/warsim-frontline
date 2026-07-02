package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.weapon.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class WeaponConfigLoader {
    private WeaponConfigLoader() {}

    static WeaponPaperConfiguration load(JavaPlugin plugin, Logger logger) {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            File file = new File(plugin.getDataFolder(), "config.yml");
            if (!file.isFile()) {
                try (InputStream source = plugin.getResource("config.yml")) {
                    if (source == null) throw new IllegalStateException("Bundled config missing");
                    Files.copy(source, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            String root = "weapons.";
            ArrayList<WeaponDefinition> definitions = new ArrayList<>();
            ConfigurationSection section = yaml.getConfigurationSection(root + "definitions");
            if (section != null) {
                for (String id : section.getKeys(false).stream().sorted().toList()) {
                    String path = root + "definitions." + id + ".";
                    List<?> rawPoints = yaml.getList(path + "damage.points", List.of());
                    ArrayList<RangeDamagePoint> points = new ArrayList<>();
                    for (Object value : rawPoints) {
                        if (!(value instanceof java.util.Map<?, ?> map)) {
                            throw new IllegalArgumentException("Invalid damage point for " + id);
                        }
                        points.add(new RangeDamagePoint(
                            number(map.get("distance")), number(map.get("damage"))
                        ));
                    }
                    definitions.add(new WeaponDefinition(
                        new WeaponId(id),
                        yaml.getString(path + "display-name", id),
                        WeaponCategory.valueOf(yaml.getString(
                            path + "category", "RIFLE"
                        ).toUpperCase(Locale.ROOT)),
                        FireMode.valueOf(yaml.getString(
                            path + "fire-mode", "SEMI_AUTO"
                        ).toUpperCase(Locale.ROOT)),
                        yaml.getString(path + "craftengine-item-id", ""),
                        new AmmoConfiguration(
                            yaml.getInt(path + "ammo.magazine-size"),
                            yaml.getInt(path + "ammo.reserve-ammo"),
                            yaml.getLong(path + "ammo.reload-millis")
                        ),
                        yaml.getInt(path + "firing.rounds-per-minute"),
                        yaml.getDouble(path + "firing.maximum-range"),
                        new AccuracyConfiguration(
                            yaml.getDouble(path + "firing.hip-spread-degrees")
                        ),
                        new DamageConfiguration(
                            yaml.getDouble(path + "damage.head-multiplier"), points
                        )
                    ));
                }
            }
            WeaponConfiguration core = new WeaponConfiguration(
                yaml.getBoolean(root + "enabled", true),
                yaml.getBoolean(root + "behavior.friendly-fire", false),
                yaml.getBoolean(root + "behavior.allow-self-damage", false),
                yaml.getInt(root + "ballistics.maximum-candidates-per-shot", 100),
                yaml.getDouble(root + "ballistics.maximum-range", 200),
                yaml.getLong(root + "ballistics.maximum-delta-millis", 250),
                yaml.getDouble(root + "ballistics.head-height-ratio", .25),
                yaml.getDouble(root + "ballistics.epsilon", 1.0E-6),
                yaml.getInt(root + "state.reload-check-interval-ticks", 2),
                definitions
            );
            return new WeaponPaperConfiguration(
                core,
                yaml.getBoolean(root + "input.cancel-vanilla-drop-when-reloading", true),
                yaml.getBoolean(root + "input.cancel-vanilla-interaction-when-armed", true),
                null
            );
        } catch (RuntimeException | java.io.IOException exception) {
            logger.log(
                Level.SEVERE,
                "[warsim-weapons] 武器配置无效，仅独立武器插件进入FAILED。",
                exception
            );
            return new WeaponPaperConfiguration(
                WeaponConfiguration.disabled(), true, true, exception.getMessage()
            );
        }
    }

    private static double number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Damage point value is not numeric");
        }
        return number.doubleValue();
    }
}
