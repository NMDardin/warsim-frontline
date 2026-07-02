package com.warsim.frontline.admin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WarSimCommand implements CommandExecutor, TabCompleter {
    private final CommandView view;
    private final WarSimCommandRegistry extensions;

    public WarSimCommand(CommandView view) {
        this(view, new WarSimCommandRegistry());
    }

    public WarSimCommand(CommandView view, WarSimCommandRegistry extensions) {
        this.view = Objects.requireNonNull(view, "view");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("warsim.admin.status")) {
                sender.sendMessage("§c你没有权限执行该命令。");
                return true;
            }
            view.statusLines().forEach(sender::sendMessage);
            return true;
        }
        if (args.length == 2 && "join".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家执行。");
                return true;
            }
            if (!sender.hasPermission("warsim.player.join")) {
                sender.sendMessage("§c你没有权限执行该命令。");
                return true;
            }
            view.join(player, args[1].toLowerCase(Locale.ROOT));
            return true;
        }
        if (args.length == 3
            && "redis".equalsIgnoreCase(args[0])
            && "ping".equalsIgnoreCase(args[1])) {
            if (!sender.hasPermission("warsim.admin.redis.ping")) {
                sender.sendMessage("§c你没有权限执行该命令。");
                return true;
            }
            view.redisPing(sender, args[2].toLowerCase(Locale.ROOT));
            return true;
        }
        if (args.length >= 2 && "match".equalsIgnoreCase(args[0])) {
            return handleMatch(sender, args);
        }
        if (args.length >= 2 && "team".equalsIgnoreCase(args[0])) {
            return handleTeam(sender,args);
        }
        if (args.length >= 2 && "squad".equalsIgnoreCase(args[0])) {
            return handleSquad(sender,args);
        }
        if (args.length >= 2 && "objective".equalsIgnoreCase(args[0])) {
            return handleObjective(sender, args);
        }
        if (args.length >= 2 && "tickets".equalsIgnoreCase(args[0])) {
            return handleTickets(sender, args);
        }
        if (args.length >= 1) {
            var extension = extensions.find(args[0]);
            if (extension.isPresent()) {
                return extension.get().execute(
                    sender, java.util.Arrays.copyOfRange(args, 1, args.length)
                );
            }
        }
        sender.sendMessage("§e用法：/warsim <status|join|redis|match|team|squad|objective|tickets"
            + (extensions.names().isEmpty() ? "" : "|" + String.join("|", extensions.names()))
            + ">");
        return true;
    }

    private boolean handleObjective(CommandSender sender, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        String permission = "warsim.admin.objective." + action;
        if (!permission(sender, permission)) return true;
        switch (action) {
            case "list" -> {
                if (args.length != 2) sender.sendMessage("§e用法：/warsim objective list");
                else view.objectiveList().forEach(sender::sendMessage);
            }
            case "status" -> {
                if (args.length > 3) sender.sendMessage("§e用法：/warsim objective status [id]");
                else view.objectiveStatus(args.length == 3 ? args[2] : null)
                    .forEach(sender::sendMessage);
            }
            case "lock" -> {
                if (args.length != 3) sender.sendMessage("§e用法：/warsim objective lock <id>");
                else view.objectiveLock(sender, args[2]);
            }
            case "unlock" -> {
                if (args.length != 3) sender.sendMessage("§e用法：/warsim objective unlock <id>");
                else view.objectiveUnlock(sender, args[2]);
            }
            case "reset" -> {
                if (args.length > 3) sender.sendMessage("§e用法：/warsim objective reset [id]");
                else view.objectiveReset(sender, args.length == 3 ? args[2] : null);
            }
            case "setowner" -> {
                if (args.length != 4) {
                    sender.sendMessage("§e用法：/warsim objective setowner <id> <neutral|attackers|defenders>");
                } else view.objectiveSetOwner(sender, args[2], args[3]);
            }
            default -> sender.sendMessage(
                "§e用法：/warsim objective <status|list|lock|unlock|reset|setowner>"
            );
        }
        return true;
    }

    private boolean handleTickets(CommandSender sender, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        String permission = "status".equals(action)
            ? "warsim.admin.tickets.status" : "warsim.admin.tickets.modify";
        if (!permission(sender, permission)) return true;
        if ("status".equals(action)) {
            if (args.length != 2) sender.sendMessage("§e用法：/warsim tickets status");
            else view.ticketsStatus().forEach(sender::sendMessage);
            return true;
        }
        if (!List.of("set", "add", "take").contains(action) || args.length != 4) {
            sender.sendMessage("§e用法：/warsim tickets <set|add|take> <attackers|defenders> <数量>");
            return true;
        }
        view.ticketsModify(sender, action, args[2], args[3]);
        return true;
    }

    private boolean handleTeam(CommandSender sender,String[] args) {
        String action=args[1].toLowerCase(Locale.ROOT);
        String permission="warsim.admin.team."+action;
        if(!sender.hasPermission(permission)){
            sender.sendMessage("§c你没有权限执行该命令。");
            return true;
        }
        switch(action){
            case "status" -> view.teamStatus(sender);
            case "list" -> view.teamList(sender);
            case "player" -> {
                if(args.length!=3) sender.sendMessage("§e用法：/warsim team player <玩家>");
                else view.teamPlayer(sender,args[2]);
            }
            case "move" -> {
                if(args.length<4 || args.length>5
                    || (args.length==5 && !"force".equalsIgnoreCase(args[4]))) {
                    sender.sendMessage("§e用法：/warsim team move <玩家> <attackers|defenders> [force]");
                } else {
                    view.teamMove(sender,args[2],args[3],args.length==5);
                }
            }
            case "rebalance" -> {
                if(args.length!=3) sender.sendMessage("§e用法：/warsim team rebalance <玩家>");
                else view.teamRebalance(sender,args[2]);
            }
            default -> sender.sendMessage("§e用法：/warsim team <status|list|player|move|rebalance>");
        }
        return true;
    }

    private boolean handleSquad(CommandSender sender,String[] args) {
        String action=args[1].toLowerCase(Locale.ROOT);
        switch(action){
            case "status" -> {
                if(!permission(sender,"warsim.player.squad.status")) return true;
                if(sender instanceof Player player) view.squadStatus(player);
                else sender.sendMessage("§c该命令只能由玩家执行。");
            }
            case "list" -> {
                if(!permission(sender,"warsim.player.squad.list")) return true;
                view.squadList(sender);
            }
            case "join" -> {
                if(!permission(sender,"warsim.player.squad.join")) return true;
                if(!(sender instanceof Player player)) sender.sendMessage("§c该命令只能由玩家执行。");
                else if(args.length!=3) sender.sendMessage("§e用法：/warsim squad join <小队ID>");
                else view.squadJoin(player,args[2]);
            }
            case "leave" -> {
                if(!permission(sender,"warsim.player.squad.leave")) return true;
                if(!(sender instanceof Player player)) sender.sendMessage("§c该命令只能由玩家执行。");
                else view.squadLeave(player);
            }
            case "leader" -> {
                boolean administrator=sender.hasPermission("warsim.admin.squad.leader");
                if(!administrator && !permission(sender,"warsim.player.squad.leader")) return true;
                if(args.length!=3) sender.sendMessage("§e用法：/warsim squad leader <玩家>");
                else view.squadLeader(sender,args[2],administrator);
            }
            case "inspect" -> {
                if(!permission(sender,"warsim.admin.squad.inspect")) return true;
                if(args.length!=3) sender.sendMessage("§e用法：/warsim squad inspect <小队ID>");
                else view.squadInspect(sender,args[2]);
            }
            case "move" -> {
                if(!permission(sender,"warsim.admin.squad.move")) return true;
                if(args.length!=4) sender.sendMessage("§e用法：/warsim squad move <玩家> <小队ID>");
                else view.squadMove(sender,args[2],args[3]);
            }
            case "remove" -> {
                if(!permission(sender,"warsim.admin.squad.remove")) return true;
                if(args.length!=3) sender.sendMessage("§e用法：/warsim squad remove <玩家>");
                else view.squadRemove(sender,args[2]);
            }
            default -> sender.sendMessage("§e用法：/warsim squad <status|list|join|leave|leader|inspect|move|remove>");
        }
        return true;
    }

    private static boolean permission(CommandSender sender,String permission){
        if(sender.hasPermission(permission)) return true;
        sender.sendMessage("§c你没有权限执行该命令。");
        return false;
    }

    private boolean handleMatch(CommandSender sender, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        String permission = "warsim.admin.match." + action;
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("§c你没有权限执行该命令。");
            return true;
        }
        switch (action) {
            case "status" -> view.matchStatusLines().forEach(sender::sendMessage);
            case "start" -> {
                if (args.length > 3
                    || (args.length == 3 && !"force".equalsIgnoreCase(args[2]))) {
                    sender.sendMessage("§e用法：/warsim match start [force]");
                } else {
                    view.matchStart(sender, args.length == 3);
                }
            }
            case "end" -> view.matchEnd(
                sender,
                args.length <= 2 ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
            );
            case "reset" -> {
                if (args.length != 2) {
                    sender.sendMessage("§e用法：/warsim match reset");
                } else {
                    view.matchReset(sender);
                }
            }
            case "recover" -> {
                if (args.length != 2) {
                    sender.sendMessage("§e用法：/warsim match recover");
                } else {
                    view.matchRecover(sender);
                }
            }
            default -> sender.sendMessage("§e用法：/warsim match <status|start|end|reset|recover>");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String alias,
        @NotNull String[] args
    ) {
        if (args.length == 1) {
            return java.util.stream.Stream.concat(
                List.of("status", "join", "redis", "match", "team", "squad",
                    "objective", "tickets").stream(),
                extensions.names().stream()
            )
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
        }
        if (args.length >= 2) {
            var extension = extensions.find(args[0]);
            if (extension.isPresent()) {
                return extension.get().complete(
                    sender, java.util.Arrays.copyOfRange(args, 1, args.length)
                );
            }
        }
        if (args.length == 2 && "join".equalsIgnoreCase(args[0])) {
            return view.allowedTargets().stream().filter(value -> value.startsWith(args[1])).toList();
        }
        if (args.length == 2 && "redis".equalsIgnoreCase(args[0])) {
            return "ping".startsWith(args[1].toLowerCase(Locale.ROOT))
                ? List.of("ping") : List.of();
        }
        if (args.length == 2 && "match".equalsIgnoreCase(args[0])) {
            return List.of("status", "start", "end", "reset", "recover").stream()
                .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .toList();
        }
        if(args.length==2 && "team".equalsIgnoreCase(args[0])){
            return List.of("status","list","player","move","rebalance").stream()
                .filter(value->value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if(args.length==2 && "squad".equalsIgnoreCase(args[0])){
            return List.of("status","list","join","leave","leader","inspect","move","remove").stream()
                .filter(value->value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && "objective".equalsIgnoreCase(args[0])) {
            return List.of("status", "list", "lock", "unlock", "reset", "setowner").stream()
                .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && "tickets".equalsIgnoreCase(args[0])) {
            return List.of("status", "set", "add", "take").stream()
                .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && "tickets".equalsIgnoreCase(args[0])
            && !"status".equalsIgnoreCase(args[1])) {
            return List.of("attackers", "defenders").stream()
                .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3
            && "match".equalsIgnoreCase(args[0])
            && "start".equalsIgnoreCase(args[1])) {
            return "force".startsWith(args[2].toLowerCase(Locale.ROOT))
                ? List.of("force") : List.of();
        }
        return List.of();
    }

    public interface CommandView {
        List<String> statusLines();

        List<String> allowedTargets();

        void join(Player player, String targetNodeId);

        void redisPing(CommandSender sender, String targetNodeId);

        List<String> matchStatusLines();

        void matchStart(CommandSender sender, boolean force);

        void matchEnd(CommandSender sender, String reason);

        void matchReset(CommandSender sender);

        void matchRecover(CommandSender sender);

        void teamStatus(CommandSender sender);
        void teamList(CommandSender sender);
        void teamPlayer(CommandSender sender,String playerName);
        void teamMove(CommandSender sender,String playerName,String team,boolean force);
        void teamRebalance(CommandSender sender,String playerName);
        void squadStatus(Player player);
        void squadList(CommandSender sender);
        void squadJoin(Player player,String squadId);
        void squadLeave(Player player);
        void squadLeader(CommandSender sender,String playerName,boolean administrator);
        void squadInspect(CommandSender sender,String squadId);
        void squadMove(CommandSender sender,String playerName,String squadId);
        void squadRemove(CommandSender sender,String playerName);
        List<String> objectiveList();
        List<String> objectiveStatus(String objectiveId);
        void objectiveLock(CommandSender sender, String objectiveId);
        void objectiveUnlock(CommandSender sender, String objectiveId);
        void objectiveReset(CommandSender sender, String objectiveId);
        void objectiveSetOwner(CommandSender sender, String objectiveId, String owner);
        List<String> ticketsStatus();
        void ticketsModify(
            CommandSender sender, String operation, String team, String amount
        );
    }
}
