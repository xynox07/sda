package dev.darloo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * /trade <player>  -> request, /trade accept, /trade deny
 * Left side of the window = player 1, right side = player 2.
 * Green glass = OK (accept), red glass = NOT OK (cancel).
 * Changing ANY item resets both confirmations (scam protection).
 */
public final class TradeManager implements TabExecutor, Listener {
    private static final int[] A_SLOTS = ownSlots(0);
    private static final int[] B_SLOTS = ownSlots(5);
    private static final long REQUEST_MS = 30_000L;

    private static int[] ownSlots(int startCol) {
        int[] r = new int[20];
        int i = 0;
        for (int row = 0; row < 5; row++) for (int col = 0; col < 4; col++) r[i++] = row * 9 + startCol + col;
        return r;
    }

    private static boolean isOwn(int slot, boolean isA) {
        int row = slot / 9, col = slot % 9;
        return row <= 4 && (isA ? col <= 3 : col >= 5);
    }

    static final class Session {
        final Player a, b;
        Inventory inv;
        boolean readyA, readyB, done;

        Session(Player a, Player b) { this.a = a; this.b = b; }
    }

    static final class Holder implements InventoryHolder {
        final Session s;
        Inventory inv;

        Holder(Session s) { this.s = s; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Main plugin;
    private final ArenaManager arenas;
    private final Map<UUID, UUID> requests = new HashMap<>();   // target -> sender
    private final Map<UUID, Long> requestTime = new HashMap<>(); // target -> time
    private final Map<UUID, Session> sessions = new HashMap<>();

    public TradeManager(Main plugin, ArenaManager arenas) {
        this.plugin = plugin;
        this.arenas = arenas;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        for (Session s : new HashSet<>(sessions.values())) cancel(s, "&cServer is stopping.", null);
    }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Util.msg(sender, "&cPlayers only."); return true; }
        if (args.length == 0) { Util.msg(p, "&cUsage: /trade <player>"); return true; }

