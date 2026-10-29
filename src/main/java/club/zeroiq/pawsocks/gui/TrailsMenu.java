package club.zeroiq.pawsocks.gui;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.feature.Trail;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import club.zeroiq.pawsocks.util.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Выбор цвета следов лапок. */
public final class TrailsMenu extends Menu {

    /** Слоты под цвета: два ряда по центру. */
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private final PawsocksPlugin plugin;

    public TrailsMenu(PawsocksPlugin plugin) {
        super(4, plugin.messages().get("menu.trails-title"));
        this.plugin = plugin;
    }

    @Override
    protected void render(Player p) {
        Messages m = plugin.messages();
        ItemStack pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int i = 0; i < getInventory().getSize(); i++) {
            set(i, pane, null);
        }

        Trail current = Trail.byId(PlayerData.trail(p));
        Trail[] all = Trail.values();
        for (int i = 0; i < all.length && i < SLOTS.length; i++) {
            Trail t = all[i];
            boolean allowed = plugin.trails().canUse(p, t);
            List<Component> lore = new ArrayList<>();
            if (t == current) {
                lore.add(m.item("menu.trail-selected"));
            } else if (allowed) {
                lore.add(m.item("menu.trail-click"));
            } else {
                lore.add(m.item("menu.trail-locked"));
            }
            ItemStack icon = Items.icon(allowed ? t.icon : Material.GRAY_DYE,
                    m.item("menu.trail-name", Placeholder.unparsed("name", m.raw("trails.names." + t.id))), lore);
            if (t == current) Items.glow(icon);
            set(SLOTS[i], icon, viewer -> {
                PawMenu.click(viewer);
                if (!plugin.trails().canUse(viewer, t)) {
                    m.send(viewer, "trails.locked");
                    return;
                }
                PlayerData.trail(viewer, t.id);
                m.send(viewer, "trails.selected",
                        Placeholder.unparsed("name", m.raw("trails.names." + t.id)));
                open(viewer);
            });
        }

        set(30, Items.icon(Material.ARROW, m.item("menu.back"), List.of()), viewer -> {
            PawMenu.click(viewer);
            new PawMenu(plugin).open(viewer);
        });
        set(32, Items.icon(Material.BARRIER, m.item("menu.trail-off"), List.of()), viewer -> {
            PawMenu.click(viewer);
            PlayerData.trail(viewer, null);
            m.send(viewer, "trails.off");
            open(viewer);
        });
    }
}
