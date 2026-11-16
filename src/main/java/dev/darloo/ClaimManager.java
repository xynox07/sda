package dev.darloo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Land claims.
 *  - every new player gets 777 claim blocks (config: claims.start-blocks)
 *  - +2 claim blocks for every minute online (config: claims.blocks-per-minute)
 *  - golden shovel: left click = corner 1, right click = corner 2
 *  - claim size (width x depth) is paid with claim blocks; deleting a claim gives them back
 */
public final class ClaimManager implements TabExecutor, Listener {

    static final class Claim {
        final UUID id;
        UUID owner;
        Region region;
        final Set<UUID> trusted = new HashSet<>();

        Claim(UUID id) { this.id = id; }
    }

    private final Main plugin;
    private final AreaManager areas;
    private final Store claimStore;
    private final Store pointStore;
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, Selection> sel = new HashMap<>();
    private final int startBlocks, perMinute, minSize;

    public ClaimManager(Main plugin, AreaManager areas) {
        this.plugin = plugin;
        this.areas = areas;
        this.claimStore = new Store(plugin, "claims.yml");
        this.pointStore = new Store(plugin, "claim-points.yml");
        this.startBlocks = plugin.getConfig().getInt("claims.start-blocks", 777);
        this.perMinute = plugin.getConfig().getInt("claims.blocks-per-minute", 2);
        this.minSize = plugin.getConfig().getInt("claims.min-size", 5);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) setTotal(p.getUniqueId(), total(p.getUniqueId()) + perMinute);
            pointStore.save();
        }, 1200L, 1200L);
        // claim shovel: day 1, then every 3 Minecraft days (checked every 10 seconds)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) giveToolIfDue(p);
        }, 100L, 200L);
    }

    // ------------------------------------------------------------ claim shovel
    private ItemStack makeTool() {
        ItemStack shovel = Util.item(Material.GOLDEN_SHOVEL, "&6Claim Shovel",
                "&7Left click a block: &fcorner 1", "&7Right click a block: &fcorner 2",
                "&7Then type &e/claim create");
        return Util.tag(shovel, "tool", "claim");
    }

    private boolean hasTool(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if ("claim".equals(Util.getTag(it, "tool"))) return true;
        }
        return false;
    }

    /** Minecraft day cycle: cycle 0 = day 1-3, cycle 1 = day 4-6, ... a new cycle = a new shovel. */
    private int currentCycle() {
        World w = Bukkit.getWorlds().get(0);
        return (int) (w.getFullTime() / 24000L / 3L);
    }

    private void giveToolIfDue(Player p) {
        if (!p.isOnline()) return;
        String key = "tool-cycle." + p.getUniqueId();
        int cycle = currentCycle();
        if (pointStore.cfg.getInt(key, -1) >= cycle) return;
        pointStore.cfg.set(key, cycle);
        pointStore.save();
        if (hasTool(p)) return;
        Util.give(p, makeTool());
        Util.msg(p, "You received a &6Claim Shovel&7! You get a new one every 3 Minecraft days if you lose it.");
    }

    // ------------------------------------------------------------ data
    private void load() {
        ConfigurationSection root = claimStore.cfg.getConfigurationSection("claims");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null || s.getConfigurationSection("region") == null || s.getString("owner") == null) continue;
                Claim c = new Claim(UUID.fromString(key));
                c.owner = UUID.fromString(s.getString("owner"));
                c.region = Region.load(s.getConfigurationSection("region"));
                for (String t : s.getStringList("trusted")) c.trusted.add(UUID.fromString(t));
                claims.put(c.id, c);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save() {
        claimStore.cfg.set("claims", null);
        for (Claim c : claims.values()) {
            ConfigurationSection s = claimStore.cfg.createSection("claims." + c.id);
            s.set("owner", c.owner.toString());
            c.region.save(s.createSection("region"));
            List<String> t = new ArrayList<>();
            for (UUID u : c.trusted) t.add(u.toString());
            s.set("trusted", t);
        }
        claimStore.save();
        pointStore.save();
    }

    private int total(UUID u) { return pointStore.cfg.getInt("players." + u, startBlocks); }
    private void setTotal(UUID u, int v) { pointStore.cfg.set("players." + u, Math.max(0, v)); }

    private int used(UUID u) {
        long sum = 0;
        for (Claim c : claims.values()) if (c.owner.equals(u)) sum += c.region.size();
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    private Claim at(Location l) {
        for (Claim c : claims.values()) if (c.region.contains(l)) return c;
        return null;
    }

    private String nameOf(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? u.toString().substring(0, 8) : op.getName();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> giveToolIfDue(joined), 60L);
        UUID u = joined.getUniqueId();
        if (!pointStore.cfg.contains("players." + u)) {
            setTotal(u, startBlocks);
            pointStore.save();
            Util.msg(e.getPlayer(), "Welcome! You got &e" + startBlocks + " &7claim blocks.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { sel.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();

        // /claim points ... works from console too
        if (sub.equals("points")) { points(sender, args); return true; }

        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        switch (sub) {
            case "tool", "shovel" -> {
                if (hasTool(p)) { Util.msg(p, "You already have a &6Claim Shovel&7 in your inventory."); return true; }
                Util.give(p, makeTool());
                Util.msg(p, "You got the &6Claim Shovel&7. Claim blocks left: &e" + (total(p.getUniqueId()) - used(p.getUniqueId())));
            }
            case "create" -> create(p);
            case "delete" -> {
                Claim c = at(p.getLocation());
                if (c == null) { Util.msg(p, "&cStand inside the claim you want to delete."); return true; }
                if (!c.owner.equals(p.getUniqueId()) && !p.hasPermission("ultimate.claim.admin")) {
                    Util.msg(p, "&cThis is not your claim."); return true;
                }
                claims.remove(c.id);
                save();
                Util.msg(p, "Claim deleted. You got &e" + c.region.size() + " &7blocks back.");
            }
            case "trust", "untrust" -> {
                if (args.length < 2) { Util.msg(p, "&cUsage: /claim " + sub + " <player>"); return true; }
                Claim c = at(p.getLocation());
                if (c == null || (!c.owner.equals(p.getUniqueId()) && !p.hasPermission("ultimate.claim.admin"))) {
                    Util.msg(p, "&cStand inside one of YOUR claims."); return true;
                }
                OfflinePlayer t = Util.findPlayer(args[1]);
                if (t == null) { Util.msg(p, "&cPlayer not found."); return true; }
                if (sub.equals("trust")) c.trusted.add(t.getUniqueId()); else c.trusted.remove(t.getUniqueId());
                save();
                Util.msg(p, "&e" + args[1] + " &7is " + (sub.equals("trust") ? "now trusted" : "no longer trusted") + " in this claim.");
            }
            case "list" -> {
                int n = 0;
                for (Claim c : claims.values()) if (c.owner.equals(p.getUniqueId())) {
                    n++;
                    Util.msg(p, "&e#" + n + " &7" + c.region.describe() + " &8(" + c.region.size() + " blocks)");
                }
                if (n == 0) Util.msg(p, "You have no claims. Use &e/claim tool");
            }
            case "info" -> {
                Claim c = at(p.getLocation());
                if (c == null) { Util.msg(p, "This land is not claimed."); return true; }
                List<String> names = new ArrayList<>();
                for (UUID u : c.trusted) names.add(nameOf(u));
                Util.msg(p, "Owner: &e" + nameOf(c.owner) + " &7| Size: &e" + c.region.size()
                        + " &7| Trusted: &e" + (names.isEmpty() ? "nobody" : String.join(", ", names)));
            }
            default -> {
                Util.msg(p, "&e/claim tool &7- get the golden shovel");
                Util.msg(p, "&e/claim create &7| &e/claim delete &7| &e/claim list &7| &e/claim info");
                Util.msg(p, "&e/claim trust <player> &7| &e/claim untrust <player>");
                Util.msg(p, "&e/claim points &7- see your claim blocks");
                if (p.hasPermission("ultimate.claim.admin")) Util.msg(p, "&e/claim points <amount> <player> &7- give blocks (admin)");
            }
        }
        return true;
    }

    private void create(Player p) {
        Selection s = sel.get(p.getUniqueId());
        Region r = s == null ? null : s.region();
        if (r == null) { Util.msg(p, "&cUse the golden shovel (&e/claim tool&c) to pick 2 corners first."); return; }
        if (r.width() < minSize || r.depth() < minSize) {
            Util.msg(p, "&cClaim too small. Minimum is &e" + minSize + " x " + minSize + "&c."); return;
        }
        int left = total(p.getUniqueId()) - used(p.getUniqueId());
        if (r.size() > left) {
            Util.msg(p, "&cNot enough claim blocks. Needed: &e" + r.size() + "&c, you have: &e" + left); return;
        }
        for (Claim c : claims.values()) if (c.region.overlaps(r)) {
            Util.msg(p, "&cThis overlaps another claim."); showBorder(p, c.region); return;
        }
        if (areas.overlapsAny(r)) { Util.msg(p, "&cThis overlaps a protected area."); return; }
        Claim c = new Claim(UUID.randomUUID());
        c.owner = p.getUniqueId();
        c.region = r;
        claims.put(c.id, c);
        save();
        sel.remove(p.getUniqueId());
        showBorder(p, r);
        Util.msg(p, "Claim created! Size &e" + r.size() + " &7blocks. Left: &e" + (left - r.size()));
    }

    private void points(CommandSender sender, String[] args) {
        // /claim points                    -> own points
        // /claim points <player>           -> see another player's points (admin)
        // /claim points <amount> <player>  -> give points (admin)  (<player> <amount> also works)
        boolean admin = sender.hasPermission("ultimate.claim.admin");
        if (args.length <= 1) {
            if (!(sender instanceof Player p)) { Util.msg(sender, "&cUsage: /claim points <amount> <player>"); return; }
            showPoints(sender, p.getUniqueId(), p.getName());
            return;
        }
        if (!admin) { Util.msg(sender, "&cYou do not have permission."); return; }
        if (args.length == 2) {
            OfflinePlayer t = Util.findPlayer(args[1]);
            if (t == null) { Util.msg(sender, "&cPlayer not found."); return; }
            showPoints(sender, t.getUniqueId(), args[1]);
            return;
        }
        Integer amount = parse(args[1]);
        String name = args[2];
        if (amount == null) { amount = parse(args[2]); name = args[1]; }
        if (amount == null) { Util.msg(sender, "&cUsage: /claim points <amount> <player>"); return; }
        OfflinePlayer t = Util.findPlayer(name);
        if (t == null) { Util.msg(sender, "&cPlayer not found."); return; }
        setTotal(t.getUniqueId(), total(t.getUniqueId()) + amount);
        pointStore.save();
        Util.msg(sender, "Gave &e" + amount + " &7claim blocks to &e" + name + "&7. Total now: &e" + total(t.getUniqueId()));
        Player online = t.getPlayer();
        if (online != null) Util.msg(online, "You received &e" + amount + " &7claim blocks!");
    }

    private void showPoints(CommandSender to, UUID u, String name) {
        int t = total(u), us = used(u);
        Util.msg(to, "&e" + name + "&7: total &e" + t + " &7| used &e" + us + " &7| left &e" + (t - us)
                + " &8(+" + perMinute + " per minute online)");
    }

    private Integer parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.match(List.of("tool", "create", "delete", "trust", "untrust", "list", "info", "points"), args[0]);
        if (args.length == 2 && List.of("trust", "untrust", "points").contains(args[0].toLowerCase())) return Util.match(Util.onlineNames(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("points")) return Util.match(Util.onlineNames(), args[2]);
        return List.of();
    }

    // ------------------------------------------------------------ shovel
    @EventHandler(priority = EventPriority.LOW)
    public void onShovel(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!"claim".equals(Util.getTag(e.getItem(), "tool"))) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Selection s = sel.computeIfAbsent(p.getUniqueId(), k -> new Selection());
        Location l = e.getClickedBlock().getLocation();
        if (a == Action.LEFT_CLICK_BLOCK) s.p1 = l; else s.p2 = l;
        Util.msg(p, (a == Action.LEFT_CLICK_BLOCK ? "Corner 1" : "Corner 2") + " set: &e" + l.getBlockX() + ", " + l.getBlockZ());
        Region r = s.region();
        if (r != null) {
            int left = total(p.getUniqueId()) - used(p.getUniqueId());
            Util.msg(p, "Selection: &e" + r.width() + " x " + r.depth() + " = " + r.size() + " &7blocks (you have &e" + left + "&7). Type &e/claim create");
            showBorder(p, r);
        }
    }

    private void showBorder(Player p, Region r) {
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                World w = Bukkit.getWorld(r.world);
                if (!p.isOnline() || n++ >= 12 || w == null || !p.getWorld().equals(w)) { cancel(); return; }
                double y = p.getLocation().getY() + 1.2;
                int step = Math.max(2, (2 * (r.width() + r.depth())) / 250);
                for (int x = r.minX; x <= r.maxX; x += step) { dot(p, x, y, r.minZ); dot(p, x, y, r.maxZ); }
                for (int z = r.minZ; z <= r.maxZ; z += step) { dot(p, r.minX, y, z); dot(p, r.maxX, y, z); }
                dot(p, r.maxX, y, r.maxZ);
                dot(p, r.maxX, y, r.minZ);
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void dot(Player p, double x, double y, double z) {
        p.spawnParticle(Particle.END_ROD, x + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------ protection
    private boolean blocked(Player p, Location l) {
        Claim c = at(l);
        if (c == null) return false;
        if (c.owner.equals(p.getUniqueId()) || c.trusted.contains(p.getUniqueId())) return false;
        if (p.hasPermission("ultimate.claim.admin")) return false;
        p.sendActionBar(Util.c("&cThis land is claimed by &e" + nameOf(c.owner) + "&c."));
        return true;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || Util.isTool(e.getItem())) return;
        Material m = e.getClickedBlock().getType();
        if (Tag.BUTTONS.isTagged(m)) return; // buttons stay public
        if (blocked(e.getPlayer(), e.getClickedBlock().getLocation())) e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if ((en instanceof ItemFrame || en instanceof ArmorStand) && blocked(e.getPlayer(), en.getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = shooter(e.getRemover());
        if (p != null && blocked(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        if (!(v instanceof Hanging) && !(v instanceof ArmorStand)) return;
        Player p = shooter(e.getDamager());
        if (p != null && blocked(p, v.getLocation())) e.setCancelled(true);
    }

    private Player shooter(Entity d) {
        if (d instanceof Player pl) return pl;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player sh) return sh;
        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> at(b.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> at(b.getLocation()) != null);
    }
}
