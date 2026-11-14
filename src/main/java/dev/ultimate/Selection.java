package dev.ultimate;

import org.bukkit.Location;

public final class Selection {
    public Location p1, p2;

    public Region region() {
        if (p1 == null || p2 == null || p1.getWorld() == null || !p1.getWorld().equals(p2.getWorld())) return null;
        return new Region(p1.getWorld().getName(), p1.getBlockX(), p1.getBlockZ(), p2.getBlockX(), p2.getBlockZ());
    }
}
