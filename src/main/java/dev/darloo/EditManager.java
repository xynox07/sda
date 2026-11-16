package dev.darloo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small WorldEdit for the server owner / staff (permission darloo.edit). Type the commands with TWO slashes:
 *   //wand            wooden axe: left click = position 1, right click = position 2
 *   //pos1 //pos2     use your own position      //sel   clear the selection
 *   //expand vert     make the selection go from the bottom to the top of the world (up and down)
 *   //expand <n> <up|down>
 *   //set <pattern>   fill the selection         e.g.  //set stone   //set 50%stone,50%dirt   //set air
 *   //replace <from> <to>      e.g. //replace dirt grass_block     //replace stone,dirt air
 *   //walls <pattern>          //sphere <pattern> <radius>
 *   //copy  //paste (pastes where you stand)   //undo (last 5 actions)
 * Big jobs are done a little every tick, so the server does not freeze.
 */
public final class EditManager implements Listener {
    private static final class Sel {
        World world;
        int[] p1, p2;
    }

    private static final class Clip {
        int dx, dy, dz, ox, oy, oz;
        BlockData[] data;
    }

    private static final class Undo {
        final World world;
        final List<int[]> pos = new ArrayList<>();
        final List<BlockData> old = new ArrayList<>();

        Undo(World w) { world = w; }
    }

    private static final class Pattern {
        final List<BlockData> data = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        double total;

        BlockData pick() {
            if (data.size() == 1) return data.get(0);
            double r = ThreadLocalRandom.current().nextDouble() * total;
            for (int i = 0; i < data.size(); i++) {
                r -= weights.get(i);
                if (r <= 0) return data.get(i);
            }
            return data.get(data.size() - 1);
        }
    }

    @FunctionalInterface
    private interface Fn {
        /** Returns the new block data, or null to leave the block alone. */
        BlockData apply(Block b);
    }

    private final Main plugin;
    private final Map<UUID, Sel> sels = new HashMap<>();
    private final Map<UUID, Clip> clips = new HashMap<>();
    private final Map<UUID, Deque<Undo>> undos = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();

