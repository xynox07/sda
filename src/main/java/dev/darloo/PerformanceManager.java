package dev.darloo;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;

import java.util.*;

/**
 * Lag tools.
 *  /clearlag or /boost  (admin) - remove old dropped items, xp orbs, stuck arrows and extra mobs
 *  /lag (or /tps)               - TPS, ms per tick (MSPT), memory, entities, your ping
 *  /ping [player]               - your connection delay in ms
 * Automatic: clears every N minutes (with warnings) and runs a "boost" cleanup by itself when TPS is low.
 * NOTE: nothing in a plugin can lower a player's internet ping - /boost makes the SERVER faster (less lag).
 */
public final class PerformanceManager implements TabExecutor {
    private record Result(int items, int orbs, int arrows, int mobs) {
        int total() { return items + orbs + arrows + mobs; }
    }

    private final Main plugin;
    private int countdown;
    private int seconds;
    private int lowStreak;

    public PerformanceManager(Main plugin) {
        this.plugin = plugin;
        this.countdown = interval();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private int interval() { return plugin.getConfig().getInt("lag.auto-clear-minutes", 10) * 60; }

    // ------------------------------------------------------------ cleaning
    private Result clean() {
        int items = 0, orbs = 0, arrows = 0, mobs = 0;
        int cap = Math.max(5, plugin.getConfig().getInt("lag.max-mobs-per-chunk", 50));
        for (World w : Bukkit.getWorlds()) {
            for (Item it : w.getEntitiesByClass(Item.class)) {
                if (it.getTicksLived() < 100) continue; // never delete fresh drops
                it.remove();
                items++;
            }
            for (ExperienceOrb o : w.getEntitiesByClass(ExperienceOrb.class)) { o.remove(); orbs++; }
            for (AbstractArrow a : w.getEntitiesByClass(AbstractArrow.class)) {
                if (a.isInBlock()) { a.remove(); arrows++; }
            }
            for (Chunk c : w.getLoadedChunks()) {
                int count = 0;
                for (Entity en : c.getEntities()) {
                    if (!(en instanceof LivingEntity le) || en instanceof Player || en instanceof ArmorStand
                            || en instanceof Villager || en instanceof Boss) continue;
                    if (le.customName() != null || le.isLeashed() || le.isPersistent()) continue;
                    if (en instanceof Tameable t && t.isTamed()) continue;
                    if (++count > cap) { le.remove(); mobs++; }
                }
            }
        }
        return new Result(items, orbs, arrows, mobs);
    }

    private void tick() {
        seconds++;
        int interval = interval();
        if (interval > 0) {
            countdown--;
            if (countdown == 60 || countdown == 30 || countdown == 10 || (countdown <= 5 && countdown >= 1)) {
                Bukkit.broadcast(Util.c(Main.PREFIX + "&eDropped items will be cleared in &c" + countdown + " &eseconds!"));
            }
            if (countdown <= 0) {
                Result r = clean();
                Bukkit.broadcast(Util.c(Main.PREFIX + "&aCleared &e" + r.items() + " &aitems, &e" + r.mobs() + " &aextra mobs. Less lag!"));
                countdown = interval;
            }
        }
        if (seconds % 10 == 0) { // automatic boost when the server is struggling
            double limit = plugin.getConfig().getDouble("lag.boost-below-tps", 17.0);
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            if (limit > 0 && tps < limit) {
                if (++lowStreak >= 3) {
                    Result r = clean();
                    Bukkit.broadcast(Util.c(Main.PREFIX + "&6Auto boost: TPS was low (" + String.format("%.1f", tps)
                            + "), cleaned &e" + r.total() + " &6entities."));
                    lowStreak = 0;
                }
            } else {
                lowStreak = 0;
            }
        }
    }

    // ------------------------------------------------------------ commands
    private String tpsColor(double tps) { return tps >= 18 ? "&a" : tps >= 15 ? "&e" : "&c"; }

    private String pingColor(int ms) { return ms < 80 ? "&a" : ms < 150 ? "&e" : "&c"; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "clearlag", "boost" -> {
                if (!sender.hasPermission("darloo.admin")) { Util.msg(sender, "&cYou do not have permission."); return true; }
                double before = Math.min(20.0, Bukkit.getTPS()[0]);
                Result r = clean();
                countdown = interval();
                Bukkit.broadcast(Util.c(Main.PREFIX + "&aServer boost by &e" + sender.getName() + "&a: removed &e" + r.items()
                        + " &aitems, &e" + r.orbs() + " &axp orbs, &e" + r.arrows() + " &aarrows, &e" + r.mobs() + " &aextra mobs."));
                Util.msg(sender, "TPS before: " + tpsColor(before) + String.format("%.1f", before));
            }
            case "lag" -> {
                double[] t = Bukkit.getTPS();
                double mspt = Bukkit.getAverageTickTime();
                Runtime rt = Runtime.getRuntime();
                long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L, max = rt.maxMemory() / 1048576L;
                int ents = 0, chunks = 0;
                for (World w : Bukkit.getWorlds()) { ents += w.getEntities().size(); chunks += w.getLoadedChunks().length; }
                Util.msg(sender, "&6--- Server performance ---");
                Util.msg(sender, "TPS (1m/5m/15m): " + tpsColor(t[0]) + String.format("%.1f", Math.min(20.0, t[0])) + "&7 / "
                        + String.format("%.1f", Math.min(20.0, t[1])) + " / " + String.format("%.1f", Math.min(20.0, t[2])));
                Util.msg(sender, "Tick time (MSPT): " + (mspt < 40 ? "&a" : mspt < 50 ? "&e" : "&c") + String.format("%.1f", mspt) + " ms &7(must stay under 50)");
                Util.msg(sender, "Memory: &e" + used + " / " + max + " MB &7| Entities: &e" + ents + " &7| Loaded chunks: &e" + chunks);
                if (sender instanceof Player p) Util.msg(sender, "Your ping: " + pingColor(p.getPing()) + p.getPing() + " ms");
            }
            case "ping" -> {
                Player target = null;
                if (args.length > 0) target = Bukkit.getPlayerExact(args[0]);
                else if (sender instanceof Player p) target = p;
                if (target == null) { Util.msg(sender, "&cUsage: /ping [player]"); return true; }
                Util.msg(sender, "&e" + target.getName() + "&7 ping: " + pingColor(target.getPing()) + target.getPing() + " ms");
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && cmd.getName().equalsIgnoreCase("ping")) return Util.match(Util.onlineNames(), args[0]);
        return List.of();
    }
}
