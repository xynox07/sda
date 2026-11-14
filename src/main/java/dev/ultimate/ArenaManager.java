package dev.ultimate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PvP arena.
 *  /arena join [arena] [kit]   - your normal inventory is saved, you get the arena kit
 *  /arena leave                - arena kit is cleared, your own inventory + position come back
 *  /arena create|setspawn|delete <name>, /arena list   (admin)
 * Your real inventory is also written to disk, so even a crash cannot lose it.
 */
public final class ArenaManager implements TabExecutor, Listener {
    private final Main plugin;
    private final KitManager kits;
    private final Store store;
    private final File dataDir;
    private final Map<String, Location> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> inArena = new HashMap<>();
    private final Map<UUID, String> kitOf = new HashMap<>();

    public ArenaManager(Main plugin, KitManager kits) {
        this.plugin = plugin;
        this.kits = kits;
        this.store = new Store(plugin, "arenas.yml");
        this.dataDir = new File(plugin.getDataFolder(), "arena-saves");
        dataDir.mkdirs();
        ConfigurationSection s = store.cfg.getConfigurationSection("arenas");
        if (s != null) for (String k : s.getKeys(false)) {
            Location l = s.getLocation(k);
            if (l != null) arenas.put(k, l);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean isInArena(Player p) { return inArena.containsKey(p.getUniqueId()); }

    public void shutdown() {
        for (UUID u : new ArrayList<>(inArena.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) leave(p, false);
        }
    }

    private void saveArenas() {
        store.cfg.set("arenas", null);
        for (Map.Entry<String, Location> e : arenas.entrySet()) store.cfg.set("arenas." + e.getKey(), e.getValue());
        store.save();
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        boolean admin = p.hasPermission("ultimate.arena.admin");
        switch (sub) {
            case "join" -> {
                if (isInArena(p)) { Util.msg(p, "&cYou are already in the arena. &e/arena leave"); return true; }
                if (arenas.isEmpty()) { Util.msg(p, "&cNo arena has been created yet."); return true; }
                String name = args.length >= 2 ? args[1].toLowerCase() : arenas.keySet().iterator().next();
                Location spawn = arenas.get(name);
                if (spawn == null || spawn.getWorld() == null) { Util.msg(p, "&cArena not found. &e/arena list"); return true; }
                String kit = args.length >= 3 ? args[2].toLowerCase()
                        : plugin.getConfig().getString("arena.default-kit", "sword").toLowerCase();
                if (!kits.exists(kit)) {
                    if (kits.names().isEmpty()) { Util.msg(p, "&cThere are no kits."); return true; }
                    kit = kits.names().iterator().next();
                }
                join(p, name, kit, spawn);
            }
            case "leave" -> {
                if (!isInArena(p)) { Util.msg(p, "&cYou are not in the arena."); return true; }
                leave(p, true);
            }
            case "list" -> Util.msg(p, arenas.isEmpty() ? "No arenas yet." : "Arenas: &e" + String.join("&7, &e", arenas.keySet()));
            case "create", "setspawn" -> {
                if (!admin) { Util.msg(p, "&cYou do not have permission."); return true; }
                if (args.length < 2 || !args[1].matches("[a-zA-Z0-9_-]{1,24}")) { Util.msg(p, "&cUsage: /arena " + sub + " <name>"); return true; }
                arenas.put(args[1].toLowerCase(), p.getLocation());
                saveArenas();
                Util.msg(p, "Arena &e" + args[1].toLowerCase() + " &7spawn set to your position.");
            }
            case "delete" -> {
                if (!admin) { Util.msg(p, "&cYou do not have permission."); return true; }
                if (args.length < 2 || arenas.remove(args[1].toLowerCase()) == null) { Util.msg(p, "&cArena not found."); return true; }
                saveArenas();
                Util.msg(p, "Arena deleted.");
            }
            default -> {
                Util.msg(p, "&e/arena join [arena] [kit] &7- play with an arena kit");
                Util.msg(p, "&e/arena leave &7- leave, arena kit is removed, your items come back");
                Util.msg(p, "&e/kit <name> &7- change kit inside the arena");
                if (admin) Util.msg(p, "&e/arena create <name> &7| &e/arena delete <name> &7| &e/arena list");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("join", "leave", "list"));
            if (sender.hasPermission("ultimate.arena.admin")) o.addAll(List.of("create", "delete"));
            return Util.match(o, args[0]);
        }
        if (args.length == 2 && List.of("join", "delete").contains(args[0].toLowerCase())) return Util.match(arenas.keySet(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("join")) return Util.match(kits.names(), args[2]);
        return List.of();
    }

    // ------------------------------------------------------------ join / leave
    private File saveFile(UUID u) { return new File(dataDir, u + ".yml"); }

    private void join(Player p, String arena, String kit, Location spawn) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("contents", Arrays.asList(p.getInventory().getContents()));
        y.set("loc", p.getLocation());
        y.set("gm", p.getGameMode().name());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        try {
            y.save(saveFile(p.getUniqueId()));
        } catch (IOException ex) {
            Util.msg(p, "&cCould not save your inventory, so you cannot join. Tell an admin.");
            plugin.getLogger().warning("Arena save failed: " + ex.getMessage());
            return;
        }
        inArena.put(p.getUniqueId(), arena);
        kitOf.put(p.getUniqueId(), kit);
        p.setGameMode(GameMode.SURVIVAL);
        p.teleport(spawn);
        giveKit(p, kit);
        Util.msg(p, "You joined the arena with kit &e" + kit + "&7. Leave with &e/arena leave");
    }

    public void setKit(Player p, String kit) {
        if (!isInArena(p)) return;
        kitOf.put(p.getUniqueId(), kit);
        giveKit(p, kit);
        Util.msg(p, "Kit changed to &e" + kit);
    }

    private void giveKit(Player p, String kit) {
        kits.apply(p, kit);
        heal(p);
    }

    private void heal(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20.0 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
    }

    /** Clears the arena kit and gives the player's own stuff back. */
    public void leave(Player p, boolean notify) {
        inArena.remove(p.getUniqueId());
        kitOf.remove(p.getUniqueId());
        p.getInventory().clear(); // arena kit gone
        restore(p);
        if (notify) Util.msg(p, "You left the arena. Your inventory is back.");
    }

    private void restore(Player p) {
        File f = saveFile(p.getUniqueId());
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ItemStack[] arr = new ItemStack[41];
        List<?> list = y.getList("contents");
        if (list != null) for (int i = 0; i < Math.min(41, list.size()); i++) {
            if (list.get(i) instanceof ItemStack is) arr[i] = is;
        }
        p.getInventory().setContents(arr);
        heal(p);
        try {
            p.setGameMode(GameMode.valueOf(y.getString("gm", "SURVIVAL")));
        } catch (IllegalArgumentException ignored) { }
        double health = y.getDouble("health", 20.0);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(Math.max(1.0, Math.min(health, max == null ? 20.0 : max.getValue())));
        p.setFoodLevel(y.getInt("food", 20));
        Location back = y.getLocation("loc");
        if (back != null && back.getWorld() != null) p.teleport(back);
        p.updateInventory();
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------ events
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // server crashed / player was kicked while in arena -> give their things back
        Player p = e.getPlayer();
        if (!inArena.containsKey(p.getUniqueId()) && saveFile(p.getUniqueId()).exists()) {
            p.getInventory().clear();
            restore(p);
            Util.msg(p, "Your inventory was restored after you left the arena.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (isInArena(e.getPlayer())) leave(e.getPlayer(), false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!isInArena(p)) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && p.isDead()) p.spigot().respawn(); });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        String arena = inArena.get(p.getUniqueId());
        if (arena == null) return;
        Location spawn = arenas.get(arena);
        if (spawn != null && spawn.getWorld() != null) e.setRespawnLocation(spawn);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String kit = kitOf.get(p.getUniqueId());
            if (p.isOnline() && kit != null && inArena.containsKey(p.getUniqueId())) giveKit(p, kit);
        }, 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isInArena(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (isInArena(e.getPlayer()) && !e.getPlayer().hasPermission("ultimate.arena.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (isInArena(e.getPlayer()) && !e.getPlayer().hasPermission("ultimate.arena.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isInArena(p) || p.hasPermission("ultimate.arena.admin")) return;
        String first = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (first.contains(":")) first = first.substring(first.indexOf(':') + 1);
        if (plugin.getConfig().getStringList("arena.allowed-commands").contains(first)) return;
        e.setCancelled(true);
        Util.msg(p, "&cYou cannot use that command in the arena. Use &e/arena leave &cfirst.");
    }
}
