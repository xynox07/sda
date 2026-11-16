package dev.darloo;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * Login security.
 *  New player: /register <password> <password>       Returning player: /login <password>
 *  Until they log in players are frozen and blind, cannot chat, use commands, break/place, open inventories
 *  or take damage - and a big "DarlooPolice Login" title stays on their screen.
 *  /changepassword <old> <new>      /authreset <player>  (admin)
 * Passwords are never stored in plain text (salted PBKDF2-SHA256).
 */
public final class AuthManager implements TabExecutor, Listener {
    private static final SecureRandom RNG = new SecureRandom();

    private final Main plugin;
    private final Store store;
    private final Set<UUID> logged = new HashSet<>();
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private int counter;

    public AuthManager(Main plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "auth.yml");
        for (Player p : Bukkit.getOnlinePlayers()) logged.add(p.getUniqueId()); // after a /reload nobody gets locked out
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("auth.enabled", true); }

    public boolean isLoggedIn(Player p) { return !enabled() || logged.contains(p.getUniqueId()); }

    private boolean registered(UUID u) { return store.cfg.contains("users." + u + ".hash"); }

    // ------------------------------------------------------------ password hashing
    private static String hash(String pw, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 65536, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void setPassword(UUID u, String pw) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        store.cfg.set("users." + u + ".salt", Base64.getEncoder().encodeToString(salt));
        store.cfg.set("users." + u + ".hash", hash(pw, salt));
        store.save();
    }

    private boolean check(UUID u, String pw) {
        String s = store.cfg.getString("users." + u + ".salt");
        String h = store.cfg.getString("users." + u + ".hash");
        if (s == null || h == null) return false;
        String test = hash(pw, Base64.getDecoder().decode(s));
        return MessageDigest.isEqual(test.getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ login screen
    private void startAuth(Player p) {
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false));
        showLoginTitle(p);
        Util.msg(p, registered(p.getUniqueId())
                ? "&eWelcome back! Type &a/login <password>"
                : "&eWelcome! Create your account: &a/register <password> <password>");
    }

    private void showLoginTitle(Player p) {
        String sub = registered(p.getUniqueId()) ? "&e/login <password>" : "&e/register <password> <password>";
        p.showTitle(Title.title(Util.c("&9&lDarloo&b&lPolice &f&lLogin"), Util.c(sub),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(30), Duration.ZERO)));
    }

    private void success(Player p, String text) {
        UUID u = p.getUniqueId();
        logged.add(u);
        attempts.remove(u);
        joinedAt.remove(u);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.clearTitle();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        Util.msg(p, text);
        if (plugin.welcome != null) plugin.welcome.show(p);
    }

    private void tick() {
        if (!enabled()) return;
        counter++;
        int timeout = plugin.getConfig().getInt("auth.timeout-seconds", 60);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (logged.contains(u)) continue;
            Long since = joinedAt.get(u);
            if (since == null) { startAuth(p); continue; }
            long left = timeout - (now - since) / 1000L;
            if (timeout > 0 && left <= 0) { p.kick(Util.c("&cYou did not log in in time.")); continue; }
            p.sendActionBar(Util.c("&cLog in to play" + (timeout > 0 ? " &7(" + left + "s left)" : "")));
            if (counter % 5 == 0) showLoginTitle(p);
        }
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("authreset")) {
            if (!sender.hasPermission("darloo.admin")) { Util.msg(sender, "&cYou do not have permission."); return true; }
            if (args.length < 1) { Util.msg(sender, "&cUsage: /authreset <player>"); return true; }
            var off = Util.findPlayer(args[0]);
            if (off == null || !registered(off.getUniqueId())) { Util.msg(sender, "&cThat player has no account."); return true; }
            store.cfg.set("users." + off.getUniqueId(), null);
            store.save();
            logged.remove(off.getUniqueId());
            Player online = off.getPlayer();
            if (online != null && enabled()) startAuth(online);
            Util.msg(sender, "&aPassword of &e" + args[0] + " &awas reset. They must /register again.");
            return true;
        }
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        UUID u = p.getUniqueId();
        int min = plugin.getConfig().getInt("auth.min-password-length", 6);
        switch (name) {
            case "register" -> {
                if (!enabled()) { Util.msg(p, "Login is turned off."); return true; }
                if (logged.contains(u)) { Util.msg(p, "&cYou are already logged in."); return true; }
                if (registered(u)) { Util.msg(p, "&cYou already have an account. Use &e/login <password>"); return true; }
                if (args.length != 2) { Util.msg(p, "&cUsage: /register <password> <password>"); return true; }
                if (!args[0].equals(args[1])) { Util.msg(p, "&cThe two passwords are not the same."); return true; }
                if (args[0].length() < min) { Util.msg(p, "&cPassword must be at least " + min + " characters."); return true; }
                setPassword(u, args[0]);
                success(p, "&aAccount created and logged in! Remember your password.");
            }
            case "login" -> {
                if (logged.contains(u)) { Util.msg(p, "&cYou are already logged in."); return true; }
                if (!registered(u)) { Util.msg(p, "&cYou have no account. Use &e/register <password> <password>"); return true; }
                if (args.length != 1) { Util.msg(p, "&cUsage: /login <password>"); return true; }
                if (check(u, args[0])) {
                    success(p, "&aLogged in. Welcome back!");
                } else {
                    int n = attempts.merge(u, 1, Integer::sum);
                    int max = plugin.getConfig().getInt("auth.max-attempts", 5);
                    if (n >= max) { p.kick(Util.c("&cToo many wrong passwords.")); return true; }
                    Util.msg(p, "&cWrong password! (" + n + "/" + max + ")");
                }
            }
            case "changepassword" -> {
                if (!isLoggedIn(p)) { Util.msg(p, "&cLog in first."); return true; }
                if (args.length != 2) { Util.msg(p, "&cUsage: /changepassword <old> <new>"); return true; }
                if (!check(u, args[0])) { Util.msg(p, "&cOld password is wrong."); return true; }
                if (args[1].length() < min) { Util.msg(p, "&cNew password must be at least " + min + " characters."); return true; }
                setPassword(u, args[1]);
                Util.msg(p, "&aPassword changed.");
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("authreset") && args.length == 1) return Util.match(Util.onlineNames(), args[0]);
        return List.of();
    }

    // ------------------------------------------------------------ lock everything until logged in
    private boolean locked(Player p) { return enabled() && !logged.contains(p.getUniqueId()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        logged.remove(p.getUniqueId());
        if (enabled()) startAuth(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        logged.remove(u);
        joinedAt.remove(u);
        attempts.remove(u);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!locked(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location t = from.clone();
            t.setYaw(to.getYaw());
            t.setPitch(to.getPitch());
            e.setTo(t);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (locked(e.getPlayer())) {
            e.setCancelled(true);
            Util.msg(e.getPlayer(), "&cLog in first: &e/login <password> &cor &e/register <password> <password>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!locked(p)) return;
        String first = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (first.contains(":")) first = first.substring(first.indexOf(':') + 1);
        if (first.equals("login") || first.equals("register")) return;
        e.setCancelled(true);
        Util.msg(p, "&cLog in first: &e/login <password> &cor &e/register <password> <password>");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInvOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && locked(p)) e.setCancelled(true); }
}
