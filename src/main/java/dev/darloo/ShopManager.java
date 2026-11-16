package dev.darloo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * /shop            - category menu (Building Blocks, Decoration, Food, Other) -> click an item to buy it
 * /shop <category> - open a category directly
 * /shop edit       - (owner) choose a category, then edit it: LEFT 4 columns = items for sale,
 *                    RIGHT 4 columns = the price (any item: iron, emerald, ...). Close the window to save.
 * Price up/down:     the owner can Shift+Left click an item in the shop to make it +1 more expensive,
 *                    Shift+Right click to make it -1 cheaper.
 */
public final class ShopManager implements TabExecutor, Listener {
    enum Cat {
        BUILDING("building", "&6Building Blocks", Material.BRICKS),
        DECORATION("decoration", "&dDecoration", Material.PAINTING),
        FOOD("food", "&aFood", Material.COOKED_BEEF),
        MISC("misc", "&bOther Items", Material.CHEST);

        final String key, title;
        final Material icon;

        Cat(String key, String title, Material icon) { this.key = key; this.title = title; this.icon = icon; }
    }

    private static final int MAX = 24;
    private static final int[] SHOW = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39};
    private static final int[] CAT_SLOTS = {10, 12, 14, 16};

    static final class Entry {
        final ItemStack item, price;

        Entry(ItemStack item, ItemStack price) { this.item = item; this.price = price; }
    }

    static final class MenuHolder implements InventoryHolder {
        final boolean edit;
        Inventory inv;

        MenuHolder(boolean edit) { this.edit = edit; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    static final class PageHolder implements InventoryHolder {
        final Cat cat;
        Inventory inv;
        final Map<Integer, Integer> slotToEntry = new HashMap<>();

        PageHolder(Cat cat) { this.cat = cat; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    static final class EditHolder implements InventoryHolder {
        final Cat cat;
        Inventory inv;

        EditHolder(Cat cat) { this.cat = cat; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Store store;
    private final Map<Cat, List<Entry>> data = new EnumMap<>(Cat.class);

    public ShopManager(Main plugin) {
        store = new Store(plugin, "shop.yml");
        for (Cat c : Cat.values()) data.put(c, new ArrayList<>());
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------ data
    private Cat catOf(String key) {
        for (Cat c : Cat.values()) if (c.key.equalsIgnoreCase(key)) return c;
        return null;
    }

    private void load() {
        ConfigurationSection root = store.cfg.getConfigurationSection("shop");
        if (root != null) {
            for (String k : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(k);
                if (sec == null) continue;
                Cat c = catOf(k);
                if (c != null) {
                    List<String> keys = new ArrayList<>(sec.getKeys(false));
                    keys.sort(Comparator.comparingInt(x -> {
                        try { return Integer.parseInt(x); } catch (NumberFormatException e) { return 0; }
                    }));
                    for (String i : keys) {
                        ItemStack it = sec.getItemStack(i + ".item");
                        ItemStack pr = sec.getItemStack(i + ".price");
                        if (it != null && pr != null) data.get(c).add(new Entry(it, pr));
                    }
                } else if (k.matches("\\d+")) { // old shop.yml format -> goes into "Other items"
                    ItemStack it = sec.getItemStack("item");
                    ItemStack pr = sec.getItemStack("price");
                    if (it != null && pr != null) data.get(Cat.MISC).add(new Entry(it, pr));
                }
            }
        }
        boolean empty = data.values().stream().allMatch(List::isEmpty);
        if (empty && !store.cfg.getBoolean("seeded", false)) {
            defaults();
            store.cfg.set("seeded", true);
        }
        save();
    }

    private void save() {
        store.cfg.set("shop", null);
        for (Cat c : Cat.values()) {
            List<Entry> list = data.get(c);
            for (int i = 0; i < list.size(); i++) {
                store.cfg.set("shop." + c.key + "." + i + ".item", list.get(i).item);
                store.cfg.set("shop." + c.key + "." + i + ".price", list.get(i).price);
            }
        }
        store.save();
    }

    private void add(Cat c, Material m, int amount, Material price, int priceAmount) {
        data.get(c).add(new Entry(new ItemStack(m, amount), new ItemStack(price, priceAmount)));
    }

    /** Starter listings - the owner can change everything with /shop edit. */
    private void defaults() {
        add(Cat.BUILDING, Material.OAK_PLANKS, 16, Material.IRON_INGOT, 2);
        add(Cat.BUILDING, Material.DIRT, 32, Material.EMERALD, 1);
        add(Cat.BUILDING, Material.COBBLESTONE, 64, Material.IRON_INGOT, 2);
        add(Cat.BUILDING, Material.STONE_BRICKS, 16, Material.IRON_INGOT, 3);
        add(Cat.BUILDING, Material.GLASS, 16, Material.IRON_INGOT, 2);
        add(Cat.DECORATION, Material.TORCH, 16, Material.IRON_INGOT, 1);
        add(Cat.DECORATION, Material.LANTERN, 4, Material.IRON_INGOT, 3);
        add(Cat.DECORATION, Material.ITEM_FRAME, 4, Material.IRON_INGOT, 2);
        add(Cat.DECORATION, Material.PAINTING, 2, Material.IRON_INGOT, 2);
        add(Cat.DECORATION, Material.FLOWER_POT, 4, Material.IRON_INGOT, 1);
        add(Cat.FOOD, Material.COOKED_BEEF, 10, Material.IRON_INGOT, 3);
        add(Cat.FOOD, Material.BREAD, 16, Material.IRON_INGOT, 2);
        add(Cat.FOOD, Material.COOKED_PORKCHOP, 10, Material.IRON_INGOT, 3);
        add(Cat.FOOD, Material.GOLDEN_CARROT, 8, Material.IRON_INGOT, 3);
        add(Cat.FOOD, Material.GOLDEN_APPLE, 1, Material.EMERALD, 1);
    }

    private String nameOf(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
        }
        return it.getType().name().toLowerCase().replace('_', ' ');
    }

    // ------------------------------------------------------------ command
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("edit")) {
            if (!p.hasPermission("ultimate.owner")) { Util.msg(p, "&cOnly the server owner can edit the shop."); return true; }
            Cat c = args.length > 1 ? catOf(args[1]) : null;
            if (c != null) openEditor(p, c); else openMenu(p, true);
            return true;
        }
        Cat c = args.length > 0 ? catOf(args[0]) : null;
        if (c != null) openPage(p, c); else openMenu(p, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> o = new ArrayList<>();
        if (args.length == 1) {
            for (Cat c : Cat.values()) o.add(c.key);
            if (sender.hasPermission("ultimate.owner")) o.add("edit");
            return Util.match(o, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit") && sender.hasPermission("ultimate.owner")) {
            for (Cat c : Cat.values()) o.add(c.key);
            return Util.match(o, args[1]);
        }
        return List.of();
    }

    // ------------------------------------------------------------ category menu
    private void openMenu(Player p, boolean edit) {
        MenuHolder h = new MenuHolder(edit);
        Inventory inv = Bukkit.createInventory(h, 27, Util.c(edit ? "&8Shop editor - pick a category" : "&6&lDarlooMC &8Shop"));
        h.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, Util.filler(Material.BLACK_STAINED_GLASS_PANE));
        Cat[] cats = Cat.values();
        for (int i = 0; i < cats.length; i++) {
            Cat c = cats[i];
            ItemStack it = Util.item(c.icon, c.title, "&7" + data.get(c).size() + " items", "",
                    edit ? "&eClick to EDIT" : "&aClick to browse");
            Util.tag(it, "cat", c.key);
            inv.setItem(CAT_SLOTS[i], it);
        }
        inv.setItem(22, Util.item(Material.EMERALD, edit ? "&eEditor" : "&eWelcome!",
                edit ? "&7Pick a category to edit its items and prices" : "&7Pick a category, then click an item to buy it"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ category page
    private void openPage(Player p, Cat c) {
        PageHolder h = new PageHolder(c);
        Inventory inv = Bukkit.createInventory(h, 54, Util.c("&8Shop \u00BB " + c.title));
        h.inv = inv;
        render(h, p);
        p.openInventory(inv);
    }

    private ItemStack display(Entry en, boolean owner) {
        ItemStack d = en.item.clone();
        ItemMeta m = d.getItemMeta();
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        lore.add(Component.empty());
        lore.add(Util.c("&7Price: &e" + en.price.getAmount() + "x " + nameOf(en.price)).decoration(TextDecoration.ITALIC, false));
        lore.add(Util.c("&aClick to buy").decoration(TextDecoration.ITALIC, false));
        if (owner) {
            lore.add(Util.c("&8Owner: Shift+Left = price +1").decoration(TextDecoration.ITALIC, false));
            lore.add(Util.c("&8Owner: Shift+Right = price -1").decoration(TextDecoration.ITALIC, false));
        }
        m.lore(lore);
        d.setItemMeta(m);
        return d;
    }

    private void render(PageHolder h, Player p) {
        Inventory inv = h.inv;
        boolean owner = p.hasPermission("ultimate.owner");
        h.slotToEntry.clear();
        for (int i = 0; i < 54; i++) inv.setItem(i, Util.filler(Material.BLACK_STAINED_GLASS_PANE));
        inv.setItem(4, Util.item(h.cat.icon, h.cat.title));
        List<Entry> list = data.get(h.cat);
        for (int i = 0; i < Math.min(list.size(), SHOW.length); i++) {
            inv.setItem(SHOW[i], display(list.get(i), owner));
            h.slotToEntry.put(SHOW[i], i);
        }
        if (list.isEmpty()) inv.setItem(22, Util.item(Material.BARRIER, "&cNothing here yet", "&7The owner has not added items to this category"));
        inv.setItem(49, Util.item(Material.ARROW, "&e\u00AB Back to categories"));
    }

    private void buy(Player p, Entry en) {
        ItemStack cost = en.price.clone();
        if (!p.getInventory().containsAtLeast(cost, cost.getAmount())) {
            Util.msg(p, "&cYou need &e" + cost.getAmount() + "x " + nameOf(cost) + "&c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        p.getInventory().removeItem(cost);
        Util.give(p, en.item.clone());
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Util.msg(p, "&aBought &e" + en.item.getAmount() + "x " + nameOf(en.item) + " &afor &e" + cost.getAmount() + "x " + nameOf(cost));
    }

    // ------------------------------------------------------------ editor window
    private void openEditor(Player p, Cat c) {
        EditHolder h = new EditHolder(c);
        Inventory inv = Bukkit.createInventory(h, 54, Util.c("&8Edit \u00BB " + c.title));
        h.inv = inv;
        for (int r = 0; r < 6; r++) inv.setItem(r * 9 + 8, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(8, Util.item(Material.PAPER, "&eHow it works",
                "&7LEFT 4 columns: item you SELL", "&7RIGHT 4 columns: the PRICE (any item)",
                "&7(same row, same position)", "&7Example: 10 bread | 3 iron ingot",
                "&7Example: 32 dirt | 1 emerald", "", "&aClose this window to SAVE",
                "&7Tip: use creative mode to get items"));
        List<Entry> list = data.get(c);
        for (int i = 0; i < Math.min(list.size(), MAX); i++) {
            int r = i / 4, col = i % 4;
            inv.setItem(r * 9 + col, list.get(i).item.clone());
            inv.setItem(r * 9 + 4 + col, list.get(i).price.clone());
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ events
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        Object holder = top.getHolder();

        if (holder instanceof MenuHolder h) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != top) return;
            Cat c = catOf(Util.getTag(e.getCurrentItem(), "cat") == null ? "" : Util.getTag(e.getCurrentItem(), "cat"));
            if (c == null) return;
            if (h.edit) {
                if (p.hasPermission("ultimate.owner")) openEditor(p, c);
            } else {
                openPage(p, c);
            }
            return;
        }

        if (holder instanceof PageHolder h) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != top) return;
            if (e.getSlot() == 49) { openMenu(p, false); return; }
            Integer idx = h.slotToEntry.get(e.getSlot());
            List<Entry> list = data.get(h.cat);
            if (idx == null || idx >= list.size()) return;
            Entry en = list.get(idx);
            ClickType ct = e.getClick();
            if ((ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) && p.hasPermission("ultimate.owner")) {
                int now = en.price.getAmount();
                int next = ct == ClickType.SHIFT_LEFT ? now + 1 : now - 1;
                next = Math.max(1, Math.min(en.price.getMaxStackSize(), next));
                en.price.setAmount(next);
                save();
                render(h, p);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, ct == ClickType.SHIFT_LEFT ? 1.4f : 0.8f);
                Util.msg(p, "Price of &e" + nameOf(en.item) + " &7is now &e" + next + "x " + nameOf(en.price));
                return;
            }
            buy(p, en);
            return;
        }

        if (holder instanceof EditHolder) {
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
            int raw = e.getRawSlot();
            if (raw >= 0 && raw < 54 && raw % 9 == 8) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof MenuHolder || holder instanceof PageHolder) { e.setCancelled(true); return; }
        if (holder instanceof EditHolder) {
            for (int raw : e.getRawSlots()) if (raw < 54 && raw % 9 == 8) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof EditHolder h)) return;
        if (!e.getPlayer().hasPermission("ultimate.owner")) return;
        Inventory inv = e.getInventory();
        List<Entry> fresh = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < MAX; i++) {
            int r = i / 4, c = i % 4;
            ItemStack item = inv.getItem(r * 9 + c);
            ItemStack price = inv.getItem(r * 9 + 4 + c);
            boolean hasItem = item != null && item.getType() != Material.AIR;
            boolean hasPrice = price != null && price.getType() != Material.AIR;
            if (hasItem && hasPrice) fresh.add(new Entry(item.clone(), price.clone()));
            else if (hasItem || hasPrice) skipped++;
        }
        data.get(h.cat).clear();
        data.get(h.cat).addAll(fresh);
        save();
        Util.msg(e.getPlayer(), "Saved &e" + fresh.size() + " &7listings in " + h.cat.title.replaceAll("&.", "") + "."
                + (skipped > 0 ? " &c" + skipped + " incomplete listing(s) skipped (item AND price are both needed)." : ""));
    }
}
