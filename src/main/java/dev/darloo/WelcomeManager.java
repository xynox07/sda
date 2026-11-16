package dev.darloo;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Big "WELCOME TO DARLOOMC" title for brand new players (shown right after they log in). */
public final class WelcomeManager implements Listener {
    private final Main plugin;
    private final Set<UUID> pending = new HashSet<>();   // joined, welcome not shown yet
    private final Set<UUID> newcomers = new HashSet<>(); // first time on the server

    public WelcomeManager(Main plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        pending.add(p.getUniqueId());
        if (!p.hasPlayedBefore()) newcomers.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.auth != null && plugin.auth.isLoggedIn(p)) show(p);
        }, 30L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
        newcomers.remove(e.getPlayer().getUniqueId());
    }

    /** Called when the player is logged in (or immediately when login is switched off). */
    public void show(Player p) {
        UUID u = p.getUniqueId();
        if (!pending.remove(u)) return;
        boolean first = newcomers.remove(u);
        if (first) {
            String title = plugin.getConfig().getString("welcome.title", "&6&lWELCOME TO DARLOOMC");
            String sub = plugin.getConfig().getString("welcome.subtitle", "&7Have fun, &e%player%&7!").replace("%player%", p.getName());
            p.showTitle(Title.title(Util.c(title), Util.c(sub),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            Bukkit.broadcast(Util.c(Main.PREFIX + "&e" + p.getName() + " &7joined &6DarlooMC &7for the first time. Say hi!"));
        } else {
            String msg = plugin.getConfig().getString("welcome.returning-message", "&7Welcome back to &6DarlooMC&7, &e%player%&7!")
                    .replace("%player%", p.getName());
            p.sendActionBar(Util.c(msg));
        }
    }
}
