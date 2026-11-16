package dev.darloo;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/** /warp <name>, /warp add <name>, /warp del <name>, /warp list */
public final class WarpManager implements TabExecutor {
    private static final Set<String> RESERVED = Set.of("add", "set", "del", "remove", "delete", "list");
    private final Store store;
    private final Map<String, Location> warps = new TreeMap<>();

    public WarpManager(Main plugin) {
        store = new Store(plugin, "warps.yml");
        ConfigurationSection s = store.cfg.getConfigurationSection("warps");
        if (s != null) for (String k : s.getKeys(false)) {
            Location l = s.getLocation(k);
            if (l != null) warps.put(k, l);
        }
    }

    private void save() {
        store.cfg.set("warps", null);
        for (Map.Entry<String, Location> e : warps.entrySet()) store.cfg.set("warps." + e.getKey(), e.getValue());
        store.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (warps.isEmpty()) { Util.msg(p, "No warps yet."); return true; }
            Util.msg(p, "Warps: &e" + String.join("&7, &e", warps.keySet()));
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add", "set" -> {
                if (!p.hasPermission("ultimate.warp.admin")) { Util.msg(p, "&cYou do not have permission."); return true; }
                if (args.length < 2) { Util.msg(p, "&cUsage: /warp add <name>"); return true; }
                String name = args[1].toLowerCase();
                if (!name.matches("[a-z0-9_-]{1,24}") || RESERVED.contains(name)) {
                    Util.msg(p, "&cInvalid name (letters, numbers, - and _)."); return true;
                }
                warps.put(name, p.getLocation());
                save();
                Util.msg(p, "Warp &e" + name + " &7saved. Players can use &e/warp " + name);
            }
            case "del", "remove", "delete" -> {
                if (!p.hasPermission("ultimate.warp.admin")) { Util.msg(p, "&cYou do not have permission."); return true; }
                if (args.length < 2 || warps.remove(args[1].toLowerCase()) == null) {
                    Util.msg(p, "&cWarp not found."); return true;
                }
                save();
                Util.msg(p, "Warp deleted.");
            }
            default -> {
                Location l = warps.get(sub);
                if (l == null || l.getWorld() == null) { Util.msg(p, "&cWarp not found. Try &e/warp list"); return true; }
                p.teleport(l);
                p.playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                p.showTitle(Title.title(Util.c("&b" + sub), Util.c("&7Warped!")));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(warps.keySet());
            if (sender.hasPermission("ultimate.warp.admin")) o.addAll(List.of("add", "del"));
            o.add("list");
            return Util.match(o, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("del") || args[0].equalsIgnoreCase("remove")))
            return Util.match(warps.keySet(), args[1]);
        return List.of();
    }
}
