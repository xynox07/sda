package dev.ultimate;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
    public static final String PREFIX = "&8[&bUltimate&8] &7";
    private static Main instance;

    public static Main get() { return instance; }

    public WarpManager warps;
    public AreaManager areas;
    public ClaimManager claims;
    public KitManager kits;
    public ArenaManager arenas;
    public TradeManager trades;
    public EmoteManager emotes;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        warps = new WarpManager(this);
        areas = new AreaManager(this);
        claims = new ClaimManager(this, areas);
        kits = new KitManager(this);
        arenas = new ArenaManager(this, kits);
        kits.setArenas(arenas);
        trades = new TradeManager(this, arenas);
        emotes = new EmoteManager(this);

        bind(trades, "trade");
        bind(areas, "area");
        bind(warps, "warp");
        bind(claims, "claim");
        for (String c : new String[]{"sit", "spin", "dap", "hug", "wave", "highfive"}) bind(emotes, c);
        bind(arenas, "arena");
        bind(kits, "kit");
        getLogger().info("UltimateCore enabled.");
    }

    @Override
    public void onDisable() {
        if (trades != null) trades.shutdown();
        if (arenas != null) arenas.shutdown();
        if (emotes != null) emotes.shutdown();
        if (claims != null) claims.save();
    }

    private void bind(TabExecutor ex, String name) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor(ex);
        c.setTabCompleter(ex);
    }
}
