package club.zeroiq.pawsocks;

import club.zeroiq.pawsocks.feature.CarrierFeature;
import club.zeroiq.pawsocks.feature.CozyFeature;
import club.zeroiq.pawsocks.feature.HugFeature;
import club.zeroiq.pawsocks.feature.SocksFeature;
import club.zeroiq.pawsocks.feature.TrailFeature;
import club.zeroiq.pawsocks.gui.MenuListener;
import club.zeroiq.pawsocks.gui.PawMenu;
import club.zeroiq.pawsocks.util.Messages;
import club.zeroiq.pawsocks.util.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class PawsocksPlugin extends JavaPlugin {

    private Messages messages;
    private SocksFeature socks;
    private CozyFeature cozy;
    private HugFeature hugs;
    private TrailFeature trails;
    private CarrierFeature carrier;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        PlayerData.init(this);
        messages = new Messages(this);
        messages.load();

        socks = new SocksFeature(this);
        cozy = new CozyFeature(this);
        hugs = new HugFeature(this);
        trails = new TrailFeature(this);
        carrier = new CarrierFeature(this);

        socks.registerRecipe();
        carrier.registerRecipe();
        for (Listener l : List.of(new MenuListener(), socks, cozy, hugs, trails, carrier)) {
            Bukkit.getPluginManager().registerEvents(l, this);
        }
        cozy.start();

        PluginCommand hug = getCommand("hug");
        if (hug != null) {
            hug.setExecutor(hugs);
            hug.setTabCompleter(hugs);
        }

        // рецепты для тех, кто уже на сервере (после /reload или перезапуска плагина)
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipe(new org.bukkit.NamespacedKey(this, "socks"));
            p.discoverRecipe(new org.bukkit.NamespacedKey(this, "carrier"));
        }
        getLogger().info("pawsocks включён: носки, уют, обнимашки, следы лапок, переноска, /paw");
    }

    @Override
    public void onDisable() {
        if (cozy != null) cozy.stop();
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
