package dev.darloo;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PvP arenas - one arena per game mode (kit).
 *  /arena create <name>        (admin) stand where players should spawn.
 *                              Name an arena like a kit (e.g. /arena create crystal) and everybody who picks
 *                              that kit is teleported to THAT arena. Kits without an own arena use the default arena.
 *  /arena join [kit|arena]     join (default arena + default kit when nothing is given)
 *  /arena modes                menu to pick a game mode
 *  /arena leave                arena kit is cleared, your own inventory + position come back
 *  /kit <name>                 inside the arena: switch mode (you are moved to that mode's arena)
 * Build kits (config arena.build-kits, default: crystal) may place blocks and break ONLY blocks placed in the arena.
 * Those blocks are removed when the player leaves or dies.
 * Your real inventory is written to disk, so even a crash cannot lose it.
 */
public final class ArenaManager implements TabExecutor, Listener {
    static final class ModeHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Main plugin;
    private final KitManager kits;
    private final Store store;
    private final File dataDir;
    private final Map<String, Location> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> inArena = new HashMap<>();   // player -> arena name
    private final Map<UUID, String> kitOf = new HashMap<>();     // player -> kit name
    private final Map<Block, UUID> placedBlocks = new HashMap<>();
    private final Map<UUID, List<Entity>> placedEntities = new HashMap<>();

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

    // ------------------------------------------------------------ mode -> arena / kit helpers
    private String defaultArena() {
        String cfg = plugin.getConfig().getString("arena.default-arena", "");
        if (cfg != null && arenas.containsKey(cfg.toLowerCase())) return cfg.toLowerCase();
        return arenas.isEmpty() ? null : arenas.keySet().iterator().next();
    }

    private String defaultKit() {
        String k = plugin.getConfig().getString("arena.default-kit", "sword").toLowerCase();
        if (kits.exists(k)) return k;
        return kits.names().isEmpty() ? null : kits.names().iterator().next();
    }

    /** The arena that belongs to a kit: an arena with the same name, otherwise the default arena. */
    private String arenaForKit(String kit) { return arenas.containsKey(kit) ? kit : defaultArena(); }

    private boolean isBuildKit(String kit) {
        return kit != null && plugin.getConfig().getStringList("arena.build-kits").contains(kit.toLowerCase());
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
                String a1 = args.length >= 2 ? args[1].toLowerCase() : null;
                String a2 = args.length >= 3 ? args[2].toLowerCase() : null;
                String kit = null, arena = null;
                if (a1 != null) {
                    if (kits.exists(a1)) kit = a1;
                    else if (arenas.containsKey(a1)) arena = a1;
                    else { Util.msg(p, "&cUnknown kit or arena. Try &e/arena modes"); return true; }
                }
                if (a2 != null && kits.exists(a2)) kit = a2;
                if (kit == null) kit = arena != null && kits.exists(arena) ? arena : defaultKit();
                if (kit == null) { Util.msg(p, "&cThere are no kits."); return true; }
                if (kits.isGiveKit(kit)) { Util.msg(p, "&e" + kit + " &cis not an arena mode. Use &e/kit " + kit + " &coutside the arena."); return true; }
                if (arena == null) arena = arenaForKit(kit);
                joinWith(p, arena, kit);
            }
            case "modes", "menu" -> openModes(p);
            case "leave" -> {
                if (!isInArena(p)) { Util.msg(p, "&cYou are not in the arena."); return true; }
                leave(p, true);
            }
            case "list" -> {
                if (arenas.isEmpty()) { Util.msg(p, "No arenas yet."); return true; }
                for (String a : arenas.keySet()) {
                    Util.msg(p, "&e" + a + (kits.exists(a) ? " &7- arena for kit &e" + a : " &7- normal arena"));
                }
            }
            case "create", "setspawn" -> {
                if (!admin) { Util.msg(p, "&cYou do not have permission."); return true; }
                if (args.length < 2 || !args[1].matches("[a-zA-Z0-9_-]{1,24}")) { Util.msg(p, "&cUsage: /arena " + sub + " <name>"); return true; }
                String name = args[1].toLowerCase();
                arenas.put(name, p.getLocation());
                saveArenas();
                Util.msg(p, "Arena &e" + name + " &7spawn set to your position.");
                if (kits.exists(name)) Util.msg(p, "&aPlayers who pick kit &e" + name + " &aare now teleported here.");
                else Util.msg(p, "&7(No kit is called &e" + name + "&7, so this is a normal arena. Name it like a kit to link them.)");
            }
            case "delete" -> {
                if (!admin) { Util.msg(p, "&cYou do not have permission."); return true; }
                if (args.length < 2 || arenas.remove(args[1].toLowerCase()) == null) { Util.msg(p, "&cArena not found."); return true; }
                saveArenas();
                Util.msg(p, "Arena deleted.");
            }
            default -> {
                Util.msg(p, "&e/arena join [kit] &7- play (default arena + kit, or the arena of that kit)");
                Util.msg(p, "&e/arena modes &7- pick a game mode from a menu");
                Util.msg(p, "&e/arena leave &7- leave, arena kit is removed, your items come back");
                Util.msg(p, "&e/kit <name> &7- change mode inside the arena");
                if (admin) Util.msg(p, "&e/arena create <name> &7(e.g. crystal) | &e/arena delete <name> | &e/arena list");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("join", "leave", "modes", "list"));
            if (sender.hasPermission("ultimate.arena.admin")) o.addAll(List.of("create", "delete"));
            return Util.match(o, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            List<String> o = new ArrayList<>();
            for (String k : kits.names()) if (!kits.isGiveKit(k)) o.add(k);
            o.addAll(arenas.keySet());
            return Util.match(o, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return Util.match(arenas.keySet(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) return Util.match(kits.names(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("join")) return Util.match(kits.names(), args[2]);
        return List.of();
    }

    // ------------------------------------------------------------ mode menu
    private void openModes(Player p) {
        if (kits.names().isEmpty()) { Util.msg(p, "&cThere are no kits."); return; }
        ModeHolder h = new ModeHolder();
        int size = Math.min(54, Math.max(9, ((kits.names().size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(h, size, Util.c("&8Arena - pick a game mode"));
        h.inv = inv;
        int i = 0;
        for (String k : kits.names()) {
            if (i >= size) break;
            if (kits.isGiveKit(k)) continue;
            String arena = arenaForKit(k);
            ItemStack it = Util.item(kits.icon(k), "&e" + k,
                    "&7Arena: &f" + (arena == null ? "none yet" : arena),
                    isBuildKit(k) ? "&7Building: &aallowed (your blocks only)" : "&7Building: &cno",
                    "", "&aClick to play");
            Util.tag(it, "kit", k);
            inv.setItem(i++, it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onModeClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ModeHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        String kit = Util.getTag(e.getCurrentItem(), "kit");
        if (kit == null) return;
        p.closeInventory();
        if (isInArena(p)) { setKit(p, kit); return; }
        String arena = arenaForKit(kit);
        if (arena == null) { Util.msg(p, "&cNo arena has been created yet."); return; }
        joinWith(p, arena, kit);
    }

    @EventHandler
    public void onModeDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ModeHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ join / leave
    private File saveFile(UUID u) { return new File(dataDir, u + ".yml"); }

    private void joinWith(Player p, String arena, String kit) {
        Location spawn = arena == null ? null : arenas.get(arena);
        if (spawn == null || spawn.getWorld() == null) { Util.msg(p, "&cArena not found or its world is not loaded."); return; }
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
        Util.msg(p, "You joined arena &e" + arena + " &7with kit &e" + kit + "&7. Leave with &e/arena leave");
    }

    /** Switch mode inside the arena: new kit and, if that mode has its own arena, teleport there. */
    public void setKit(Player p, String kit) {
        if (!isInArena(p)) return;
        UUID u = p.getUniqueId();
        cleanup(u);
        kitOf.put(u, kit);
        String target = arenaForKit(kit);
        Location spawn = target == null ? null : arenas.get(target);
        if (target != null && spawn != null && spawn.getWorld() != null && !target.equals(inArena.get(u))) {
            inArena.put(u, target);
            p.teleport(spawn);
        }
        giveKit(p, kit);
        Util.msg(p, "Mode changed to &e" + kit + " &7(arena &e" + inArena.get(u) + "&7)");
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
        UUID u = p.getUniqueId();
        cleanup(u);
        inArena.remove(u);
        kitOf.remove(u);
        p.getInventory().clear(); // arena kit gone
        restore(p);
        if (notify) Util.msg(p, "You left the arena. Your inventory is back.");
    }

    /** Removes the blocks and crystals this player placed in the arena. */
    private void cleanup(UUID u) {
        Iterator<Map.Entry<Block, UUID>> it = placedBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, UUID> en = it.next();
            if (en.getValue().equals(u)) {
                en.getKey().setType(Material.AIR);
                it.remove();
            }
        }
        List<Entity> list = placedEntities.remove(u);
        if (list != null) for (Entity en : list) if (en.isValid()) en.remove();
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
        cleanup(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && p.isDead()) p.spigot().respawn(); });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        String arena = inArena.get(p.getUniqueId());
        if (arena == null) return;
        Location spawn = arenas.get(arena);
        if (spawn != null && spawn.getWorld() != null) e.setRespawnLocation(spawn); // back to THIS mode's arena
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String kit = kitOf.get(p.getUniqueId());
            if (p.isOnline() && kit != null && inArena.containsKey(p.getUniqueId())) giveKit(p, kit);
        }, 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isInArena(e.getPlayer())) e.setCancelled(true);
    }

    private boolean builder(Player p) { return isInArena(p) && isBuildKit(kitOf.get(p.getUniqueId())); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!isInArena(p) || p.hasPermission("ultimate.arena.admin")) return;
        // build kits may only break blocks that were placed during a fight
        if (builder(p) && placedBlocks.containsKey(e.getBlock())) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!isInArena(p) || p.hasPermission("ultimate.arena.admin")) return;
        if (!builder(p)) e.setCancelled(true);
    }

    /** Remember placed blocks (only if nobody cancelled the placement). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlaced(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (builder(p)) placedBlocks.put(e.getBlockPlaced(), p.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || !isInArena(p) || p.hasPermission("ultimate.arena.admin")) return;
        if (!builder(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityPlaced(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null && builder(p)) placedEntities.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(e.getEntity());
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
