package dev.darloo;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/** Tiny wrapper around a yml data file. */
public final class Store {
    private final File file;
    public final YamlConfiguration cfg;

    public Store(Main plugin, String name) {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), name);
        cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            Main.get().getLogger().warning("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }
}