        String sub = args[0].toLowerCase();
        if (sub.equals("accept")) {
            UUID from = requests.get(p.getUniqueId());
            Long t = requestTime.get(p.getUniqueId());
            Player sender2 = from == null ? null : Bukkit.getPlayer(from);
            if (sender2 == null || t == null || System.currentTimeMillis() - t > REQUEST_MS) {
                Util.msg(p, "&cYou have no pending trade request."); return true;
            }
            requests.remove(p.getUniqueId());
            requestTime.remove(p.getUniqueId());
            begin(sender2, p);
            return true;
        }
        if (sub.equals("deny")) {
            UUID from = requests.remove(p.getUniqueId());
            requestTime.remove(p.getUniqueId());
            Player s2 = from == null ? null : Bukkit.getPlayer(from);
            Util.msg(p, "Trade request denied.");
            if (s2 != null) Util.msg(s2, "&e" + p.getName() + " &7denied your trade request.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { Util.msg(p, "&cPlayer not found."); return true; }
        if (target.equals(p)) { Util.msg(p, "&cYou cannot trade with yourself."); return true; }
        if (sessions.containsKey(p.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            Util.msg(p, "&cOne of you is already trading."); return true;
        }
        if (arenas.isInArena(p) || arenas.isInArena(target)) {
            Util.msg(p, "&cYou cannot trade while in the arena."); return true;
        }

        // if they already asked me, start immediately
        if (p.getUniqueId().equals(requests.get(target.getUniqueId())) == false
                && target.getUniqueId().equals(requests.get(p.getUniqueId()))) {
            requests.remove(p.getUniqueId());
            requestTime.remove(p.getUniqueId());
            begin(target, p);
            return true;
        }

        requests.put(target.getUniqueId(), p.getUniqueId());
        requestTime.put(target.getUniqueId(), System.currentTimeMillis());
        Util.msg(p, "Trade request sent to &e" + target.getName() + "&7. It expires in 30 seconds.");
        Component m = Util.c(Main.PREFIX + "&e" + p.getName() + " &7wants to trade with you. ")
                .append(Util.c("&a&l[ACCEPT]").clickEvent(ClickEvent.runCommand("/trade accept"))
                        .hoverEvent(HoverEvent.showText(Util.c("&7Click to accept"))))
                .append(Component.text(" "))
                .append(Util.c("&c&l[DENY]").clickEvent(ClickEvent.runCommand("/trade deny"))
                        .hoverEvent(HoverEvent.showText(Util.c("&7Click to deny"))));
        target.sendMessage(m);
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1) return List.of();
        List<String> o = new ArrayList<>(Util.onlineNames());
        o.addAll(List.of("accept", "deny"));
        return Util.match(o, args[0]);
    }

    // ------------------------------------------------------------ session
    private void begin(Player a, Player b) {
        if (sessions.containsKey(a.getUniqueId()) || sessions.containsKey(b.getUniqueId())) return;
        Session s = new Session(a, b);
        Holder h = new Holder(s);
        Inventory inv = Bukkit.createInventory(h, 54, Util.c("&8Trade: &0" + a.getName() + " &8<> &0" + b.getName()));
        h.inv = inv;
        s.inv = inv;
        layout(s);
        sessions.put(a.getUniqueId(), s);
        sessions.put(b.getUniqueId(), s);
        a.openInventory(inv);
        b.openInventory(inv);
        Util.msg(a, "Trading with &e" + b.getName() + "&7. Your items go on the &eLEFT &7side.");
        Util.msg(b, "Trading with &e" + a.getName() + "&7. Your items go on the &eRIGHT &7side.");
    }

    private ItemStack okBtn(boolean ready) {
        return ready
                ? Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lCONFIRMED", "&7Waiting for the other player...", "&7(change an item to un-confirm)")
                : Util.item(Material.GREEN_STAINED_GLASS_PANE, "&a&l✔ OK", "&7Click when you are happy with the deal");
    }

    private ItemStack noBtn() {
        return Util.item(Material.RED_STAINED_GLASS_PANE, "&c&l✘ NOT OK", "&7Click to cancel the trade");
    }

    private ItemStack status(boolean ready, Player p, String side) {
        return ready
                ? Util.item(Material.LIME_CONCRETE, "&a" + p.getName() + " is READY", "&7" + side + " side")
                : Util.item(Material.YELLOW_CONCRETE, "&e" + p.getName() + " is choosing...", "&7" + side + " side");
    }

    private void layout(Session s) {
        Inventory inv = s.inv;
        for (int r = 0; r < 6; r++) inv.setItem(r * 9 + 4, Util.filler(Material.BLACK_STAINED_GLASS_PANE));
        inv.setItem(48, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(50, Util.filler(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(45, okBtn(s.readyA));
        inv.setItem(46, noBtn());
        inv.setItem(47, status(s.readyA, s.a, "Left"));
        inv.setItem(52, okBtn(s.readyB));
        inv.setItem(53, noBtn());
        inv.setItem(51, status(s.readyB, s.b, "Right"));
    }

    private void unready(Session s) {
        s.readyA = false;
        s.readyB = false;
        Bukkit.getScheduler().runTask(plugin, () -> { if (!s.done) layout(s); });
    }

    private List<ItemStack> collect(Session s, boolean fromA) {
        List<ItemStack> out = new ArrayList<>();
        for (int slot : fromA ? A_SLOTS : B_SLOTS) {
            ItemStack it = s.inv.getItem(slot);
            if (it != null && it.getType() != Material.AIR) out.add(it.clone());
        }
        return out;
    }

    private void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
    }

    private void complete(Session s) {
        if (s.done) return;
        s.done = true;
        sessions.remove(s.a.getUniqueId());
        sessions.remove(s.b.getUniqueId());
        List<ItemStack> fromA = collect(s, true);
        List<ItemStack> fromB = collect(s, false);
        s.inv.clear();
        s.a.closeInventory();
        s.b.closeInventory();
        give(s.b, fromA);
        give(s.a, fromB);
        for (Player p : new Player[]{s.a, s.b}) {
            Util.msg(p, "&aTrade complete!");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
    }

    /** Cancels the trade and gives everybody their own items back. */
    private void cancel(Session s, String reason, Player skipClose) {
        if (s.done) return;
        s.done = true;
        sessions.remove(s.a.getUniqueId());
        sessions.remove(s.b.getUniqueId());
        List<ItemStack> fromA = collect(s, true);
        List<ItemStack> fromB = collect(s, false);
        s.inv.clear();
        for (Player p : new Player[]{s.a, s.b}) {
            if (p.isOnline() && !p.equals(skipClose)) p.closeInventory();
        }
        give(s.a, fromA);
        give(s.b, fromB);
        for (Player p : new Player[]{s.a, s.b}) {
            if (p.isOnline()) {
                Util.msg(p, "Trade cancelled. " + reason);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
        }
    }

    // ------------------------------------------------------------ events
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        Session s = h.s;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (s.done) { e.setCancelled(true); return; }
        boolean isA = p.getUniqueId().equals(s.a.getUniqueId());
        int raw = e.getRawSlot();
        InventoryAction act = e.getAction();

        if (act == InventoryAction.COLLECT_TO_CURSOR || act == InventoryAction.DROP_ALL_SLOT
                || act == InventoryAction.DROP_ONE_SLOT || act == InventoryAction.DROP_ALL_CURSOR
                || act == InventoryAction.DROP_ONE_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (raw < 0) { e.setCancelled(true); return; }

        // clicks in the player's own inventory (bottom)
        if (raw >= 54) {
            if (e.isShiftClick()) { // manual shift-click so items never land on the other side
                e.setCancelled(true);
                shiftIn(s, isA, e);
            }
            return;
        }

        // buttons
        if (raw == 45 || raw == 52 || raw == 46 || raw == 53) {
            e.setCancelled(true);
            boolean mine = isA ? (raw == 45 || raw == 46) : (raw == 52 || raw == 53);
            if (!mine) return;
            if (raw == 46 || raw == 53) { cancel(s, "&e" + p.getName() + " &7pressed NOT OK.", null); return; }
            if (isA) s.readyA = true; else s.readyB = true;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.5f);
            layout(s);
            if (s.readyA && s.readyB) complete(s);
            return;
        }

        // item slots: only your own side
        if (!isOwn(raw, isA)) { e.setCancelled(true); return; }
        unready(s);
    }

    private void shiftIn(Session s, boolean isA, InventoryClickEvent e) {
        ItemStack src = e.getCurrentItem();
        if (src == null || src.getType() == Material.AIR) return;
        ItemStack rest = src.clone();
        int[] slots = isA ? A_SLOTS : B_SLOTS;
        for (int slot : slots) { // merge into existing stacks
            ItemStack cur = s.inv.getItem(slot);
            if (cur != null && cur.isSimilar(rest) && cur.getAmount() < cur.getMaxStackSize()) {
                int move = Math.min(rest.getAmount(), cur.getMaxStackSize() - cur.getAmount());
                cur.setAmount(cur.getAmount() + move);
                rest.setAmount(rest.getAmount() - move);
                if (rest.getAmount() <= 0) break;
            }
        }
        if (rest.getAmount() > 0) {
            for (int slot : slots) {
                ItemStack cur = s.inv.getItem(slot);
                if (cur == null || cur.getType() == Material.AIR) {
                    s.inv.setItem(slot, rest);
                    rest = new ItemStack(Material.AIR);
                    break;
                }
            }
        }
        e.setCurrentItem(rest.getType() == Material.AIR || rest.getAmount() <= 0 ? null : rest);
        unready(s);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean isA = p.getUniqueId().equals(h.s.a.getUniqueId());
        for (int raw : e.getRawSlots()) {
            if (raw < 54 && !isOwn(raw, isA)) { e.setCancelled(true); return; }
        }
        unready(h.s);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        if (h.s.done) return;
        Player closer = (Player) e.getPlayer();
        cancel(h.s, "&e" + closer.getName() + " &7closed the trade window.", closer);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null) cancel(s, "&e" + p.getName() + " &7left the game.", p);
        requests.remove(p.getUniqueId());
    }
}
