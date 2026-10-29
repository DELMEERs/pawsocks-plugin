package club.zeroiq.pawsocks.gui;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.economy.Money;
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
        super(5, plugin.messages().get("menu.title"));
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
                        Placeholder.unparsed("hours", String.valueOf(p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600)),
                        Money.tag(m, "balance", plugin.economy().balance(p.getUniqueId())),
                        club.zeroiq.pawsocks.astra.AstraFeature.tag(m, "astra", plugin.astras().balance(p.getUniqueId()))));
        SkullMeta skull = (SkullMeta) head.getItemMeta();
        skull.setPlayerProfile(p.getPlayerProfile());
        head.setItemMeta(skull);
        set(4, head, null);

        // следы лапок
        Trail current = Trail.byId(PlayerData.trail(p));
        Component currentName = current == null
                ? m.get("menu.trail-off")
                : Component.text(m.raw("trails.names." + current.id));
        set(19, Items.icon(current == null ? Material.BONE : current.icon,
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
        set(21, socks, null);

        // переноска
        ItemStack carrier = plugin.carrier().empty();
        var carrierMeta = carrier.getItemMeta();
        carrierMeta.displayName(m.item("menu.carrier.name"));
        carrierMeta.lore(m.lore("menu.carrier.lore"));
        carrier.setItemMeta(carrierMeta);
        set(23, carrier, null);

        // уют
        set(25, Items.icon(Material.CAMPFIRE, m.item("menu.cozy.name"),
                        m.lore("menu.cozy.lore", Placeholder.component("state", state(PlayerData.cozyBar(p))))),
                viewer -> {
                    PlayerData.cozyBar(viewer, !PlayerData.cozyBar(viewer));
                    click(viewer);
                    open(viewer);
                });

        // обнимашки
        set(11, Items.icon(Material.PINK_WOOL, m.item("menu.hugs.name"),
                        m.lore("menu.hugs.lore", Placeholder.component("state", state(PlayerData.hugsAllowed(p))))),
                viewer -> {
                    PlayerData.hugsAllowed(viewer, !PlayerData.hugsAllowed(viewer));
                    click(viewer);
                    open(viewer);
                });
        // косметика и дома
        set(13, Items.icon(Material.AMETHYST_CLUSTER, m.item("menu.cosmetics.name"), m.lore("menu.cosmetics.lore")),
                viewer -> {
                    click(viewer);
                    plugin.cosmetics().open(viewer);
                });
        set(15, Items.icon(Material.RED_BED, m.item("menu.homes.name"), m.lore("menu.homes.lore")), command("homes"));

        // экономика
        set(28, Items.icon(Material.GOLD_INGOT, m.item("menu.auction.name"), m.lore("menu.auction.lore")), command("ah"));
        set(29, Items.icon(Material.HOPPER, m.item("menu.sell.name"), m.lore("menu.sell.lore")), command("sell"));
        set(30, Items.icon(Material.EMERALD, m.item("menu.shop.name"), m.lore("menu.shop.lore")), command("shop"));
        set(31, Items.icon(Material.WRITABLE_BOOK, m.item("menu.orders.name"), m.lore("menu.orders.lore")), command("order"));
        set(32, Items.icon(Material.ANVIL, m.item("menu.smith.name"), m.lore("menu.smith.lore")), command("smith"));
        set(34, plugin.astraItems().astraIcon(m.item("menu.astra.name"), m.lore("menu.astra.lore",
                club.zeroiq.pawsocks.astra.AstraFeature.tag(m, "astra", plugin.astras().balance(p.getUniqueId())))), command("astra"));
    }

    /** Кнопка, которая закрывает меню и выполняет команду от игрока. */
    private java.util.function.Consumer<Player> command(String cmd) {
        return viewer -> {
            click(viewer);
            viewer.closeInventory();
            viewer.performCommand(cmd);
        };
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
