package dev.darloo;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Announcements.
 *  /announcement <text>        send an announcement to everybody  
 *  /announcement edit          (owner) edit color, bold, italic, underline, FONT, SOUND, where it shows, auto interval
 *  /announcement add|list|remove   automatic announcements that repeat every N minutes
 * You can also write colors inside the text with & codes, e.g.  /announcement &aWelcome to &6DarlooMC
 */
public final class AnnouncementManager implements TabExecutor, Listener {
    private static final String[] COLOR_NAMES = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private static final String[] COLOR_CODES = {"&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9", "&a", "&b", "&c", "&d", "&e", "&f"};
    private static final Material[] COLOR_ITEMS = {Material.BLACK_WOOL, Material.BLUE_WOOL, Material.GREEN_WOOL, Material.CYAN_WOOL,
            Material.RED_TERRACOTTA, Material.PURPLE_WOOL, Material.ORANGE_WOOL, Material.LIGHT_GRAY_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.LIME_WOOL, Material.LIGHT_BLUE_CONCRETE, Material.RED_WOOL, Material.MAGENTA_WOOL,
            Material.YELLOW_WOOL, Material.WHITE_WOOL};
    private static final int[] PALETTE = {18, 19, 20, 21, 22, 23, 24, 25, 27, 28, 29, 30, 31, 32, 33, 34};
    private static final String[] FONTS = {"default", "uniform", "alt", "illageralt"};
    private static final String[] FONT_LABELS = {"Default", "Uniform (clean letters)", "Alt (enchanting-table letters)", "Illager (rune letters)"};
    private static final String[] SOUNDS = {"none", "entity.player.levelup", "block.note_block.pling", "entity.experience_orb.pickup",
            "ui.toast.challenge_complete", "block.bell.use", "entity.firework_rocket.launch", "entity.villager.celebrate",
            "entity.ender_dragon.growl", "entity.wither.spawn", "block.anvil.land", "entity.generic.explode"};
    private static final String[] MODES = {"chat", "title", "actionbar", "all"};
    private static final String[] MODE_LABELS = {"Chat message", "Big title on screen", "Action bar (above hotbar)", "Everything (chat + title + action bar)"};
    private static final int[] INTERVALS = {0, 1, 2, 3, 5, 10, 15, 30, 60};

    static final class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Main plugin;
    private final Store store;
    private final Map<UUID, Boolean> prompts = new ConcurrentHashMap<>();

    // style
    private String color = "gold";
    private boolean bold = true, italic = false, underlined = false, prefix = true;
    private String font = "default", sound = "entity.player.levelup", mode = "chat";
    private int interval = 0;
    private final List<String> messages = new ArrayList<>();
    private int next = 0, countdown = 0;

    public AnnouncementManager(Main plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "announcements.yml");
        color = store.cfg.getString("style.color", color);
        bold = store.cfg.getBoolean("style.bold", bold);
        italic = store.cfg.getBoolean("style.italic", italic);
        underlined = store.cfg.getBoolean("style.underlined", underlined);
        prefix = store.cfg.getBoolean("style.prefix", prefix);
        font = store.cfg.getString("style.font", font);
        sound = store.cfg.getString("style.sound", sound);
        mode = store.cfg.getString("style.mode", mode);
        interval = store.cfg.getInt("interval", 0);
        messages.addAll(store.cfg.getStringList("messages"));
        countdown = interval * 60;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void save() {
        store.cfg.set("style.color", color);
        store.cfg.set("style.bold", bold);
        store.cfg.set("style.italic", italic);
        store.cfg.set("style.underlined", underlined);
        store.cfg.set("style.prefix", prefix);
        store.cfg.set("style.font", font);
        store.cfg.set("style.sound", sound);
        store.cfg.set("style.mode", mode);
        store.cfg.set("interval", interval);
        store.cfg.set("messages", messages);
        store.save();
    }

