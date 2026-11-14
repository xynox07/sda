package dev.ultimate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Kits are stored as 41 slots: 0-35 inventory, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand.
 *  /kit <name>          use a kit (only inside the arena)
 *  /kit list
 *  /kit create <name>   (owner) copy your current inventory into a new kit
 *  /kit edit <name>     (owner) open the editor, kit saves when you close it
 *  /kit delete <name>   (owner)
 */
public final class KitManager implements TabExecutor, Listener {
    private static final Set<String> RESERVED = Set.of("list", "create", "edit", "delete");

    static final class Holder implements InventoryHolder {
        final String kit;
        Inventory inv;

        Holder(String kit) { this.kit = kit; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Store store;
    private ArenaManager arenas;
    private final Map<String, ItemStack[]> kits = new LinkedHashMap<>();

    public KitManager(Main plugin) {
        store = new Store(plugin, "kits.yml");
        ConfigurationSection root = store.cfg.getConfigurationSection("kits");
        if (root != null) {
            for (String k : root.getKeys(false)) {
                List<?> list = store.cfg.getList("kits." + k);
                if (list == null) continue;
                ItemStack[] arr = new ItemStack[41];
                for (int i = 0; i < Math.min(41, list.size()); i++) {
                    if (list.get(i) instanceof ItemStack is) arr[i] = is;
                }
                kits.put(k, arr);
            }
        }
        if (kits.isEmpty()) { defaults(); save(); }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void setArenas(ArenaManager a) { this.arenas = a; }

    public boolean exists(String name) { return kits.containsKey(name.toLowerCase()); }

    public Set<String> names() { return kits.keySet(); }

    /** Clears the player's inventory and gives the kit. */
    public boolean apply(Player p, String name) {
        ItemStack[] src = kits.get(name.toLowerCase());
        if (src == null) return false;
        ItemStack[] copy = new ItemStack[41];
        for (int i = 0; i < 41; i++) copy[i] = src[i] == null ? null : src[i].clone();
        p.getInventory().clear();
        p.getInventory().setContents(copy);
        p.updateInventory();
        return true;
    }

    private void save() {
        store.cfg.set("kits", null);
        for (Map.Entry<String, ItemStack[]> e : kits.entrySet()) store.cfg.set("kits." + e.getKey(), Arrays.asList(e.getValue()));
        store.save();
    }

    private void defaults() {
        ItemStack[] sword = new ItemStack[41];
        sword[0] = new ItemStack(Material.DIAMOND_SWORD);
        sword[1] = new ItemStack(Material.BOW);
        sword[2] = new ItemStack(Material.ARROW, 32);
        sword[3] = new ItemStack(Material.GOLDEN_APPLE, 8);
        diamondArmor(sword);
        sword[40] = new ItemStack(Material.SHIELD);
        kits.put("sword", sword);

        ItemStack[] mace = new ItemStack[41];
        mace[0] = new ItemStack(Material.MACE);
        mace[1] = new ItemStack(Material.WIND_CHARGE, 32);
        mace[2] = new ItemStack(Material.GOLDEN_APPLE, 8);
        mace[3] = new ItemStack(Material.COOKED_BEEF, 16);
        diamondArmor(mace);
        kits.put("mace", mace);

        ItemStack[] ufc = new ItemStack[41]; // fists only
        ufc[0] = new ItemStack(Material.GOLDEN_APPLE, 5);
        ufc[1] = new ItemStack(Material.COOKED_BEEF, 16);
        kits.put("ufc", ufc);
    }

    private void diamondArmor(ItemStack[] a) {
        a[36] = new ItemStack(Material.DIAMOND_BOOTS);
        a[37] = new ItemStack(Material.DIAMOND_LEGGINGS);
        a[38] = new ItemStack(Material.DIAMOND_CHESTPLATE);
        a[39] = new ItemStack(Material.DIAMOND_HELMET);
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            Util.msg(p, "Kits: &e" + String.join("&7, &e", kits.keySet()) + " &8(use &e/kit <name> &8inside the arena)");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create", "edit", "delete" -> {
                if (!p.hasPermission("ultimate.owner")) { Util.msg(p, "&cOnly the server owner can do this."); return true; }
                if (args.length < 2) { Util.msg(p, "&cUsage: /kit " + sub + " <name>"); return true; }
                String name = args[1].toLowerCase();
                if (sub.equals("create")) {
                    if (!name.matches("[a-z0-9_-]{1,24}") || RESERVED.contains(name)) { Util.msg(p, "&cInvalid kit name."); return true; }
                    if (kits.containsKey(name)) { Util.msg(p, "&cThat kit already exists. Use &e/kit edit " + name); return true; }
                    ItemStack[] arr = new ItemStack[41];
                    ItemStack[] cur = p.getInventory().getContents();
                    for (int i = 0; i < Math.min(41, cur.length); i++) arr[i] = cur[i] == null ? null : cur[i].clone();
                    kits.put(name, arr);
                    save();
                    Util.msg(p, "Kit &e" + name + " &7created from your inventory. Fine-tune it with &e/kit edit " + name);
                } else if (!kits.containsKey(name)) {
                    Util.msg(p, "&cKit not found.");
                } else if (sub.equals("delete")) {
                    kits.remove(name);
                    save();
                    Util.msg(p, "Kit deleted.");
                } else {
                    openEditor(p, name);
                }
            }
            default -> {
                if (!kits.containsKey(sub)) { Util.msg(p, "&cKit not found. Try &e/kit list"); return true; }
                if (arenas == null || !arenas.isInArena(p)) { Util.msg(p, "&cKits can only be used inside the arena (&e/arena join&c)."); return true; }
                arenas.setKit(p, sub);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(kits.keySet());
            o.add("list");
            if (sender.hasPermission("ultimate.owner")) o.addAll(List.of("create", "edit", "delete"));
            return Util.match(o, args[0]);
        }
        if (args.length == 2 && List.of("edit", "delete").contains(args[0].toLowerCase())) return Util.match(kits.keySet(), args[1]);
        return List.of();
    }

    // ------------------------------------------------------------ editor GUI
    private static int guiToInv(int g) {
        if (g >= 0 && g <= 26) return g + 9;   // main inventory rows
        if (g >= 27 && g <= 35) return g - 27; // hotbar
        return switch (g) { case 45 -> 39; case 46 -> 38; case 47 -> 37; case 48 -> 36; case 49 -> 40; default -> -1; };
    }

    private void openEditor(Player p, String name) {
        Holder h = new Holder(name);
        Inventory inv = Bukkit.createInventory(h, 54, Util.c("&8Kit editor: &0" + name));
        h.inv = inv;
        ItemStack[] kit = kits.get(name);
        for (int g = 0; g < 54; g++) {
            int idx = guiToInv(g);
            if (idx >= 0) inv.setItem(g, kit[idx] == null ? null : kit[idx].clone());
            else inv.setItem(g, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        }
        inv.setItem(50, Util.item(Material.PAPER, "&eKit layout",
                "&7Top 3 rows: inventory", "&7Row 4: hotbar", "&7Bottom left: helmet, chest, legs, boots, offhand",
                "", "&aClose this window to SAVE"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        if (raw >= 0 && raw < 54 && guiToInv(raw) < 0) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) if (raw < 54 && guiToInv(raw) < 0) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        if (!kits.containsKey(h.kit)) return;
        ItemStack[] arr = new ItemStack[41];
        for (int g = 0; g < 54; g++) {
            int idx = guiToInv(g);
            if (idx < 0) continue;
            ItemStack it = e.getInventory().getItem(g);
            arr[idx] = it == null || it.getType() == Material.AIR ? null : it.clone();
        }
        kits.put(h.kit, arr);
        save();
        Util.msg(e.getPlayer(), "Kit &e" + h.kit + " &7saved.");
    }
}
