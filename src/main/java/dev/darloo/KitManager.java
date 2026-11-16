package dev.darloo;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Main plugin;
    private final Store store;
    private ArenaManager arenas;
    private final Map<String, ItemStack[]> kits = new LinkedHashMap<>();

    public KitManager(Main plugin) {
        this.plugin = plugin;
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
        if (!store.cfg.getBoolean("seeded-crystal", false)) { // add the crystal kit once to older installs
            if (!kits.containsKey("crystal")) kits.put("crystal", crystalKit());
            store.cfg.set("seeded-crystal", true);
            save();
        }
        if (!store.cfg.getBoolean("seeded-starter", false)) { // starter kit for everyone, added once
            if (!kits.containsKey("starter")) kits.put("starter", starterKit());
            store.cfg.set("seeded-starter", true);
            save();
        }
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
        kits.put("crystal", crystalKit());
    }

    private ItemStack[] crystalKit() {
        ItemStack[] c = new ItemStack[41];
        c[0] = new ItemStack(Material.DIAMOND_SWORD);
        c[1] = new ItemStack(Material.END_CRYSTAL, 64);
        c[2] = new ItemStack(Material.OBSIDIAN, 64);
        c[3] = new ItemStack(Material.GOLDEN_APPLE, 16);
        c[4] = new ItemStack(Material.ENDER_PEARL, 16);
        c[5] = new ItemStack(Material.TOTEM_OF_UNDYING);
        diamondArmor(c);
        c[40] = new ItemStack(Material.TOTEM_OF_UNDYING);
        return c;
    }

    /** First item of the kit - used as the icon in the arena mode menu. */
    public Material icon(String name) {
        ItemStack[] a = kits.get(name.toLowerCase());
        if (a != null) for (ItemStack s : a) if (s != null && s.getType() != Material.AIR) return s.getType();
        return Material.PAPER;
    }

    private void diamondArmor(ItemStack[] a) {
        a[36] = new ItemStack(Material.DIAMOND_BOOTS);
        a[37] = new ItemStack(Material.DIAMOND_LEGGINGS);
        a[38] = new ItemStack(Material.DIAMOND_CHESTPLATE);
        a[39] = new ItemStack(Material.DIAMOND_HELMET);
    }

    private ItemStack[] starterKit() {
        ItemStack[] s = new ItemStack[41];
        s[0] = new ItemStack(Material.IRON_SWORD);
        s[1] = new ItemStack(Material.OAK_LOG, 16);
        s[2] = new ItemStack(Material.BREAD, 16);
        s[36] = new ItemStack(Material.IRON_BOOTS);
        s[37] = new ItemStack(Material.IRON_LEGGINGS);
        s[38] = new ItemStack(Material.IRON_CHESTPLATE);
        s[39] = new ItemStack(Material.IRON_HELMET);
        return s;
    }

    /** Kits that anybody can claim with /kit <name> outside the arena (they are NOT arena modes). */
    public boolean isGiveKit(String name) {
        return plugin.getConfig().getStringList("kits.give-kits").contains(name.toLowerCase());
    }

    /** Gives a "give kit" (e.g. starter). Items are added to the inventory; armor is worn if the slot is free. */
    private void claim(Player p, String name) {
        if (arenas != null && arenas.isInArena(p)) { Util.msg(p, "&cYou cannot claim this kit inside the arena."); return; }
        int hours = plugin.getConfig().getInt("starter.cooldown-hours", -1);
        String key = "claims." + name + "." + p.getUniqueId();
        long last = store.cfg.getLong(key, 0L);
        long now = System.currentTimeMillis();
        if (last > 0 && !p.hasPermission("ultimate.owner")) {
            if (hours < 0) { Util.msg(p, "&cYou already claimed the &e" + name + " &ckit. It can only be claimed once."); return; }
            if (hours > 0) {
                long wait = last + hours * 3_600_000L - now;
                if (wait > 0) {
                    long min = wait / 60_000L + 1;
                    Util.msg(p, "&cYou can claim this kit again in &e" + (min / 60) + "h " + (min % 60) + "m&c.");
                    return;
                }
            }
        }
        ItemStack[] src = kits.get(name);
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 41; i++) {
            ItemStack it = src[i];
            if (it == null || it.getType() == Material.AIR) continue;
            ItemStack c = it.clone();
            if (i >= 36) { // armor / offhand: wear it only when that slot is free
                ItemStack cur = switch (i) {
                    case 36 -> inv.getBoots();
                    case 37 -> inv.getLeggings();
                    case 38 -> inv.getChestplate();
                    case 39 -> inv.getHelmet();
                    default -> inv.getItemInOffHand();
                };
                if (cur == null || cur.getType() == Material.AIR) {
                    switch (i) {
                        case 36 -> inv.setBoots(c);
                        case 37 -> inv.setLeggings(c);
                        case 38 -> inv.setChestplate(c);
                        case 39 -> inv.setHelmet(c);
                        default -> inv.setItemInOffHand(c);
                    }
                    continue;
                }
            }
            Util.give(p, c);
        }
        store.cfg.set(key, now);
        store.save();
        Util.msg(p, "&aYou received the &e" + name + " &akit!");
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            Util.msg(p, "Kits: &e" + String.join("&7, &e", kits.keySet()) + " &8(&e/kit starter &8= free starter kit, other kits = arena modes: use them inside the arena)");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create", "edit", "delete" -> {
                if (!p.hasPermission("ultimate.owner")) { Util.msg(p, "&cOnly the server owner can do this."); return true; }
                if (sub.equals("edit") && args.length < 2) { openChooser(p); return true; }
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
                if (isGiveKit(sub)) { claim(p, sub); return true; }
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

    static final class ChooseHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    static final class BrowserHolder implements InventoryHolder {
        final String kit, filter;
        final int page;
        Inventory inv;

        BrowserHolder(String kit, int page, String filter) { this.kit = kit; this.page = page; this.filter = filter; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, String> searching = new ConcurrentHashMap<>(); // player -> kit they are browsing for
    private List<Material> allItems;

    /** /kit edit (no name): pick which kit to edit. */
    private void openChooser(Player p) {
        ChooseHolder h = new ChooseHolder();
        int size = Math.min(54, Math.max(9, ((kits.size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(h, size, Util.c("&8Edit which kit?"));
        h.inv = inv;
        int i = 0;
        for (String k : kits.keySet()) {
            if (i >= size) break;
            ItemStack it = Util.item(icon(k), "&e" + k, "&aClick to edit");
            Util.tag(it, "kit", k);
            inv.setItem(i++, it);
        }
        p.openInventory(inv);
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
                "", "&bShift+Right click an item: MAX enchant", "&7(click again to remove the enchants)",
                "&bNether star: item browser (every item)", "", "&aClose this window to SAVE"));
        inv.setItem(52, Util.item(Material.NETHER_STAR, "&bItem browser", "&7Get ANY item, like in creative",
                "&7Left click = 1, Right click = 16, Shift = full stack", "&7Then come back and place it in the kit"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ item browser (every item in the game)
    private List<Material> allItems() {
        if (allItems == null) {
            List<Material> l = new ArrayList<>();
            for (Material m : Material.values()) {
                if (m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_")) l.add(m);
            }
            l.sort(Comparator.comparing(Material::name));
            allItems = l;
        }
        return allItems;
    }

    private void openBrowser(Player p, String kit, int page, String filter) {
        String f = filter.toLowerCase().trim().replace(' ', '_');
        List<Material> list = new ArrayList<>();
        for (Material m : allItems()) if (f.isEmpty() || m.name().toLowerCase().contains(f)) list.add(m);
        int pages = Math.max(1, (list.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        BrowserHolder h = new BrowserHolder(kit, pg, filter);
        Inventory inv = Bukkit.createInventory(h, 54, Util.c("&8Items (" + (pg + 1) + "/" + pages + ")" + (f.isEmpty() ? "" : " &0" + f)));
        h.inv = inv;
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx < list.size()) inv.setItem(i, new ItemStack(list.get(idx)));
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, Util.filler(Material.BLACK_STAINED_GLASS_PANE));
        inv.setItem(45, Util.item(Material.ARROW, "&e\u00AB Previous page"));
        inv.setItem(48, Util.item(Material.WRITABLE_BOOK, "&aBack to kit editor", "&7Kit: &f" + kit));
        inv.setItem(49, Util.item(Material.PAPER, "&ePage " + (pg + 1) + " / " + pages,
                "&7" + list.size() + " items" + (f.isEmpty() ? "" : " matching &f" + f),
                "", "&7Left click: 1 | Right click: 16 | Shift: full stack"));
        inv.setItem(50, Util.item(Material.COMPASS, "&bSearch", "&7Click, then type a word in chat", "&7e.g. netherite, sword, glass"));
        inv.setItem(53, Util.item(Material.ARROW, "&eNext page \u00BB"));
        p.openInventory(inv);
    }

    private void browserClick(InventoryClickEvent e, BrowserHolder h) {
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("ultimate.owner")) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int s = e.getSlot();
        if (s < 45) {
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType() == Material.AIR) return;
            int max = it.getMaxStackSize();
            ClickType ct = e.getClick();
            int amount = (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) ? max : ct == ClickType.RIGHT ? Math.min(16, max) : 1;
            Util.give(p, new ItemStack(it.getType(), amount));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.3f);
            return;
        }
        switch (s) {
            case 45 -> openBrowser(p, h.kit, h.page - 1, h.filter);
            case 53 -> openBrowser(p, h.kit, h.page + 1, h.filter);
            case 48 -> openEditor(p, h.kit);
            case 50 -> {
                searching.put(p.getUniqueId(), h.kit);
                p.closeInventory();
                Util.msg(p, "Type what to search for in chat (for example &enetherite&7), or &ecancel&7.");
            }
            default -> { }
        }
    }

    @EventHandler
    public void onSearchChat(AsyncChatEvent e) {
        String kit = searching.remove(e.getPlayer().getUniqueId());
        if (kit == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(Main.get(), () -> {
            if (p.isOnline()) openBrowser(p, kit, 0, text.equalsIgnoreCase("cancel") ? "" : text);
        });
    }

    /** Shift+Right click in the editor: put every useful enchantment on at max level; again = remove them all. */
    private void toggleEnchant(ItemStack it) {
        if (!it.getEnchantments().isEmpty()) {
            for (Enchantment en : new ArrayList<>(it.getEnchantments().keySet())) it.removeEnchantment(en);
            return;
        }
        for (Enchantment en : Registry.ENCHANTMENT) {
            if (!en.canEnchantItem(it)) continue;
            boolean conflict = false;
            for (Enchantment ex : it.getEnchantments().keySet()) if (en.conflictsWith(ex)) { conflict = true; break; }
            if (conflict) continue;
            try {
                it.addEnchantment(en, en.getMaxLevel());
            } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------ events
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof BrowserHolder bh) { browserClick(e, bh); return; }
        if (holder instanceof ChooseHolder) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p && p.hasPermission("ultimate.owner")
                    && e.getClickedInventory() == e.getView().getTopInventory()) {
                String kit = Util.getTag(e.getCurrentItem(), "kit");
                if (kit != null && kits.containsKey(kit)) openEditor(p, kit);
            }
            return;
        }
        if (!(holder instanceof Holder h)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        if (raw == 52) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) openBrowser(p, h.kit, 0, "");
            return;
        }
        if (raw >= 0 && raw < 54 && guiToInv(raw) < 0) { e.setCancelled(true); return; }
        if (e.getClick() == ClickType.SHIFT_RIGHT && raw >= 0 && raw < 54) {
            ItemStack it = e.getCurrentItem();
            if (it != null && it.getType() != Material.AIR) {
                e.setCancelled(true);
                ItemStack copy = it.clone();
                toggleEnchant(copy);
                e.setCurrentItem(copy);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof BrowserHolder || holder instanceof ChooseHolder) { e.setCancelled(true); return; }
        if (!(holder instanceof Holder)) return;
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
