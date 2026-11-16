package dev.darloo;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/** A rectangle on the X/Z plane that covers the FULL height of the world (up and down). */
public final class Region {
    public final String world;
    public final int minX, maxX, minZ, maxZ;

    public Region(String world, int x1, int z1, int x2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(Location l) {
        return l.getWorld() != null && l.getWorld().getName().equals(world)
                && l.getBlockX() >= minX && l.getBlockX() <= maxX
                && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
    }

    public boolean overlaps(Region o) {
        return world.equals(o.world) && minX <= o.maxX && maxX >= o.minX && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public long size() { return (long) width() * depth(); }

    public String describe() { return minX + ", " + minZ + "  to  " + maxX + ", " + maxZ + " (" + world + ")"; }

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("minX", minX);
        s.set("maxX", maxX);
        s.set("minZ", minZ);
        s.set("maxZ", maxZ);
    }

    public static Region load(ConfigurationSection s) {
        return new Region(s.getString("world"), s.getInt("minX"), s.getInt("minZ"), s.getInt("maxX"), s.getInt("maxZ"));
    }
}
