package club.zeroiq.pawsocks;

import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.economy.Auction;
import club.zeroiq.pawsocks.economy.AuctionFeature;
import club.zeroiq.pawsocks.economy.Economy;
import club.zeroiq.pawsocks.economy.EconomyCommands;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.economy.Prices;
import club.zeroiq.pawsocks.economy.SellFeature;
import club.zeroiq.pawsocks.feature.CarrierFeature;
import club.zeroiq.pawsocks.feature.CozyFeature;
import club.zeroiq.pawsocks.feature.HugFeature;
import club.zeroiq.pawsocks.feature.SocksFeature;
import club.zeroiq.pawsocks.feature.TrailFeature;
import club.zeroiq.pawsocks.gui.MenuListener;
import club.zeroiq.pawsocks.gui.PawMenu;
import club.zeroiq.pawsocks.homes.HomesFeature;
import club.zeroiq.pawsocks.util.Messages;
import club.zeroiq.pawsocks.util.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

public final class PawsocksPlugin extends JavaPlugin {

    private Messages messages;
    private Database database;
    private Economy economy;
    private Prices prices;
    private Auction auction;

    private SocksFeature socks;
    private CozyFeature cozy;
    private HugFeature hugs;
    private TrailFeature trails;
    private CarrierFeature carrier;
    private SellFeature sell;
    private AuctionFeature auctionFeature;
    private HomesFeature homes;
    private Runnable worthOff;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        PlayerData.init(this);
        messages = new Messages(this);
        messages.load();

        database = new Database(getLogger());
        try {
            database.open(new File(getDataFolder(), "data.db"));
        } catch (Exception e) {
            getLogger().severe("не удалось открыть базу data.db, плагин выключается: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        long start = Math.max(0, Money.parse(getConfig().getString("economy.start-balance", "0")));
        economy = new Economy(database, start);
        prices = new Prices(this, database);
        prices.load();
        auction = new Auction(database);

        socks = new SocksFeature(this);
        cozy = new CozyFeature(this);
        hugs = new HugFeature(this);
        trails = new TrailFeature(this);
        carrier = new CarrierFeature(this);
        sell = new SellFeature(this);
        auctionFeature = new AuctionFeature(this);
        homes = new HomesFeature(this, database);
        EconomyCommands ecoCommands = new EconomyCommands(this);

        socks.registerRecipe();
        carrier.registerRecipe();
        for (Listener l : List.of(new MenuListener(this), ecoCommands, socks, cozy, hugs, trails, carrier, sell, auctionFeature, homes)) {
            Bukkit.getPluginManager().registerEvents(l, this);
        }
        cozy.start();
        auctionFeature.start();

        command("hug", hugs, hugs);
        command("sell", sell, sell);
        command("ah", auctionFeature, auctionFeature);
        for (String c : List.of("balance", "pay", "baltop", "eco")) command(c, ecoCommands, ecoCommands);
        for (String c : List.of("sethome", "home", "delhome", "homes")) command(c, homes, homes);

        // цена при наведении — только если на сервере есть packetevents
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                worthOff = club.zeroiq.pawsocks.economy.WorthHook.enable(this);
                getLogger().info("цена при наведении включена (packetevents)");
            } catch (Throwable t) {
                getLogger().warning("не удалось подключиться к packetevents, цены при наведении не будет: " + t);
            }
        } else {
            getLogger().warning("плагин packetevents не найден — цены при наведении на предмет не будет. всё остальное работает");
        }

        // для тех, кто уже на сервере (после перезапуска плагина)
        for (Player p : Bukkit.getOnlinePlayers()) {
            economy.touch(p.getUniqueId(), p.getName());
            p.discoverRecipe(new NamespacedKey(this, "socks"));
            p.discoverRecipe(new NamespacedKey(this, "carrier"));
        }
        getLogger().info("pawsocks включён: носки, уют, обнимашки, следы лапок, переноска, экономика, аукцион, дома, /paw");
    }

    private void command(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("команда /" + name + " не описана в plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(completer);
    }

    @Override
    public void onDisable() {
        if (sell != null) sell.closeAll();
        if (worthOff != null) {
            try {
                worthOff.run();
            } catch (Throwable ignored) {
                // packetevents мог выключиться раньше нас
            }
        }
        if (auctionFeature != null) auctionFeature.stop();
        if (cozy != null) cozy.stop();
        if (database != null) database.close();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        switch (command.getName().toLowerCase()) {
            case "paw" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "players-only");
                    return true;
                }
                new PawMenu(this).open(player);
                return true;
            }
            case "pawsocks" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    messages.load();
                    prices.load();
                    socks.registerRecipe();
                    carrier.registerRecipe();
                    messages.send(sender, "reloaded");
                    return true;
                }
                sender.sendMessage("/pawsocks reload");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("pawsocks") && args.length == 1) {
            return List.of("reload");
        }
        return List.of();
    }

    public Messages messages() {
        return messages;
    }

    public Economy economy() {
        return economy;
    }

    public Prices prices() {
        return prices;
    }

    public Auction auction() {
        return auction;
    }

    public SocksFeature socks() {
        return socks;
    }

    public CozyFeature cozy() {
        return cozy;
    }

    public TrailFeature trails() {
        return trails;
    }

    public CarrierFeature carrier() {
        return carrier;
    }
}
