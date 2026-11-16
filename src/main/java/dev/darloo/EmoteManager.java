package dev.darloo;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.*;

/** /sit, /spin [player], /dap <player>, /hug <player>, /wave <player>, /highfive <player> */
public final class EmoteManager implements TabExecutor, Listener {
    private static final double EMOTE_RANGE = 8.0;

    private static final class Seat {
        final ArmorStand stand;
        final Location origin;

        Seat(ArmorStand stand, Location origin) { this.stand = stand; this.origin = origin; }
    }

    private final Main plugin;
    private final Map<UUID, Seat> seats = new HashMap<>();
    private final Set<UUID> spinning = new HashSet<>();

    public EmoteManager(Main plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        for (Seat s : seats.values()) s.stand.remove();
        seats.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        String name = cmd.getName().toLowerCase();
        switch (name) {
            case "sit" -> sit(p);
            case "spin" -> {
                Player target = p;
                if (args.length > 0) {
                    if (!p.hasPermission("ultimate.emote.others")) { Util.msg(p, "&cYou cannot spin other players."); return true; }
                    target = Bukkit.getPlayerExact(args[0]);
                    if (target == null) { Util.msg(p, "&cPlayer not found."); return true; }
                }
                spin(p, target);
            }
            case "dap" -> {
                Player t = pair(p, args, name, "dapped up", "&6", Particle.CRIT, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f);
                if (t != null) dapPopup(p, t);
            }
            case "hug" -> pair(p, args, name, "hugged", "&d", Particle.HEART, Sound.ENTITY_VILLAGER_YES, 1f);
            case "wave" -> pair(p, args, name, "waved at", "&b", Particle.NOTE, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f);
            case "highfive" -> pair(p, args, name, "high-fived", "&a", Particle.CLOUD, Sound.ENTITY_PLAYER_LEVELUP, 1.5f);
            default -> { }
        }
        return true;
    }

    // ------------------------------------------------------------ sit
    private void sit(Player p) {
        Seat old = seats.remove(p.getUniqueId());
        if (old != null) { standUp(p, old); return; } // /sit again = stand up

        if (p.isInsideVehicle()) { Util.msg(p, "&cYou are already riding something."); return; }
        if (!p.isOnGround()) { Util.msg(p, "&cYou can only sit while standing on the ground."); return; }

        // A marker armor stand placed at the player's feet makes the player sit with the
        // hips right on the ground (the game lowers the rider by 0.6 blocks by itself).
        Location origin = p.getLocation();
        ArmorStand st = p.getWorld().spawn(origin, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
            a.setBasePlate(false);
        });
        if (!st.addPassenger(p)) {
            st.remove();
            Util.msg(p, "&cYou cannot sit here.");
            return;
        }
        seats.put(p.getUniqueId(), new Seat(st, origin));
        p.sendActionBar(Util.c("&7Sneak (shift) or type &e/sit &7again to stand up"));
    }

    private void standUp(Player p, Seat seat) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            seat.stand.remove();
            if (!p.isOnline()) return;
            Location back = seat.origin.clone();
            back.setYaw(p.getLocation().getYaw());
            back.setPitch(p.getLocation().getPitch());
            p.teleport(back);
        });
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Seat seat = seats.get(p.getUniqueId());
        if (seat == null || !e.getDismounted().equals(seat.stand)) return;
        seats.remove(p.getUniqueId());
        standUp(p, seat);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Seat s = seats.remove(e.getPlayer().getUniqueId());
        if (s != null) s.stand.remove();
        spinning.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ spin
    private void spin(Player by, Player target) {
        if (target.isInsideVehicle()) { Util.msg(by, "&c" + target.getName() + " must stand up first (not sitting / riding)."); return; }
        if (!spinning.add(target.getUniqueId())) return; // already spinning
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
        Util.msg(target, "&dWheeeee!");
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!target.isOnline() || target.isInsideVehicle() || t++ >= 60) {
                    spinning.remove(target.getUniqueId());
                    cancel();
                    return;
                }
                Location l = target.getLocation();
                l.setYaw(l.getYaw() + 12f); // 60 ticks x 12 degrees = 2 full turns
                target.teleport(l);
                if (t % 6 == 0) target.getWorld().spawnParticle(Particle.CLOUD, l, 3, 0.3, 0.05, 0.3, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------ pair emotes
    /** Returns the target player when the emote worked, otherwise null. */
    private Player pair(Player p, String[] args, String cmdName, String verb, String color,
                        Particle particle, Sound sound, float pitch) {
        if (args.length == 0) { Util.msg(p, "&cUsage: /" + cmdName + " <player>"); return null; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) { Util.msg(p, "&cPlayer not found."); return null; }
        if (t.equals(p)) { Util.msg(p, "&cPick another player."); return null; }
        if (!t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > EMOTE_RANGE) {
            Util.msg(p, "&cYou must be within " + (int) EMOTE_RANGE + " blocks of &e" + t.getName() + "&c.");
            return null;
        }
        Location mid = p.getLocation().add(t.getLocation()).multiply(0.5).add(0, 1.6, 0);
        p.getWorld().spawnParticle(particle, mid, 12, 0.35, 0.3, 0.35, 0.02);
        p.getWorld().playSound(mid, sound, 1f, pitch);
        p.swingMainHand();
        t.swingMainHand();
        Util.msg(p, color + "You " + verb + " " + t.getName() + "!");
        Util.msg(t, color + p.getName() + " " + verb + " you!");
        return t;
    }

    /** Big "DAP!" popup + slap sound for both players. */
    private void dapPopup(Player p, Player t) {
        Title title = Title.title(Util.c("&6&lDAP!"),
                Util.c("&e" + p.getName() + " &8\u270B &e" + t.getName()),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(300)));
        for (Player x : new Player[]{p, t}) {
            x.showTitle(title);
            x.playSound(x.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1.2f);
            x.playSound(x.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.5f);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && !cmd.getName().equalsIgnoreCase("sit")) return Util.match(Util.onlineNames(), args[0]);
        return List.of();
    }
}