    // ------------------------------------------------------------ building + sending
    /** The text in the current style: color, bold / italic / underline and font. */
    private Component style(String text) {
        NamedTextColor col = NamedTextColor.NAMES.value(color);
        if (col == null) col = NamedTextColor.GOLD;
        Component c = Util.c(text).colorIfAbsent(col)
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.ITALIC, italic)
                .decoration(TextDecoration.UNDERLINED, underlined);
        if (!font.equals("default")) c = c.font(Key.key("minecraft", font));
        return c;
    }

    /** Sends the announcement to the players (null = everybody online, also written to the console). */
    private void announce(String text, Collection<? extends Player> targets) {
        Component msg = style(text);
        Component line = prefix ? Util.c("&8[&6Announcement&8] ").append(msg) : msg;
        boolean chat = mode.equals("chat") || mode.equals("all");
        boolean title = mode.equals("title") || mode.equals("all");
        boolean bar = mode.equals("actionbar") || mode.equals("all");
        Collection<? extends Player> to = targets == null ? Bukkit.getOnlinePlayers() : targets;
        if (targets == null && chat) Bukkit.broadcast(line);
        for (Player p : to) {
            if (targets != null && chat) p.sendMessage(line);
            if (title) p.showTitle(Title.title(msg, Component.empty(),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(700))));
            if (bar) p.sendActionBar(msg);
            if (!sound.equals("none")) p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }

    private void tick() {
        if (interval <= 0 || messages.isEmpty()) return;
        if (--countdown > 0) return;
        countdown = interval * 60;
        announce(messages.get(next % messages.size()), null);
        next++;
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("darloo.announce")) { Util.msg(sender, "&cYou do not have permission."); return true; }
        if (args.length == 0) {
            Util.msg(sender, "&e/announcement <text> &7- send to everybody (try &e/announcement welcome to the server&7)");
            Util.msg(sender, "&e/announcement edit &7- style: color, bold, font, sound (owner)");
            Util.msg(sender, "&e/announcement add <text> &7| &e/announcement list &7| &e/announcement remove <number>");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "edit" -> {
                if (!sender.hasPermission("ultimate.owner")) { Util.msg(sender, "&cOnly the server owner can edit the style."); return true; }
                if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
                open(p);
            }
            case "add" -> {
                if (args.length < 2) { Util.msg(sender, "&cUsage: /announcement add <text>"); return true; }
                messages.add(text(args, 1));
                save();
                Util.msg(sender, "Added automatic announcement &e#" + messages.size() + "&7. "
                        + (interval <= 0 ? "Turn on the repeat time in &e/announcement edit&7." : "It repeats every " + interval + " min."));
            }
            case "list" -> {
                if (messages.isEmpty()) { Util.msg(sender, "No automatic announcements yet. Add one: &e/announcement add <text>"); return true; }
                for (int i = 0; i < messages.size(); i++) Util.msg(sender, "&e#" + (i + 1) + " &f" + messages.get(i));
                Util.msg(sender, "Repeat: " + (interval <= 0 ? "&cOFF" : "&aevery " + interval + " min"));
            }
            case "remove" -> {
                int n;
                try { n = Integer.parseInt(args.length > 1 ? args[1] : "x"); } catch (NumberFormatException e) { Util.msg(sender, "&cUsage: /announcement remove <number>"); return true; }
                if (n < 1 || n > messages.size()) { Util.msg(sender, "&cThere is no announcement with that number."); return true; }
                messages.remove(n - 1);
                save();
                Util.msg(sender, "Removed.");
            }
            case "say" -> {
                if (args.length < 2) { Util.msg(sender, "&cUsage: /announcement say <text>"); return true; }
                announce(text(args, 1), null);
            }
            default -> announce(text(args, 0), null);
        }
        return true;
    }

    private String text(String[] args, int from) {
        String t = String.join(" ", Arrays.copyOfRange(args, from, args.length));
        return t.length() > 256 ? t.substring(0, 256) : t;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.match(List.of("edit", "add", "list", "remove", "say"), args[0]);
        return List.of();
    }

    // ------------------------------------------------------------ editor GUI
    private void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, Util.c("&8Announcement editor"));
        h.inv = inv;
        render(inv);
        p.openInventory(inv);
    }

    private ItemStack glow(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack toggle(Material m, String name, boolean on, String... lore) {
        List<String> l = new ArrayList<>(Arrays.asList(lore));
        l.add(0, "&7Now: " + (on ? "&aON" : "&cOFF"));
        l.add("");
        l.add("&eClick to switch");
        ItemStack it = Util.item(m, (on ? "&a" : "&c") + name, l.toArray(new String[0]));
        return on ? glow(it) : it;
    }

    private void render(Inventory inv) {
        for (int i = 0; i < 54; i++) inv.setItem(i, Util.filler(Material.GRAY_STAINED_GLASS_PANE));

        // live preview in the style that is selected right now
        ItemStack sample = new ItemStack(Material.OAK_SIGN);
        ItemMeta sm = sample.getItemMeta();
        sm.displayName(style("Welcome to the server!"));
        sm.lore(List.of(Util.c("&7This is how your announcements look").decoration(TextDecoration.ITALIC, false)));
        sample.setItemMeta(sm);
        inv.setItem(4, sample);

        inv.setItem(10, toggle(Material.BLAZE_POWDER, "Bold", bold));
        inv.setItem(11, toggle(Material.FEATHER, "Italic", italic));
        inv.setItem(12, toggle(Material.STRING, "Underline", underlined));
        int fi = Math.max(0, Arrays.asList(FONTS).indexOf(font));
        inv.setItem(13, Util.item(Material.ENCHANTING_TABLE, "&eFont", "&7Now: &f" + FONT_LABELS[fi], "",
                "&aLeft click: next font", "&aRight click: previous font"));
        inv.setItem(14, Util.item(Material.NOTE_BLOCK, "&eSound", "&7Now: &f" + sound, "",
                "&aLeft click: next sound", "&aRight click: previous sound", "&aShift+Left: type a custom sound key", "&aShift+Right: preview"));
        int mi = Math.max(0, Arrays.asList(MODES).indexOf(mode));
        inv.setItem(15, Util.item(Material.COMPARATOR, "&eWhere it shows", "&7Now: &f" + MODE_LABELS[mi], "", "&aClick to change"));
        inv.setItem(16, toggle(Material.NAME_TAG, "[Announcement] prefix", prefix, "&7Shown before chat announcements"));

        for (int i = 0; i < 16; i++) {
            ItemStack c = Util.item(COLOR_ITEMS[i], COLOR_CODES[i] + COLOR_NAMES[i].replace('_', ' '),
                    COLOR_NAMES[i].equals(color) ? "&aSelected" : "&7Click to use this color");
            inv.setItem(PALETTE[i], COLOR_NAMES[i].equals(color) ? glow(c) : c);
        }
        inv.setItem(38, Util.item(Material.CLOCK, "&eAuto announcements", "&7Repeat: " + (interval <= 0 ? "&cOFF" : "&aevery " + interval + " min"),
                "&7Messages: &f" + messages.size(), "", "&aLeft click: longer", "&aRight click: shorter",
                "&7Add messages: &f/announcement add <text>"));
        inv.setItem(40, Util.item(Material.FIREWORK_ROCKET, "&bTest it", "&7Shows an announcement to YOU only"));
        inv.setItem(49, Util.item(Material.BARRIER, "&cClose"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("ultimate.owner")) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Inventory inv = e.getView().getTopInventory();
        ClickType ct = e.getClick();
        int slot = e.getSlot();

        for (int i = 0; i < 16; i++) {
            if (PALETTE[i] == slot) { color = COLOR_NAMES[i]; save(); render(inv); click(p); return; }
        }
        switch (slot) {
            case 10 -> { bold = !bold; save(); render(inv); click(p); }
            case 11 -> { italic = !italic; save(); render(inv); click(p); }
            case 12 -> { underlined = !underlined; save(); render(inv); click(p); }
            case 13 -> {
                int idx = Math.max(0, Arrays.asList(FONTS).indexOf(font));
                idx = (idx + (ct == ClickType.RIGHT ? -1 : 1) + FONTS.length) % FONTS.length;
                font = FONTS[idx];
                save(); render(inv); click(p);
            }
            case 14 -> {
                if (ct == ClickType.SHIFT_LEFT) {
                    prompts.put(p.getUniqueId(), Boolean.TRUE);
                    p.closeInventory();
                    Util.msg(p, "Type the sound key in chat (or &ecancel&7). Examples: &fentity.cat.hiss &7or, with a resource pack, &fdarloo:welcome");
                    return;
                }
                if (ct == ClickType.SHIFT_RIGHT) { if (!sound.equals("none")) p.playSound(p.getLocation(), sound, 1f, 1f); return; }
                int idx = Arrays.asList(SOUNDS).indexOf(sound);
                int nx = idx < 0 ? 0 : idx + (ct == ClickType.RIGHT ? -1 : 1);
                sound = SOUNDS[(nx + SOUNDS.length) % SOUNDS.length];
                save(); render(inv);
                if (!sound.equals("none")) p.playSound(p.getLocation(), sound, 1f, 1f);
            }
            case 15 -> {
                int idx = Math.max(0, Arrays.asList(MODES).indexOf(mode));
                mode = MODES[(idx + 1) % MODES.length];
                save(); render(inv); click(p);
            }
            case 16 -> { prefix = !prefix; save(); render(inv); click(p); }
            case 38 -> {
                int idx = 0;
                for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == interval) idx = i;
                idx = (idx + (ct == ClickType.RIGHT ? -1 : 1) + INTERVALS.length) % INTERVALS.length;
                interval = INTERVALS[idx];
                countdown = interval * 60;
                save(); render(inv); click(p);
            }
            case 40 -> announce("Welcome to the server!", List.of(p));
            case 49 -> p.closeInventory();
            default -> { }
        }
    }

    private void click(Player p) { p.playSound(p.getLocation(), "ui.button.click", 1f, 1.2f); }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (prompts.remove(e.getPlayer().getUniqueId()) == null) return;
        e.setCancelled(true);
        String t = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!t.equalsIgnoreCase("cancel") && !t.isEmpty()) {
                sound = t.toLowerCase();
                save();
                Util.msg(p, "&aSound set to &f" + sound);
            }
            if (p.isOnline()) open(p);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }
}
