package dev.darloo;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memes: a big title + a sound on a player's screen.
 *  "punish" - GHOP GHOP when a player gets punished
 *  "noob"   - NOOB when a player dies from fall damage in PvP
 *  /mems            list      /mems edit   (owner) edit text, sound, repeat, on/off
 *  /mems test <meme> (owner) plays the meme on you
 * Sounds are vanilla sound keys. If you have a resource pack with your own sound, choose
 * "custom sound" in the editor and type its key (for example darloo:ghop).
 */
public final class MemeManager implements TabExecutor, Listener {
    private static final String[] SOUNDS = {
            "entity.generic.drink", "entity.generic.eat", "entity.player.burp", "entity.villager.no", "entity.villager.ambient",
            "entity.wither.spawn", "entity.enderman.scream", "entity.ghast.scream", "entity.cat.hiss", "entity.chicken.ambient",
            "entity.pig.ambient", "entity.cow.ambient", "entity.goat.screaming.ambient", "entity.zombie.ambient",
            "entity.creeper.primed", "entity.ender_dragon.growl", "entity.player.levelup", "block.anvil.land",
            "entity.experience_orb.pickup", "block.note_block.bass", "entity.arrow.hit_player", "entity.ravager.roar"
    };

    static final class Meme {
        final String key;
        final String name;
        final Material icon;
        boolean enabled = true;
        String title, subtitle, sound;
        int repeat = 1;

        Meme(String key, String name, Material icon, String title, String subtitle, String sound, int repeat) {
            this.key = key; this.name = name; this.icon = icon;
            this.title = title; this.subtitle = subtitle; this.sound = sound; this.repeat = repeat;
        }
    }

    static final class ListHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    static final class EditHolder implements InventoryHolder {
        final String key;
        Inventory inv;

