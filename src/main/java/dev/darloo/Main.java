package dev.darloo;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
    public static final String PREFIX = "&8[&9Darloo&bPolice&8] &7";
    private static Main instance;

    public static Main get() { return instance; }

    public WarpManager warps;
    public AreaManager areas;
    public ClaimManager claims;
    public KitManager kits;
    public ArenaManager arenas;
    public TradeManager trades;
    public EmoteManager emotes;
    public PunishManager punish;
    public ShopManager shop;
    public WelcomeManager welcome;
    public TpaManager tpa;
    public PerformanceManager perf;
    public AuthManager auth;
    public EditManager edit;
    public MemeManager memes;
    public AnnouncementManager announce;
    public HomeManager homes;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true); // adds new settings to an old config.yml
        saveConfig();

        memes = new MemeManager(this);
        auth = new AuthManager(this);
        warps = new WarpManager(this);
        areas = new AreaManager(this);
        claims = new ClaimManager(this, areas);
        kits = new KitManager(this);
        arenas = new ArenaManager(this, kits);
        kits.setArenas(arenas);
        trades = new TradeManager(this, arenas);
        emotes = new EmoteManager(this);
        punish = new PunishManager(this, arenas);
        shop = new ShopManager(this);
        welcome = new WelcomeManager(this);
        tpa = new TpaManager(this, arenas, punish);
        perf = new PerformanceManager(this);
        edit = new EditManager(this);
        announce = new AnnouncementManager(this);
        homes = new HomeManager(this, arenas, punish);

        bind(trades, "trade");
        bind(areas, "area");
        bind(warps, "warp");
        bind(claims, "claim");
        for (String c : new String[]{"sit", "spin", "dap", "hug", "wave", "highfive"}) bind(emotes, c);
        bind(arenas, "arena");
        bind(kits, "kit");
        bind(punish, "punish");
        bind(punish, "unpunish");
        bind(punish, "jail");
        bind(shop, "shop");
        for (String c : new String[]{"tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel"}) bind(tpa, c);
        for (String c : new String[]{"clearlag", "boost", "lag", "ping"}) bind(perf, c);
        for (String c : new String[]{"register", "login", "changepassword", "authreset"}) bind(auth, c);
        bind(memes, "mems");
        bind(announce, "announcement");
        for (String c : new String[]{"sethome", "home", "delhome", "homes"}) bind(homes, c);
        getLogger().info("DarlooPolice enabled.");
    }

    @Override
    public void onDisable() {
        if (trades != null) trades.shutdown();
        if (arenas != null) arenas.shutdown();
        if (emotes != null) emotes.shutdown();
        if (punish != null) punish.shutdown();
        if (claims != null) claims.save();
    }

    private void bind(TabExecutor ex, String name) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor(ex);
        c.setTabCompleter(ex);
    }
}
