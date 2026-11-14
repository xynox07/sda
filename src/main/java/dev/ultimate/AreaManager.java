package dev.ultimate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
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
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Protected areas.
 *  /area axe            -> gold axe (left click = pos 1, right click = pos 2, covers full height)
 *  /area create <name>
 *  /area <name> edit    -> GUI with flags (fight, build, break, explode, interact, mobs)
 */
public final class AreaManager implements TabExecutor, Listener {
    public static final List<String> FLAGS = List.of("pvp", "build", "break", "explode", "interact", "mobs");
    private static final Map<String, Boolean> DEFAULTS = Map.of(
            "pvp", false, "build", false, "break", false, "explode", false, "interact", true, "mobs", true);
    private static final Map<String, String> LABEL = Map.of(
            "pvp", "Fight (PvP)", "build", "Place blocks", "break", "Break blocks",
            "explode", "Explosions", "interact", "Doors / chests / buttons", "mobs", "Mob spawning");
    private static final Map<String, Material> ICON = Map.of(
            "pvp", Material.DIAMOND_SWORD, "build", Material.GRASS_BLOCK, "break", Material.IRON_PICKAXE,
            "explode", Material.TNT, "interact", Material.OAK_DOOR, "mobs", Material.ZOMBIE_HEAD);
    private static final Set<String> RESERVED = Set.of("axe", "wand", "create", "delete", "list", "info");
    private static final int[] FLAG_SLOTS = {10, 11, 12, 14, 15, 16};

    static final class Area {
        final String name;
        Region region;
        final Map<String, Boolean> flags = new HashMap<>();

        Area(String name, Region region) { this.name = name; this.region = region; }

        boolean get(String f) { return flags.getOrDefault(f, DEFAULTS.get(f)); }
    }

    static final class Holder implements InventoryHolder {
        final String area;
        Inventory inv;

        Holder(String area) { this.area = area; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Main plugin;
    private final Store store;
    private final Map<String, Area> areas = new LinkedHashMap<>();
    private final Map<UUID, Selection> sel = new HashMap<>();

    public AreaManager(Main plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "areas.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------ data
    private void load() {
        ConfigurationSection root = store.cfg.getConfigurationSection("areas");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null || s.getConfigurationSection("region") == null) continue;
            Area a = new Area(name, Region.load(s.getConfigurationSection("region")));
            ConfigurationSection f = s.getConfigurationSection("flags");
            if (f != null) for (String k : f.getKeys(false)) a.flags.put(k, f.getBoolean(k));
            areas.put(name, a);
        }
    }

    private void save() {
        store.cfg.set("areas", null);
        for (Area a : areas.values()) {
            ConfigurationSection s = store.cfg.createSection("areas." + a.name);
            a.region.save(s.createSection("region"));
            for (String f : FLAGS) s.set("flags." + f, a.get(f));
        }
        store.save();
    }

    private Area at(Location l) {
        for (Area a : areas.values()) if (a.region.contains(l)) return a;
        return null;
    }

    public boolean overlapsAny(Region r) {
        for (Area a : areas.values()) if (a.region.overlaps(r)) return true;
        return false;
    }

    private boolean allowed(Location l, String flag, Player p) {
        Area a = at(l);
        if (a == null) return true;
        if (p != null && p.hasPermission("ultimate.area.bypass")) return true;
        return a.get(flag);
    }

