package com.warsim.frontline.admin;

import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * Dynamically registered child command of the single /warsim root command.
 */
public interface WarSimCommandExtension {
    String name();

    boolean execute(CommandSender sender, String[] arguments);

    default List<String> complete(CommandSender sender, String[] arguments) {
        return List.of();
    }
}