        EditHolder(String key) { this.key = key; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Main plugin;
    private final Store store;
    private final Map<String, Meme> memes = new LinkedHashMap<>();
    private final Map<UUID, String[]> prompts = new ConcurrentHashMap<>(); // player -> {meme, field}
    private final Map<UUID, Long> lastPvp = new HashMap<>();
    private final Set<UUID> noobPending = new HashSet<>();

    public MemeManager(Main plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "memes.yml");
        memes.put("punish", new Meme("punish", "Punished (GHOP GHOP)", Material.COOKED_BEEF,
                "&e&lGHOP GHOP", "&7%player% got punished!", "entity.generic.drink", 2));
        memes.put("noob", new Meme("noob", "Fall death in PvP (NOOB)", Material.FEATHER,
                "&c&lNOOB", "&7%player% died from fall damage", "entity.villager.no", 1));
        for (Meme m : memes.values()) {
            ConfigurationSection s = store.cfg.getConfigurationSection("memes." + m.key);
            if (s == null) continue;
            m.enabled = s.getBoolean("enabled", true);
            m.title = s.getString("title", m.title);
            m.subtitle = s.getString("subtitle", m.subtitle);
            m.sound = s.getString("sound", m.sound);
            m.repeat = Math.max(1, Math.min(5, s.getInt("repeat", m.repeat)));
        }
        save();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void save() {
        for (Meme m : memes.values()) {
            String b = "memes." + m.key + ".";
            store.cfg.set(b + "enabled", m.enabled);
            store.cfg.set(b + "title", m.title);
            store.cfg.set(b + "subtitle", m.subtitle);
            store.cfg.set(b + "sound", m.sound);
            store.cfg.set(b + "repeat", m.repeat);
        }
        store.save();
    }

    /** Shows the meme on the player's screen with its sound. Returns false when the meme is turned off. */
    public boolean trigger(String key, Player p) {
        Meme m = memes.get(key);
        if (m == null || !m.enabled) return false;
        p.showTitle(Title.title(Util.c(m.title.replace("%player%", p.getName())), Util.c(m.subtitle.replace("%player%", p.getName())),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
        for (int i = 0; i < m.repeat; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.playSound(p.getLocation(), m.sound, 1f, 1f); }, i * 6L);
        }
        return true;
    }

    // ------------------------------------------------------------ noob meme (fall death in PvP)
    private Player attacker(Entity d) {
        if (d instanceof Player pl) return pl;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player sh) return sh;
        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player a = attacker(e.getDamager());
        if (a != null && !a.equals(victim)) lastPvp.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        EntityDamageEvent last = p.getLastDamageCause();
        if (last == null || last.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Long t = lastPvp.get(p.getUniqueId());
        if (t == null || System.currentTimeMillis() - t > 15_000L) return;
        Meme m = memes.get("noob");
        if (m == null || !m.enabled) return;
        noobPending.add(p.getUniqueId());
        Bukkit.broadcast(Util.c(Main.PREFIX + "&c" + p.getName() + " &7died from fall damage in PvP. &e&lNOOB!"));
    }

    /** The meme is shown right after respawn, so the player really sees it (not hidden by the death screen). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!noobPending.remove(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) trigger("noob", p); }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        lastPvp.remove(u);
        noobPending.remove(u);
        prompts.remove(u);
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("ultimate.owner")) { Util.msg(sender, "&cOnly the server owner can use /mems."); return true; }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "edit" -> {
                if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
                openList(p);
            }
            case "test" -> {
                if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
                if (args.length < 2 || !memes.containsKey(args[1].toLowerCase())) { Util.msg(p, "&cUsage: /mems test <" + String.join("|", memes.keySet()) + ">"); return true; }
                if (!trigger(args[1].toLowerCase(), p)) Util.msg(p, "&cThat meme is turned off.");
            }
            default -> {
                for (Meme m : memes.values()) {
                    Util.msg(sender, (m.enabled ? "&a[ON] " : "&c[OFF] ") + "&e" + m.key + " &7- " + m.name + " &8| &f" + m.sound + " x" + m.repeat);
                }
                Util.msg(sender, "&e/mems edit &7- change text and sound | &e/mems test <meme>");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.match(List.of("edit", "test", "list"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) return Util.match(memes.keySet(), args[1]);
        return List.of();
    }

    // ------------------------------------------------------------ editor GUI
    private void openList(Player p) {
        ListHolder h = new ListHolder();
        Inventory inv = Bukkit.createInventory(h, 27, Util.c("&8Memes - pick one to edit"));
        h.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        int slot = 11;
        for (Meme m : memes.values()) {
            ItemStack it = Util.item(m.icon, "&e" + m.name,
                    "&7Status: " + (m.enabled ? "&aON" : "&cOFF"), "&7Title: &f" + m.title, "&7Sound: &f" + m.sound + " x" + m.repeat,
                    "", "&aClick to edit");
            Util.tag(it, "meme", m.key);
            inv.setItem(slot, it);
            slot += 4;
        }
        p.openInventory(inv);
    }

    private void openEdit(Player p, String key) {
        Meme m = memes.get(key);
        if (m == null) return;
        EditHolder h = new EditHolder(key);
        Inventory inv = Bukkit.createInventory(h, 27, Util.c("&8Edit meme: &0" + m.key));
        h.inv = inv;
        render(inv, m);
        p.openInventory(inv);
    }

    private void render(Inventory inv, Meme m) {
        for (int i = 0; i < 27; i++) inv.setItem(i, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(10, Util.item(Material.NAME_TAG, "&eTitle", "&7Now: &f" + m.title, "", "&aClick, then type the new text in chat", "&7(colors with &, use %player% for the name)"));
        inv.setItem(11, Util.item(Material.PAPER, "&eSubtitle", "&7Now: &f" + m.subtitle, "", "&aClick, then type the new text in chat"));
        inv.setItem(12, Util.item(Material.NOTE_BLOCK, "&eSound", "&7Now: &f" + m.sound, "",
                "&aLeft click: next sound", "&aRight click: previous sound", "&aShift+Left: type a custom sound key", "&aShift+Right: preview"));
        inv.setItem(13, Util.item(Material.CLOCK, "&eRepeat sound", "&7Now: &f" + m.repeat + " time(s)", "", "&aClick to change (1-5)"));
        inv.setItem(14, Util.item(m.enabled ? Material.LIME_DYE : Material.GRAY_DYE, m.enabled ? "&aEnabled" : "&cDisabled", "&aClick to switch"));
        inv.setItem(16, Util.item(Material.FIREWORK_ROCKET, "&bTest it", "&7Plays the meme on you"));
        inv.setItem(22, Util.item(Material.ARROW, "&e\u00AB Back"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof ListHolder) && !(holder instanceof EditHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("ultimate.owner")) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        if (holder instanceof ListHolder) {
            String key = Util.getTag(e.getCurrentItem(), "meme");
            if (key != null) openEdit(p, key);
            return;
        }
        EditHolder h = (EditHolder) holder;
        Meme m = memes.get(h.key);
        if (m == null) return;
        ClickType ct = e.getClick();
        switch (e.getSlot()) {
            case 10 -> askChat(p, h.key, "title");
            case 11 -> askChat(p, h.key, "subtitle");
            case 12 -> {
                if (ct == ClickType.SHIFT_LEFT) { askChat(p, h.key, "sound"); return; }
                if (ct == ClickType.SHIFT_RIGHT) { p.playSound(p.getLocation(), m.sound, 1f, 1f); return; }
                int idx = Arrays.asList(SOUNDS).indexOf(m.sound);
                int next = ct == ClickType.RIGHT ? idx - 1 : idx + 1;
                if (idx < 0) next = 0;
                m.sound = SOUNDS[(next + SOUNDS.length) % SOUNDS.length];
                save();
                p.playSound(p.getLocation(), m.sound, 1f, 1f);
                render(e.getView().getTopInventory(), m);
            }
            case 13 -> { m.repeat = m.repeat >= 5 ? 1 : m.repeat + 1; save(); render(e.getView().getTopInventory(), m); }
            case 14 -> { m.enabled = !m.enabled; save(); render(e.getView().getTopInventory(), m); }
            case 16 -> { boolean was = m.enabled; m.enabled = true; trigger(m.key, p); m.enabled = was; }
            case 22 -> openList(p);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof EditHolder) e.setCancelled(true);
    }

    private void askChat(Player p, String key, String field) {
        prompts.put(p.getUniqueId(), new String[]{key, field});
        p.closeInventory();
        Util.msg(p, "Type the new &e" + field + " &7in chat (or &ecancel&7)." + (field.equals("sound") ? " Example: &fentity.cat.hiss &7or &fdarloo:ghop" : ""));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        String[] pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Meme m = memes.get(pr[0]);
            if (m != null && !text.equalsIgnoreCase("cancel") && !text.isEmpty()) {
                switch (pr[1]) {
                    case "title" -> m.title = text;
                    case "subtitle" -> m.subtitle = text;
                    case "sound" -> m.sound = text.toLowerCase();
                    default -> { }
                }
                save();
                Util.msg(p, "&aSaved.");
            }
            if (p.isOnline()) openEdit(p, pr[0]);
        });
    }
}
