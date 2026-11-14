package dev.ultimate;

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

import java.util.*;

/** /sit, /spin [player], /dap <player>, /hug <player>, /wave <player>, /highfive <player> */
public final class EmoteManager implements TabExecutor, Listener {
    private static final double SIT_OFFSET = -0.35; // change if the seat looks too high / low
    private static final double EMOTE_RANGE = 8.0;

    private static final class Seat {
        final ArmorStand stand;
        final Location origin;

        Seat(ArmorStand stand, Location origin) { this.stand = stand; this.origin = origin; }
    }

    private final Main plugin;
    private final Map<UUID, Seat> seats = new HashMap<>();

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
        switch (cmd.getName().toLowerCase()) {
            case "sit" -> sit(p);
            case "spin" -> {
                Player target = p;
                if (args.length > 0) {
                    if (!p.hasPermission("ultimate.emote.others")) { Util.msg(p, "&cYou cannot spin other players."); return true; }
                    target = Bukkit.getPlayerExact(args[0]);
                    if (target == null) { Util.msg(p, "&cPlayer not found."); return true; }
                }
                spin(target);
            }
            case "dap" -> pair(p, args, "dapped up", "&6", Particle.CRIT, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f);
            case "hug" -> pair(p, args, "hugged", "&d", Particle.HEART, Sound.ENTITY_VILLAGER_YES, 1f);
            case "wave" -> pair(p, args, "waved at", "&b", Particle.NOTE, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f);
            case "highfive" -> pair(p, args, "high-fived", "&a", Particle.CLOUD, Sound.ENTITY_PLAYER_LEVELUP, 1.5f);
            default -> { }
        }
        return true;
    }

    private void sit(Player p) {
        Seat old = seats.remove(p.getUniqueId());
        if (old != null) { // /sit again = stand up
            old.stand.remove();
            p.teleport(old.origin);
            return;
        }
        if (!p.isOnGround() || p.isInsideVehicle()) { Util.msg(p, "&cYou can only sit while standing on the ground."); return; }
        Location origin = p.getLocation();
        ArmorStand st = p.getWorld().spawn(origin.clone().add(0, SIT_OFFSET, 0), ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setSmall(true);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
        });
        st.addPassenger(p);
        seats.put(p.getUniqueId(), new Seat(st, origin));
        p.sendActionBar(Util.c("&7Sneak (shift) or type &e/sit &7to stand up"));
    }

    private void spin(Player target) {
        Util.msg(target, "&dWheeeee!");
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!target.isOnline() || t++ >= 40) { cancel(); return; }
                Location l = target.getLocation();
                target.setRotation(l.getYaw() + 18f, l.getPitch());
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void pair(Player p, String[] args, String verb, String color, Particle particle, Sound sound, float pitch) {
        if (args.length == 0) { Util.msg(p, "&cUsage: /" + verb.split(" ")[0] + " <player>  (type the player name)"); return; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) { Util.msg(p, "&cPlayer not found."); return; }
        if (t.equals(p)) { Util.msg(p, "&cPick another player."); return; }
        if (!t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > EMOTE_RANGE) {
            Util.msg(p, "&cYou must be within " + (int) EMOTE_RANGE + " blocks of &e" + t.getName() + "&c."); return;
        }
        Location mid = p.getLocation().add(t.getLocation()).multiply(0.5).add(0, 1.6, 0);
        p.getWorld().spawnParticle(particle, mid, 12, 0.35, 0.3, 0.35, 0.02);
        p.getWorld().playSound(mid, sound, 1f, pitch);
        p.swingMainHand();
        t.swingMainHand();
        Util.msg(p, color + "You " + verb + " " + t.getName() + "!");
        Util.msg(t, color + p.getName() + " " + verb + " you!");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && !cmd.getName().equalsIgnoreCase("sit")) return Util.match(Util.onlineNames(), args[0]);
        return List.of();
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Seat seat = seats.get(p.getUniqueId());
        if (seat == null || !e.getDismounted().equals(seat.stand)) return;
        seats.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            seat.stand.remove();
            if (p.isOnline()) p.teleport(seat.origin);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Seat s = seats.remove(e.getPlayer().getUniqueId());
        if (s != null) s.stand.remove();
    }
}
