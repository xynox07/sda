package dev.darloo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Homes.
 *  /sethome [name]   save a home where you stand (name defaults to "home"; saving an existing name moves it)
 *  /home [name]      teleport to a home (3 second countdown, cancelled if you move)
 *  /delhome <name>   delete a home        /homes   list your homes
 * Every player can have 4 homes. More homes: give the permission darloo.homes.<number> (for example darloo.homes.10),
 * or darloo.homes.unlimited. Permissions darloo.home.set and darloo.home.delete (both on for everybody by default)
 * control who may set and delete homes.
 */
public final class HomeManager implements TabExecutor {
    private final Main plugin;
    private final ArenaManager arenas;
    private final PunishManager punish;
    private final Store store;

    public HomeManager(Main plugin, ArenaManager arenas, PunishManager punish) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.punish = punish;
        this.store = new Store(plugin, "homes.yml");
    }

    // ------------------------------------------------------------ data
    private Map<String, Location> homesOf(UUID u) {
        Map<String, Location> out = new TreeMap<>();
        ConfigurationSection s = store.cfg.getConfigurationSection("players." + u);
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            Location l = s.getLocation(k);
            if (l != null) out.put(k, l);
        }
        return out;
    }

    /** 4 by default (config homes.default-limit); permission darloo.homes.<n> gives more. */
    private int limitOf(Player p) {
        if (p.hasPermission("darloo.homes.unlimited")) return Integer.MAX_VALUE;
        int best = plugin.getConfig().getInt("homes.default-limit", 4);
        for (PermissionAttachmentInfo pai : p.getEffectivePermissions()) {
            String perm = pai.getPermission();
            if (!pai.getValue() || !perm.startsWith("darloo.homes.")) continue;
            try {
                best = Math.max(best, Integer.parseInt(perm.substring("darloo.homes.".length())));
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }

    private String describe(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + " (" + (l.getWorld() == null ? "?" : l.getWorld().getName()) + ")";
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        UUID u = p.getUniqueId();
        Map<String, Location> homes = homesOf(u);
        switch (cmd.getName().toLowerCase()) {
            case "sethome" -> {
                if (!p.hasPermission("darloo.home.set")) { Util.msg(p, "&cYou do not have permission to set homes."); return true; }
                String name = args.length > 0 ? args[0].toLowerCase() : "home";
                if (!name.matches("[a-z0-9_-]{1,16}")) { Util.msg(p, "&cHome names: letters, numbers, - and _ (max 16)."); return true; }
                int limit = limitOf(p);
                if (!homes.containsKey(name) && homes.size() >= limit) {
                    Util.msg(p, "&cYou already have &e" + homes.size() + "/" + limit + " &chomes. Delete one with &e/delhome <name>&c.");
                    return true;
                }
                store.cfg.set("players." + u + "." + name, p.getLocation());
                store.save();
                int count = homes.containsKey(name) ? homes.size() : homes.size() + 1;
                Util.msg(p, "&aHome &e" + name + " &asaved! &7(" + count + "/" + (limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit)) + ")");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
            }
            case "delhome" -> {
                if (!p.hasPermission("darloo.home.delete")) { Util.msg(p, "&cYou do not have permission to delete homes."); return true; }
                String name = args.length > 0 ? args[0].toLowerCase() : (homes.size() == 1 ? homes.keySet().iterator().next() : null);
                if (name == null) { Util.msg(p, "&cUsage: /delhome <name>   Your homes: &e" + String.join("&7, &e", homes.keySet())); return true; }
                if (!homes.containsKey(name)) { Util.msg(p, "&cYou have no home called &e" + name + "&c."); return true; }
                store.cfg.set("players." + u + "." + name, null);
                store.save();
                Util.msg(p, "Home &e" + name + " &7deleted.");
            }
            case "homes" -> list(p, homes);
            default -> { // /home
                if (homes.isEmpty()) { Util.msg(p, "&cYou have no homes. Set one with &e/sethome"); return true; }
                String name;
                if (args.length > 0) name = args[0].toLowerCase();
                else if (homes.containsKey("home")) name = "home";
                else if (homes.size() == 1) name = homes.keySet().iterator().next();
                else { list(p, homes); return true; }
                Location l = homes.get(name);
                if (l == null) { Util.msg(p, "&cYou have no home called &e" + name + "&c."); list(p, homes); return true; }
                if (l.getWorld() == null) { Util.msg(p, "&cThat home's world is not loaded."); return true; }
                teleport(p, l, name);
            }
        }
        return true;
    }

    private void list(Player p, Map<String, Location> homes) {
        if (homes.isEmpty()) { Util.msg(p, "You have no homes. Set one with &e/sethome <name>"); return; }
        int limit = limitOf(p);
        Util.msg(p, "Your homes (&e" + homes.size() + "/" + (limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit)) + "&7):");
        for (Map.Entry<String, Location> e : homes.entrySet()) Util.msg(p, "&e" + e.getKey() + " &7- " + describe(e.getValue()));
    }

    // ------------------------------------------------------------ teleport with countdown
    private boolean blocked(Player p) { return arenas.isInArena(p) || punish.isJailed(p); }

    private void teleport(Player p, Location dest, String name) {
        if (blocked(p)) { Util.msg(p, "&cYou cannot use homes in the arena or in jail."); return; }
        int delay = plugin.getConfig().getInt("homes.delay-seconds", 3);
        if (delay <= 0) { go(p, dest, name); return; }
        Location start = p.getLocation();
        Util.msg(p, "&7Teleporting to &e" + name + " &7in &e" + delay + " &7seconds - don't move!");
        new BukkitRunnable() {
            int left = delay;

            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                Location now = p.getLocation();
                if (!now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 1.0) {
                    Util.msg(p, "&cTeleport cancelled because you moved.");
                    cancel();
                    return;
                }
                left--;
                if (left <= 0) { go(p, dest, name); cancel(); }
                else p.sendActionBar(Util.c("&eTeleporting in " + left + "..."));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void go(Player p, Location dest, String name) {
        if (blocked(p)) { Util.msg(p, "&cTeleport cancelled."); return; }
        p.teleport(dest);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        Util.msg(p, "&aWelcome to home &e" + name + "&a!");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && sender instanceof Player p && !cmd.getName().equalsIgnoreCase("homes")) {
            return Util.match(homesOf(p.getUniqueId()).keySet(), args[0]);
        }
        return List.of();
    }
}
