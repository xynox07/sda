package dev.darloo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * /tpa <player>      - ask to teleport TO a player
 * /tpahere <player>  - ask a player to teleport to YOU
 * /tpaccept [player], /tpdeny [player], /tpcancel
 * Teleport has a short countdown (default 3 s) and is cancelled if the player moves.
 */
public final class TpaManager implements TabExecutor, Listener {
    private static final class Req {
        final UUID from, to;
        final boolean here;
        final long time;

        Req(UUID from, UUID to, boolean here) {
            this.from = from; this.to = to; this.here = here; this.time = System.currentTimeMillis();
        }
    }

    private final Main plugin;
    private final ArenaManager arenas;
    private final PunishManager punish;
    private final Map<UUID, Req> pending = new HashMap<>(); // key = the player who has to answer

    public TpaManager(Main plugin, ArenaManager arenas, PunishManager punish) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.punish = punish;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private boolean blocked(Player x) { return arenas.isInArena(x) || punish.isJailed(x); }

    private boolean expired(Req r) {
        return System.currentTimeMillis() - r.time > plugin.getConfig().getInt("tpa.expire-seconds", 60) * 1000L;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "tpa" -> request(p, args, false);
            case "tpahere" -> request(p, args, true);
            case "tpaccept" -> answer(p, args, true);
            case "tpdeny" -> answer(p, args, false);
            case "tpcancel" -> {
                boolean removed = pending.entrySet().removeIf(e -> e.getValue().from.equals(p.getUniqueId()));
                Util.msg(p, removed ? "Your teleport request was cancelled." : "&cYou have no pending request.");
            }
            default -> { }
        }
        return true;
    }

    private void request(Player p, String[] args, boolean here) {
        if (args.length < 1) { Util.msg(p, "&cUsage: /" + (here ? "tpahere" : "tpa") + " <player>"); return; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) { Util.msg(p, "&cPlayer not found."); return; }
        if (t.equals(p)) { Util.msg(p, "&cYou cannot teleport to yourself."); return; }
        if (blocked(p) || blocked(t)) { Util.msg(p, "&cYou cannot teleport while in the arena or in jail."); return; }

        pending.put(t.getUniqueId(), new Req(p.getUniqueId(), t.getUniqueId(), here));
        Util.msg(p, "Request sent to &e" + t.getName() + "&7. It expires in "
                + plugin.getConfig().getInt("tpa.expire-seconds", 60) + " seconds. Cancel with &e/tpcancel");
        Component m = Util.c(Main.PREFIX + "&e" + p.getName() + (here ? " &7wants YOU to teleport to them. " : " &7wants to teleport to you. "))
                .append(Util.c("&a&l[ACCEPT]").clickEvent(ClickEvent.runCommand("/tpaccept"))
                        .hoverEvent(HoverEvent.showText(Util.c("&7Click to accept"))))
                .append(Component.text(" "))
                .append(Util.c("&c&l[DENY]").clickEvent(ClickEvent.runCommand("/tpdeny"))
                        .hoverEvent(HoverEvent.showText(Util.c("&7Click to deny"))));
        t.sendMessage(m);
        t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    private void answer(Player p, String[] args, boolean accept) {
        Req r = pending.get(p.getUniqueId());
        if (r != null && expired(r)) { pending.remove(p.getUniqueId()); r = null; }
        if (r == null) { Util.msg(p, "&cYou have no pending teleport request."); return; }
        Player from = Bukkit.getPlayer(r.from);
        if (args.length > 0) {
            Player named = Bukkit.getPlayerExact(args[0]);
            if (named == null || !named.getUniqueId().equals(r.from)) { Util.msg(p, "&cNo request from that player."); return; }
        }
        pending.remove(p.getUniqueId());
        if (from == null) { Util.msg(p, "&cThat player is no longer online."); return; }

        if (!accept) {
            Util.msg(p, "Request denied.");
            Util.msg(from, "&e" + p.getName() + " &cdenied &7your teleport request.");
            return;
        }
        Util.msg(p, "Request accepted.");
        Util.msg(from, "&e" + p.getName() + " &aaccepted &7your teleport request.");
        Player mover = r.here ? p : from;
        Player dest = r.here ? from : p;
        teleport(mover, dest);
    }

    private void teleport(Player mover, Player dest) {
        int delay = plugin.getConfig().getInt("tpa.delay-seconds", 3);
        if (delay <= 0) { go(mover, dest); return; }
        Location start = mover.getLocation();
        Util.msg(mover, "&7Teleporting in &e" + delay + " &7seconds - don't move!");
        new BukkitRunnable() {
            int left = delay;

            @Override
            public void run() {
                if (!mover.isOnline() || !dest.isOnline()) { cancel(); return; }
                Location now = mover.getLocation();
                if (!now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 1.0) {
                    Util.msg(mover, "&cTeleport cancelled because you moved.");
                    cancel();
                    return;
                }
                left--;
                if (left <= 0) { go(mover, dest); cancel(); }
                else mover.sendActionBar(Util.c("&eTeleporting in " + left + "..."));
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void go(Player mover, Player dest) {
        if (blocked(mover) || blocked(dest)) { Util.msg(mover, "&cTeleport cancelled (arena or jail)."); return; }
        mover.teleport(dest.getLocation());
        mover.playSound(mover.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        Util.msg(mover, "&aTeleported to &e" + dest.getName() + "&a.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Util.match(Util.onlineNames(), args[0]);
        return List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        pending.remove(u);
        pending.entrySet().removeIf(en -> en.getValue().from.equals(u));
    }
}