    private void deny(Player p) { p.sendActionBar(Util.c("&cThis area is protected.")); }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        if (!p.hasPermission("ultimate.area.admin")) { Util.msg(p, "&cYou do not have permission."); return true; }
        if (args.length == 0) { help(p); return true; }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "axe", "wand" -> {
                ItemStack axe = Util.item(Material.GOLDEN_AXE, "&6Area Wand",
                        "&7Left click a block: &fposition 1", "&7Right click a block: &fposition 2",
                        "&7Area covers the full height (up & down)");
                Util.tag(axe, "tool", "area");
                p.getInventory().addItem(axe);
                Util.msg(p, "You got the &6Area Wand&7. Select 2 corners, then &e/area create <name>");
            }
            case "create" -> {
                if (args.length < 2) { Util.msg(p, "&cUsage: /area create <name>"); return true; }
                String name = args[1].toLowerCase();
                if (!name.matches("[a-z0-9_-]{1,24}") || RESERVED.contains(name)) {
                    Util.msg(p, "&cInvalid name (use letters, numbers, - and _)."); return true;
                }
                if (areas.containsKey(name)) { Util.msg(p, "&cAn area with that name already exists."); return true; }
                Selection s = sel.get(p.getUniqueId());
                Region r = s == null ? null : s.region();
                if (r == null) { Util.msg(p, "&cSelect position 1 and 2 with the gold axe first (same world)."); return true; }
                Area a = new Area(name, r);
                areas.put(name, a);
                save();
                sel.remove(p.getUniqueId());
                Util.msg(p, "Area &e" + name + " &7created (" + r.size() + " blocks, full height). Now use &e/area " + name + " edit");
            }
            case "delete" -> {
                if (args.length < 2 || areas.remove(args[1].toLowerCase()) == null) {
                    Util.msg(p, "&cArea not found."); return true;
                }
                save();
                Util.msg(p, "Area deleted.");
            }
            case "list" -> {
                if (areas.isEmpty()) { Util.msg(p, "No areas yet."); return true; }
                Util.msg(p, "Areas: &e" + String.join("&7, &e", areas.keySet()));
            }
            case "info" -> {
                Area a = at(p.getLocation());
                if (a == null) { Util.msg(p, "You are not inside an area."); return true; }
                Util.msg(p, "You are in area &e" + a.name + "&7: " + a.region.describe());
            }
            default -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("edit")) {
                    Area a = areas.get(sub);
                    if (a == null) { Util.msg(p, "&cArea not found."); return true; }
                    openEditor(p, a);
                } else help(p);
            }
        }
        return true;
    }

    private void help(Player p) {
        Util.msg(p, "&e/area axe &7- get the gold axe");
        Util.msg(p, "&e/area create <name> &7- create from your selection");
        Util.msg(p, "&e/area <name> edit &7- open the flag menu");
        Util.msg(p, "&e/area delete <name> &7| &e/area list &7| &e/area info");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("axe", "create", "delete", "list", "info"));
            o.addAll(areas.keySet());
            return Util.match(o, args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("delete")) return Util.match(areas.keySet(), args[1]);
            if (areas.containsKey(args[0].toLowerCase())) return Util.match(List.of("edit"), args[1]);
        }
        return List.of();
    }

    // ------------------------------------------------------------ GUI
    private void openEditor(Player p, Area a) {
        Holder h = new Holder(a.name);
        Inventory inv = Bukkit.createInventory(h, 27, Util.c("&8Area: &0" + a.name));
        h.inv = inv;
        render(a, inv);
        p.openInventory(inv);
    }

    private void render(Area a, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 0; i < FLAGS.size(); i++) {
            String f = FLAGS.get(i);
            boolean on = a.get(f);
            ItemStack it = Util.item(ICON.get(f), (on ? "&a" : "&c") + LABEL.get(f),
                    "&7Allowed: " + (on ? "&aYES" : "&cNO"), "", "&eClick to toggle");
            Util.tag(it, "flag", f);
            inv.setItem(FLAG_SLOTS[i], it);
        }
        inv.setItem(22, Util.item(Material.BARRIER, "&cClose"));
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getSlot() == 22) { p.closeInventory(); return; }
        String flag = Util.getTag(e.getCurrentItem(), "flag");
        Area a = areas.get(h.area);
        if (flag == null || a == null) return;
        a.flags.put(flag, !a.get(flag));
        save();
        render(a, e.getView().getTopInventory());
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ selection wand
    @EventHandler(priority = EventPriority.LOW)
    public void onWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!"area".equals(Util.getTag(e.getItem(), "tool"))) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("ultimate.area.admin")) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Selection s = sel.computeIfAbsent(p.getUniqueId(), k -> new Selection());
        Location l = e.getClickedBlock().getLocation();
        if (a == Action.LEFT_CLICK_BLOCK) {
            s.p1 = l;
            Util.msg(p, "Position 1 set: &e" + l.getBlockX() + ", " + l.getBlockZ() + " &7(full height)");
        } else {
            s.p2 = l;
            Util.msg(p, "Position 2 set: &e" + l.getBlockX() + ", " + l.getBlockZ() + " &7(full height)");
        }
        Region r = s.region();
        if (r != null) Util.msg(p, "Selection size: &e" + r.width() + " x " + r.depth() + " &7- type &e/area create <name>");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { sel.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------ protection
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (!allowed(e.getBlock().getLocation(), "break", e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (!allowed(e.getBlock().getLocation(), "build", e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!allowed(e.getBlock().getLocation(), "build", e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!allowed(e.getBlock().getLocation(), "break", e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || Util.isTool(e.getItem())) return;
        Block b = e.getClickedBlock();
        if (!allowed(b.getLocation(), "interact", e.getPlayer())) {
            e.setUseInteractedBlock(Event.Result.DENY);
            deny(e.getPlayer());
        }
    }

    private Player shooter(Entity d) {
        if (d instanceof Player pl) return pl;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player sh) return sh;
        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attacker = shooter(e.getDamager());
        if (attacker == null) return;
        Entity victim = e.getEntity();
        if (victim instanceof Player vp) {
            if (vp != attacker && !allowed(vp.getLocation(), "pvp", null)) {
                e.setCancelled(true);
                attacker.sendActionBar(Util.c("&cFighting is disabled here."));
            }
        } else if (victim instanceof Hanging || victim instanceof ArmorStand) {
            if (!allowed(victim.getLocation(), "break", attacker)) { e.setCancelled(true); deny(attacker); }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> !allowed(b.getLocation(), "explode", null));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> !allowed(b.getLocation(), "explode", null));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if ((r == CreatureSpawnEvent.SpawnReason.NATURAL || r == CreatureSpawnEvent.SpawnReason.SPAWNER)
                && !allowed(e.getLocation(), "mobs", null)) {
            e.setCancelled(true);
        }
    }
}
