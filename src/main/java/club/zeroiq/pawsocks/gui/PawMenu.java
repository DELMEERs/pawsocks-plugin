package club.zeroiq.pawsocks.gui;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.feature.Trail;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import club.zeroiq.pawsocks.util.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** Главное меню /paw: профиль, следы лапок, носки, переноска, уют, обнимашки. */
public final class PawMenu extends Menu {

    private final PawsocksPlugin plugin;

    public PawMenu(PawsocksPlugin plugin) {
        super(3, plugin.messages().get("menu.title"));
        this.plugin = plugin;
    }

    @Override
    protected void render(Player p) {
        Messages m = plugin.messages();
        fill();

        // профиль
        ItemStack head = Items.icon(Material.PLAYER_HEAD,
                m.item("menu.profile.name", Placeholder.unparsed("player", p.getName())),
                m.lore("menu.profile.lore",
                        Placeholder.unparsed("cozy", String.valueOf(PlayerData.cozy(p))),
                        Placeholder.unparsed("hugs", String.valueOf(PlayerData.hugs(p))),
                        Placeholder.unparsed("hours", String.valueOf(p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600))));
        SkullMeta skull = (SkullMeta) head.getItemMeta();
        skull.setPlayerProfile(p.getPlayerProfile());
        head.setItemMeta(skull);
        set(4, head, null);

        // следы лапок
        Trail current = Trail.byId(PlayerData.trail(p));
        Component currentName = current == null
                ? m.get("menu.trail-off")
                : Component.text(m.raw("trails.names." + current.id));
        set(10, Items.icon(current == null ? Material.BONE : current.icon,
                        m.item("menu.trails.name"),
                        m.lore("menu.trails.lore", Placeholder.component("current", currentName))),
                viewer -> {
                    click(viewer);
                    new TrailsMenu(plugin).open(viewer);
                });

        // носки — показываем настоящие носки как иконку
        ItemStack socks = plugin.socks().build(DyeColor.PINK, DyeColor.WHITE);
        var socksMeta = socks.getItemMeta();
        socksMeta.displayName(m.item("menu.socks.name"));
        socksMeta.lore(m.lore("menu.socks.lore"));
        socksMeta.addItemFlags(ItemFlag.values());
        socks.setItemMeta(socksMeta);
        set(12, socks, null);

        // переноска
        ItemStack carrier = plugin.carrier().empty();
        var carrierMeta = carrier.getItemMeta();
        carrierMeta.displayName(m.item("menu.carrier.name"));
        carrierMeta.lore(m.lore("menu.carrier.lore"));
        carrier.setItemMeta(carrierMeta);
        set(14, carrier, null);

        // уют
        set(16, Items.icon(Material.CAMPFIRE, m.item("menu.cozy.name"),
                        m.lore("menu.cozy.lore", Placeholder.component("state", state(PlayerData.cozyBar(p))))),
                viewer -> {
                    PlayerData.cozyBar(viewer, !PlayerData.cozyBar(viewer));
                    click(viewer);
                    open(viewer);
                });

        // обнимашки
        set(22, Items.icon(Material.PINK_WOOL, m.item("menu.hugs.name"),
                        m.lore("menu.hugs.lore", Placeholder.component("state", state(PlayerData.hugsAllowed(p))))),
                viewer -> {
                    PlayerData.hugsAllowed(viewer, !PlayerData.hugsAllowed(viewer));
                    click(viewer);
                    open(viewer);
                });
    }

    private Component state(boolean on) {
        return plugin.messages().get(on ? "menu.on" : "menu.off");
    }

    private void fill() {
        ItemStack pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), java.util.List.of());
        for (int i = 0; i < getInventory().getSize(); i++) {
            set(i, pane, null);
        }
    }

    static void click(Player p) {
        Fx.sound(p.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
    }
}