    public EditManager(Main plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private int maxBlocks() { return plugin.getConfig().getInt("edit.max-blocks", 200000); }

    private int batch() { return Math.max(1000, plugin.getConfig().getInt("edit.blocks-per-tick", 20000)); }

    // ------------------------------------------------------------ command interception ("//...")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage();
        if (!m.startsWith("//") || m.length() < 3) return;
        Player p = e.getPlayer();
        if (plugin.auth != null && !plugin.auth.isLoggedIn(p)) return; // the login system handles it
        e.setCancelled(true);
        if (!p.hasPermission("darloo.edit")) { Util.msg(p, "&cYou do not have permission."); return; }
        String[] parts = m.substring(2).trim().split("\\s+");
        handle(p, parts[0].toLowerCase(), Arrays.copyOfRange(parts, 1, parts.length));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!"edit".equals(Util.getTag(e.getItem(), "tool"))) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("darloo.edit")) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Block b = e.getClickedBlock();
        setPos(p, a == Action.LEFT_CLICK_BLOCK ? 1 : 2, b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        sels.remove(u);
        clips.remove(u);
        undos.remove(u);
        busy.remove(u);
    }

    // ------------------------------------------------------------ commands
    private void handle(Player p, String cmd, String[] a) {
        Sel s = sels.computeIfAbsent(p.getUniqueId(), k -> new Sel());
        switch (cmd) {
            case "wand" -> {
                ItemStack axe = Util.item(Material.WOODEN_AXE, "&dEdit Wand",
                        "&7Left click a block: &fposition 1", "&7Right click a block: &fposition 2");
                Util.give(p, Util.tag(axe, "tool", "edit"));
                Util.msg(p, "You got the &dEdit Wand&7. Select two corners, then use &e//set <block>");
            }
            case "pos1", "pos2" -> {
                Location l = p.getLocation();
                setPos(p, cmd.equals("pos1") ? 1 : 2, l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
            }
            case "sel", "desel", "clear" -> { s.p1 = null; s.p2 = null; s.world = null; Util.msg(p, "Selection cleared."); }
            case "expand" -> expand(p, s, a);
            case "set" -> {
                Pattern pt = pattern(p, a, 0);
                if (pt == null) return;
                int[] r = region(p, s);
                if (r == null) return;
                run(p, p.getWorld(), r, b -> pt.pick(), "set");
            }
            case "replace" -> {
                if (a.length < 2) { Util.msg(p, "&cUsage: //replace <from> <to>   e.g. //replace dirt grass_block"); return; }
                Set<Material> from = materials(p, a[0]);
                Pattern to = pattern(p, a, 1);
                if (from == null || to == null) return;
                int[] r = region(p, s);
                if (r == null) return;
                run(p, p.getWorld(), r, b -> from.contains(b.getType()) ? to.pick() : null, "replace");
            }
            case "walls" -> {
                Pattern pt = pattern(p, a, 0);
                if (pt == null) return;
                int[] r = region(p, s);
                if (r == null) return;
                run(p, p.getWorld(), r, b -> (b.getX() == r[0] || b.getX() == r[3] || b.getZ() == r[2] || b.getZ() == r[5]) ? pt.pick() : null, "walls");
            }
            case "sphere" -> sphere(p, a);
            case "copy" -> copy(p, s);
            case "paste" -> paste(p);
            case "undo" -> undo(p);
            default -> {
                Util.msg(p, "&6//wand &7| &6//pos1 //pos2 &7| &6//sel &7| &6//expand vert &7| &6//set <block> &7| &6//replace <from> <to>");
                Util.msg(p, "&6//walls <block> &7| &6//sphere <block> <radius> &7| &6//copy &7| &6//paste &7| &6//undo");
                Util.msg(p, "&7Patterns: &fstone &7| &f50%stone,50%dirt &7| &fair &7| &foak_stairs[facing=east]");
            }
        }
    }

    // ------------------------------------------------------------ selection
    private void setPos(Player p, int which, World w, int x, int y, int z) {
        Sel s = sels.computeIfAbsent(p.getUniqueId(), k -> new Sel());
        if (s.world != null && !s.world.equals(w)) { s.p1 = null; s.p2 = null; }
        s.world = w;
        int[] pos = {x, y, z};
        if (which == 1) s.p1 = pos; else s.p2 = pos;
        String size = "";
        if (s.p1 != null && s.p2 != null) {
            long n = (long) (Math.abs(s.p1[0] - s.p2[0]) + 1) * (Math.abs(s.p1[1] - s.p2[1]) + 1) * (Math.abs(s.p1[2] - s.p2[2]) + 1);
            size = " &7(" + n + " blocks)";
        }
        Util.msg(p, "Position " + which + ": &e" + x + ", " + y + ", " + z + size);
    }

    private void expand(Player p, Sel s, String[] a) {
        if (s.p1 == null || s.p2 == null || s.world == null) { Util.msg(p, "&cSelect two positions first (//wand)."); return; }
        int hi = s.p1[1] >= s.p2[1] ? 1 : 2; // which position is the upper one
        int[] up = hi == 1 ? s.p1 : s.p2, low = hi == 1 ? s.p2 : s.p1;
        if (a.length >= 1 && a[0].equalsIgnoreCase("vert")) {
            up[1] = s.world.getMaxHeight() - 1;
            low[1] = s.world.getMinHeight();
            Util.msg(p, "Selection now goes from the bottom to the top of the world (up and down).");
            return;
        }
        if (a.length >= 2) {
            try {
                int n = Integer.parseInt(a[0]);
                if (a[1].equalsIgnoreCase("up")) up[1] += n;
                else if (a[1].equalsIgnoreCase("down")) low[1] -= n;
                else { Util.msg(p, "&cUsage: //expand <n> <up|down>  or  //expand vert"); return; }
                Util.msg(p, "Selection expanded.");
            } catch (NumberFormatException ex) {
                Util.msg(p, "&cUsage: //expand <n> <up|down>  or  //expand vert");
            }
            return;
        }
        Util.msg(p, "&cUsage: //expand vert   or   //expand <n> <up|down>");
    }

    /** Returns {minX, minY, minZ, maxX, maxY, maxZ} or null (with a message) when the selection is not usable. */
    private int[] region(Player p, Sel s) {
        if (s.p1 == null || s.p2 == null || s.world == null) { Util.msg(p, "&cSelect two positions first (&e//wand&c)."); return null; }
        if (!s.world.equals(p.getWorld())) { Util.msg(p, "&cYour selection is in another world."); return null; }
        int minY = Math.max(s.world.getMinHeight(), Math.min(s.p1[1], s.p2[1]));
        int maxY = Math.min(s.world.getMaxHeight() - 1, Math.max(s.p1[1], s.p2[1]));
        int[] r = {Math.min(s.p1[0], s.p2[0]), minY, Math.min(s.p1[2], s.p2[2]),
                Math.max(s.p1[0], s.p2[0]), maxY, Math.max(s.p1[2], s.p2[2])};
        long size = (long) (r[3] - r[0] + 1) * (r[4] - r[1] + 1) * (r[5] - r[2] + 1);
        if (size > maxBlocks()) { Util.msg(p, "&cThat is " + size + " blocks. The limit is " + maxBlocks() + " (config edit.max-blocks)."); return null; }
        return r;
    }

    // ------------------------------------------------------------ patterns
    private List<String> splitTop(String s) { // split on commas that are not inside [ ]
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private Pattern pattern(Player p, String[] a, int index) {
        if (a.length <= index) { Util.msg(p, "&cGive a block, e.g. &e//set stone &cor &e//set 50%stone,50%dirt"); return null; }
        try {
            Pattern pt = new Pattern();
            for (String tok : splitTop(a[index])) {
                tok = tok.trim();
                if (tok.isEmpty()) continue;
                double w = 1;
                int pc = tok.indexOf('%');
                if (pc > 0) { w = Double.parseDouble(tok.substring(0, pc)); tok = tok.substring(pc + 1); }
                if (w <= 0) throw new IllegalArgumentException("weight");
                String name = tok.toLowerCase();
                if (!name.contains(":")) name = "minecraft:" + name;
                pt.data.add(Bukkit.createBlockData(name));
                pt.weights.add(w);
                pt.total += w;
            }
            if (pt.data.isEmpty()) throw new IllegalArgumentException("empty");
            return pt;
        } catch (IllegalArgumentException ex) {
            Util.msg(p, "&cUnknown block in &e" + a[index] + "&c. Use block names like stone, oak_planks, air.");
            return null;
        }
    }

    private Set<Material> materials(Player p, String s) {
        Set<Material> out = new HashSet<>();
        for (String tok : s.split(",")) {
            tok = tok.trim();
            if (tok.equals("*") || tok.equalsIgnoreCase("any")) { for (Material m : Material.values()) if (m.isBlock()) out.add(m); continue; }
            Material m = Material.matchMaterial(tok);
            if (m == null || !m.isBlock()) { Util.msg(p, "&cUnknown block: &e" + tok); return null; }
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ operations
    private void sphere(Player p, String[] a) {
        if (a.length < 2) { Util.msg(p, "&cUsage: //sphere <block> <radius>"); return; }
        Pattern pt = pattern(p, a, 0);
        if (pt == null) return;
        int rad;
        try { rad = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { Util.msg(p, "&cRadius must be a number."); return; }
        if (rad < 1 || (long) (2 * rad + 1) * (2 * rad + 1) * (2 * rad + 1) > maxBlocks()) { Util.msg(p, "&cRadius too big or too small."); return; }
        Location l = p.getLocation();
        int cx = l.getBlockX(), cy = l.getBlockY(), cz = l.getBlockZ();
        World w = p.getWorld();
        int[] r = {cx - rad, Math.max(w.getMinHeight(), cy - rad), cz - rad, cx + rad, Math.min(w.getMaxHeight() - 1, cy + rad), cz + rad};
        double lim = (rad + 0.5) * (rad + 0.5);
        run(p, w, r, b -> {
            double dx = b.getX() - cx, dy = b.getY() - cy, dz = b.getZ() - cz;
            return dx * dx + dy * dy + dz * dz <= lim ? pt.pick() : null;
        }, "sphere");
    }

    private void copy(Player p, Sel s) {
        int[] r = region(p, s);
        if (r == null) return;
        World w = p.getWorld();
        Clip c = new Clip();
        c.dx = r[3] - r[0] + 1; c.dy = r[4] - r[1] + 1; c.dz = r[5] - r[2] + 1;
        c.data = new BlockData[c.dx * c.dy * c.dz];
        for (int iy = 0; iy < c.dy; iy++)
            for (int iz = 0; iz < c.dz; iz++)
                for (int ix = 0; ix < c.dx; ix++)
                    c.data[ix + c.dx * (iz + c.dz * iy)] = w.getBlockAt(r[0] + ix, r[1] + iy, r[2] + iz).getBlockData();
        Location l = p.getLocation();
        c.ox = r[0] - l.getBlockX(); c.oy = r[1] - l.getBlockY(); c.oz = r[2] - l.getBlockZ();
        clips.put(p.getUniqueId(), c);
        Util.msg(p, "Copied &e" + c.data.length + " &7blocks. Walk somewhere and use &e//paste");
    }

    private void paste(Player p) {
        Clip c = clips.get(p.getUniqueId());
        if (c == null) { Util.msg(p, "&cNothing copied yet. Use //copy"); return; }
        Location l = p.getLocation();
        int minX = l.getBlockX() + c.ox, minY = l.getBlockY() + c.oy, minZ = l.getBlockZ() + c.oz;
        World w = p.getWorld();
        if (minY < w.getMinHeight() || minY + c.dy - 1 >= w.getMaxHeight()) { Util.msg(p, "&cThe paste would go outside the world height."); return; }
        int[] r = {minX, minY, minZ, minX + c.dx - 1, minY + c.dy - 1, minZ + c.dz - 1};
        run(p, w, r, b -> c.data[(b.getX() - minX) + c.dx * ((b.getZ() - minZ) + c.dz * (b.getY() - minY))], "paste");
    }

    /** Runs the change a few thousand blocks per tick and remembers the old blocks for //undo. */
    private void run(Player p, World w, int[] r, Fn fn, String what) {
        UUID u = p.getUniqueId();
        if (!busy.add(u)) { Util.msg(p, "&cWait until your last edit is finished."); return; }
        final int x1 = r[0], y1 = r[1], z1 = r[2];
        final int dx = r[3] - r[0] + 1, dy = r[4] - r[1] + 1, dz = r[5] - r[2] + 1;
        final long total = (long) dx * dy * dz;
        final int per = batch();
        final Undo undo = new Undo(w);
        final long start = System.currentTimeMillis();
        Util.msg(p, "Working on &e" + total + " &7blocks...");
        new BukkitRunnable() {
            long i = 0;
            int changed = 0;

            @Override
            public void run() {
                long end = Math.min(total, i + per);
                for (; i < end; i++) {
                    int x = x1 + (int) (i % dx);
                    int z = z1 + (int) ((i / dx) % dz);
                    int y = y1 + (int) (i / ((long) dx * dz));
                    Block b = w.getBlockAt(x, y, z);
                    BlockData nd = fn.apply(b);
                    if (nd == null) continue;
                    BlockData old = b.getBlockData();
                    if (old.equals(nd)) continue;
                    undo.pos.add(new int[]{x, y, z});
                    undo.old.add(old);
                    b.setBlockData(nd, false);
                    changed++;
                }
                if (i >= total) {
                    cancel();
                    busy.remove(u);
                    Deque<Undo> dq = undos.computeIfAbsent(u, k -> new ArrayDeque<>());
                    dq.addFirst(undo);
                    while (dq.size() > 5) dq.removeLast();
                    Player online = Bukkit.getPlayer(u);
                    if (online != null) Util.msg(online, "&a" + what + " done: &e" + changed + " &ablocks changed in "
                            + (System.currentTimeMillis() - start) + " ms. &7(//undo to revert)");
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void undo(Player p) {
        UUID u = p.getUniqueId();
        Deque<Undo> dq = undos.get(u);
        if (dq == null || dq.isEmpty()) { Util.msg(p, "&cNothing to undo."); return; }
        if (!busy.add(u)) { Util.msg(p, "&cWait until your last edit is finished."); return; }
        Undo un = dq.pollFirst();
        final int per = batch();
        new BukkitRunnable() {
            int i = un.pos.size() - 1;

            @Override
            public void run() {
                int n = 0;
                while (i >= 0 && n < per) {
                    int[] q = un.pos.get(i);
                    un.world.getBlockAt(q[0], q[1], q[2]).setBlockData(un.old.get(i), false);
                    i--;
                    n++;
                }
                if (i < 0) {
                    cancel();
                    busy.remove(u);
                    Player online = Bukkit.getPlayer(u);
                    if (online != null) Util.msg(online, "&aUndo done: &e" + un.pos.size() + " &ablocks restored.");
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
