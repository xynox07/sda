package dev.darloo;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.*;

/**
 * DarlooPolice punishment system.
 *  /punish <player> <minutes> [reason]  - big title, inventory saved + cleared, teleport to the jail cell
 *  /unpunish <player>                   - free early
 *  /jail set | del | list               - the jail is a 5x5 cell around the spot where you type /jail set
 *
 * - The punishment timer ONLY counts while the player is online (it pauses when they leave).
 * - In the cell nobody can do anything except: open chests, drink milk, eat rotten flesh.
 * - Every 2 seconds the punished player takes 1 heart of damage. If they die they respawn in the jail.
 * - When the time is over (or /unpunish) the player gets ALL items back and returns to where they were.
 */
public final class PunishManager implements TabExecutor, Listener {
    private static final Set<Material> CHESTS = Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);

    private final Main plugin;
    private final ArenaManager arenas;
    private final Store store;
    private Location jail;                                   // centre of the 5x5 cell
    private final Map<UUID, Long> remain = new HashMap<>();  // seconds left, only ticks down while online
    private boolean selfDamage;                              // true only while WE damage a jailed player
    private int counter;

    public PunishManager(Main plugin, ArenaManager arenas) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.store = new Store(plugin, "jail.yml");
        this.jail = store.cfg.getLocation("jail");
        ConfigurationSection s = store.cfg.getConfigurationSection("players");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    long rem = s.getLong(k + ".remaining", -1L);
                    if (rem < 0) { // old format (end time) -> convert once
                        rem = Math.max(0L, (s.getLong(k + ".end", 0L) - System.currentTimeMillis()) / 1000L);
                    }
                    remain.put(UUID.fromString(k), rem);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() { flush(); }

    private boolean isJailed(UUID u) { return remain.containsKey(u); }

    public boolean isJailed(Player p) { return remain.containsKey(p.getUniqueId()); }

    /** Writes the remaining times to disk. */
    private void flush() {
        for (Map.Entry<UUID, Long> e : remain.entrySet()) store.cfg.set("players." + e.getKey() + ".remaining", e.getValue());
        store.save();
    }

    private boolean inCell(Location l) {
        return jail != null && jail.getWorld() != null && l.getWorld() != null && l.getWorld().equals(jail.getWorld())
                && Math.abs(l.getX() - jail.getX()) <= 2.5
                && Math.abs(l.getZ() - jail.getZ()) <= 2.5
                && Math.abs(l.getY() - jail.getY()) <= 6.0;
    }

    private String fmt(long sec) {
        sec = Math.max(0, sec);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("darloo.police")) { Util.msg(sender, "&cYou do not have permission."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "jail" -> jailCmd(sender, args);
            case "punish" -> punish(sender, args);
            case "unpunish" -> unpunish(sender, args);
            default -> { }
        }
        return true;
    }

    private void jailCmd(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "set" -> {
                if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return; }
                Location c = p.getLocation();
                jail = new Location(c.getWorld(), c.getBlockX() + 0.5, c.getY(), c.getBlockZ() + 0.5, c.getYaw(), c.getPitch());
                store.cfg.set("jail", jail);
                store.save();
                Util.msg(p, "&aJail cell set: a &e5x5 &aarea around you (2 blocks each side). Build walls around it, and put a "
                        + "&echest with milk buckets + rotten flesh &ainside.");
            }
            case "del", "delete", "remove" -> {
                if (jail == null) { Util.msg(sender, "&cThere is no jail set."); return; }
                jail = null;
                store.cfg.set("jail", null);
                store.save();
                Util.msg(sender, "Jail removed.");
            }
            case "list" -> {
                if (remain.isEmpty()) { Util.msg(sender, "Nobody is in jail."); return; }
                for (Map.Entry<UUID, Long> e : remain.entrySet()) {
                    String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                    Util.msg(sender, "&e" + (name == null ? e.getKey().toString() : name) + " &7- " + fmt(e.getValue())
                            + " left - " + store.cfg.getString("players." + e.getKey() + ".reason", ""));
                }
            }
            default -> Util.msg(sender, "&e/jail set &7| &e/jail del &7| &e/jail list");
        }
    }

    private void punish(CommandSender sender, String[] args) {
        if (args.length < 2) { Util.msg(sender, "&cUsage: /punish <player> <minutes> [reason]"); return; }
        if (jail == null || jail.getWorld() == null) { Util.msg(sender, "&cNo jail yet. Stand in the cell and type &e/jail set"); return; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) { Util.msg(sender, "&cPlayer must be online."); return; }
        if (isJailed(t.getUniqueId())) { Util.msg(sender, "&c" + t.getName() + " is already punished."); return; }
        if (t.hasPermission("darloo.police") && !t.equals(sender)) { Util.msg(sender, "&cYou cannot punish staff."); return; }
        int minutes;
        try {
            minutes = Integer.parseInt(args[1].toLowerCase().replace("min", "").replace("m", ""));
        } catch (NumberFormatException e) {
            Util.msg(sender, "&cMinutes must be a number, e.g. &e/punish " + t.getName() + " 10 spamming");
            return;
        }
        if (minutes < 1 || minutes > 10080) { Util.msg(sender, "&cMinutes must be between 1 and 10080."); return; }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "No reason given";

        // leave arena / close trade first so their REAL items are what we save
        if (arenas.isInArena(t)) arenas.leave(t, false);
        t.closeInventory();

        String base = "players." + t.getUniqueId();
        store.cfg.set(base + ".remaining", minutes * 60L);
        store.cfg.set(base + ".reason", reason);
        store.cfg.set(base + ".by", sender.getName());
        store.cfg.set(base + ".contents", Arrays.asList(t.getInventory().getContents()));
        store.cfg.set(base + ".loc", t.getLocation());
        store.cfg.set(base + ".gm", t.getGameMode().name());
        store.save(); // items are safe on disk BEFORE we clear anything
        remain.put(t.getUniqueId(), minutes * 60L);

        t.getInventory().clear();
        t.updateInventory();
        t.setGameMode(GameMode.SURVIVAL);
        t.teleport(jail);
        heal(t);
        t.setFoodLevel(10);   // not full, so they are able to eat rotten flesh
        t.setSaturation(0f);
        boolean meme = plugin.memes != null && plugin.memes.trigger("punish", t); // GHOP GHOP + sound first
        final int mins = minutes;
        final String why = reason;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!t.isOnline()) return;
            t.showTitle(Title.title(Util.c("&c&lYOU ARE PUNISHED"), Util.c("&7Reason: &e" + why + " &8| &7" + mins + " min"),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            t.playSound(t.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1f);
        }, meme ? 70L : 1L);
        Util.msg(t, "&cYou are punished for &e" + minutes + " minutes&c. Reason: &e" + reason
                + " &7(the time only counts while you are online)");
        Bukkit.broadcast(Util.c(Main.PREFIX + "&e" + t.getName() + " &7was punished for &e" + minutes + " min &7by &e"
                + sender.getName() + "&7. Reason: &c" + reason));
    }

    private void unpunish(CommandSender sender, String[] args) {
        if (args.length < 1) { Util.msg(sender, "&cUsage: /unpunish <player>"); return; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t != null) {
            if (!isJailed(t.getUniqueId())) { Util.msg(sender, "&c" + t.getName() + " is not punished."); return; }
            release(t, "Released by " + sender.getName());
            Util.msg(sender, "&a" + t.getName() + " was freed.");
            return;
        }
        var off = Util.findPlayer(args[0]);
        if (off == null || !isJailed(off.getUniqueId())) { Util.msg(sender, "&cThat player is not punished."); return; }
        remain.put(off.getUniqueId(), 0L);
        flush();
        Util.msg(sender, "&a" + args[0] + " is offline - they will be freed and get their items back when they join.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("darloo.police")) return List.of();
        String name = cmd.getName().toLowerCase();
        if (name.equals("jail") && args.length == 1) return Util.match(List.of("set", "del", "list"), args[0]);
        if ((name.equals("punish") || name.equals("unpunish")) && args.length == 1) return Util.match(Util.onlineNames(), args[0]);
        if (name.equals("punish") && args.length == 2) return List.of("5", "10", "30", "60");
        return List.of();
    }

    // ------------------------------------------------------------ release
    private void release(Player p, String why) {
        UUID u = p.getUniqueId();
        if (!remain.containsKey(u)) return;
        String base = "players." + u;
        ItemStack[] arr = new ItemStack[41];
        List<?> list = store.cfg.getList(base + ".contents");
        if (list != null) {
            for (int i = 0; i < Math.min(41, list.size()); i++) {
                if (list.get(i) instanceof ItemStack is) arr[i] = is;
            }
        }
        Location back = store.cfg.getLocation(base + ".loc");
        String gm = store.cfg.getString(base + ".gm", "SURVIVAL");

        p.getInventory().clear();
        p.getInventory().setContents(arr);   // ALL items come back
        p.updateInventory();
        try { p.setGameMode(GameMode.valueOf(gm)); } catch (IllegalArgumentException ignored) { }
        heal(p);
        if (back != null && back.getWorld() != null) p.teleport(back);

        remain.remove(u);
        store.cfg.set(base, null);
        store.save();
        p.showTitle(Title.title(Util.c("&a&lYOU ARE FREE"), Util.c("&7You got all your items back"),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Util.msg(p, "&aYou are free! &7(" + why + ")");
    }

    private void heal(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20.0 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
    }

    // ------------------------------------------------------------ every second
    private void tick() {
        counter++;
        int every = Math.max(1, plugin.getConfig().getInt("jail.damage-interval-seconds", 2));
        double damage = plugin.getConfig().getDouble("jail.damage-hearts", 1.0) * 2.0; // 1 heart = 2 hp
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            Long left = remain.get(u);
            if (left == null) continue;
            left = left - 1;                        // only online players are counted
            if (left <= 0) { release(p, "Time is over"); continue; }
            remain.put(u, left);

            String reason = store.cfg.getString("players." + u + ".reason", "");
            p.sendActionBar(Util.c("&c\u26D3 Punished &8| &e" + fmt(left) + " &7left &8| &7Reason: &f" + reason));

            if (jail != null && !inCell(p.getLocation())) p.teleport(jail);

            if (counter % every == 0 && !p.isDead()) {
                selfDamage = true;
                try { p.damage(damage); } finally { selfDamage = false; }
            }
        }
        if (counter % 10 == 0) flush();
    }

    // ------------------------------------------------------------ events
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Long left = remain.get(p.getUniqueId());
        if (left == null) return;
        if (left <= 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) release(p, "Time is over"); }, 5L);
        } else {
            if (jail != null && !inCell(p.getLocation())) p.teleport(jail);
            Util.msg(p, "&cYou are still punished. Time left: &e" + fmt(left));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (isJailed(e.getPlayer().getUniqueId())) flush(); // time stops while they are offline
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (jail == null || !isJailed(e.getPlayer().getUniqueId())) return;
        Location to = e.getTo();
        if (to == null || inCell(to)) return;
        if (inCell(e.getFrom())) e.setTo(e.getFrom()); else e.setTo(jail);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!isJailed(p.getUniqueId())) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.deathMessage(null);
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && p.isDead()) p.spigot().respawn(); });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!isJailed(p.getUniqueId())) return;
        if (jail != null && jail.getWorld() != null) e.setRespawnLocation(jail); // died -> back to jail
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isJailed(p.getUniqueId())) { p.setFoodLevel(10); p.setSaturation(0f); }
        }, 2L);
    }

    /** Only our own 1-heart damage is allowed on a jailed player. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isJailed(p.getUniqueId()) && !selfDamage) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isJailed(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isJailed(p.getUniqueId())) return;
        Action a = e.getAction();
        if (a == Action.PHYSICAL || a == Action.LEFT_CLICK_BLOCK) { e.setCancelled(true); return; }
        Material held = e.getItem() == null ? Material.AIR : e.getItem().getType();
        boolean consumable = held == Material.MILK_BUCKET || held == Material.ROTTEN_FLESH;
        if (a == Action.RIGHT_CLICK_BLOCK) {
            boolean chest = e.getClickedBlock() != null && CHESTS.contains(e.getClickedBlock().getType());
            if (!chest) e.setUseInteractedBlock(Event.Result.DENY);
            if (!consumable) e.setUseItemInHand(Event.Result.DENY);
        } else if (a == Action.RIGHT_CLICK_AIR && !consumable) {
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!isJailed(e.getPlayer().getUniqueId())) return;
        Material m = e.getItem().getType();
        if (m != Material.MILK_BUCKET && m != Material.ROTTEN_FLESH) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (isJailed(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isJailed(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isJailed(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /** Nobody (except staff) can change blocks inside the jail cell. */
    private boolean cellProtected(Player p, Location blockLoc) {
        if (isJailed(p.getUniqueId())) return true;
        return !p.hasPermission("darloo.police") && inCell(blockLoc.clone().add(0.5, 0, 0.5));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (cellProtected(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (cellProtected(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isJailed(p.getUniqueId()) || p.hasPermission("darloo.police")) return;
        String first = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (first.contains(":")) first = first.substring(first.indexOf(':') + 1);
        if (plugin.getConfig().getStringList("jail.allowed-commands").contains(first)) return;
        e.setCancelled(true);
        Util.msg(p, "&cYou cannot use commands while punished.");
    }
}
