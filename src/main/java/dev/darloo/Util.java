package dev.darloo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class Util {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Util() {}

    /** Turns "&aHello" into a coloured Component. */
    public static Component c(String s) { return LEGACY.deserialize(s); }

    public static void msg(CommandSender to, String s) { to.sendMessage(c(Main.PREFIX + s)); }

    public static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack tag(ItemStack it, String key, String value) {
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(Main.get(), key), PersistentDataType.STRING, value);
        it.setItemMeta(meta);
        return it;
    }

    public static String getTag(ItemStack it, String key) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(Main.get(), key), PersistentDataType.STRING);
    }

    /** True for the gold axe (area) and golden shovel (claim). */
    public static boolean isTool(ItemStack it) { return getTag(it, "tool") != null; }

    public static ItemStack filler(Material m) { return item(m, " "); }

    public static List<String> match(Collection<String> options, String typed) {
        String t = typed.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(t)) out.add(o);
        return out;
    }

    public static List<String> onlineNames() {
        List<String> n = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName());
        return n;
    }

    public static OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    /** Gives an item; anything that does not fit is dropped at the player's feet. */
    public static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }
}
