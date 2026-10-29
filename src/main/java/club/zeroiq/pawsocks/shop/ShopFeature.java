package club.zeroiq.pawsocks.shop;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /shop — сервер продаёт за лапки. Цены в shop.yml, всегда дороже скупки. */
public final class ShopFeature implements CommandExecutor {

    private record Category(String id, Component name, Material icon, Map<Material, Long> items) {
    }

    private final PawsocksPlugin plugin;
    private List<Category> categories = new ArrayList<>();

    public ShopFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) plugin.saveResource("shop.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        if (yml.getConfigurationSection("categories") == null) {
            try (InputStream in = plugin.getResource("shop.yml")) {
                if (in != null) yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // останется пустой магазин
            }
        }
        double markup = plugin.getConfig().getDouble("economy.shop.min-markup", 2.0);
        List<Category> next = new ArrayList<>();
        ConfigurationSection cats = yml.getConfigurationSection("categories");
        int raised = 0;
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                if (c == null) continue;
                Material icon = Material.matchMaterial(c.getString("icon", "chest"));
                Map<Material, Long> items = new LinkedHashMap<>();
                ConfigurationSection list = c.getConfigurationSection("items");
                if (list != null) {
                    for (String key : list.getKeys(false)) {
                        Material mat = Material.matchMaterial(key);
                        long price = Money.parse(list.getString(key, ""));
                        if (mat == null || !mat.isItem() || price <= 0) {
                            plugin.getLogger().warning("shop.yml: пропущено " + id + "." + key);
                            continue;
                        }
                        // защита от «купил — продал дороже»
                        long minPrice = (long) Math.ceil(plugin.prices().base(mat) * markup);
                        if (price < minPrice) {
                            price = minPrice;
                            raised++;
                        }
                        items.put(mat, price);
                    }
                }
                Component name = MiniMessage.miniMessage().deserialize(c.getString("name", id)).decoration(TextDecoration.ITALIC, false);
                next.add(new Category(id, name, icon == null ? Material.CHEST : icon, items));
            }
        }
        categories = next;
        if (raised > 0) plugin.getLogger().warning("shop.yml: у " + raised + " товаров цена была ниже скупки — поднята");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!plugin.getConfig().getBoolean("economy.shop.enabled", true)) {
            m().send(p, "no-permission");
            return true;
        }
        new Main().open(p);
        return true;
    }

    private static ItemStack pane() {
        return Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private final class Main extends Menu {
        Main() {
            super(3, plugin.messages().get("shop.title"));
        }

        @Override
        protected void render(Player p) {
            for (int i = 0; i < 27; i++) set(i, pane(), null);
            int[] slots = {10, 11, 12, 13, 14, 15, 16};
            for (int i = 0; i < categories.size() && i < slots.length; i++) {
                Category c = categories.get(i);
                set(slots[i], Items.icon(c.icon(), c.name(),
                        m().lore("shop.category-lore", Placeholder.unparsed("count", String.valueOf(c.items().size())))),
                        v -> new CategoryMenu(c).open(v));
            }
            set(22, Items.icon(Material.GOLD_INGOT, m().item("ah.balance.name"),
                    m().lore("ah.balance.lore", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())))), null);
        }
    }

    private final class CategoryMenu extends Menu {
        private final Category cat;

        CategoryMenu(Category cat) {
            super(6, cat.name());
            this.cat = cat;
        }

        @Override
        protected void render(Player p) {
            int i = 0;
            for (Map.Entry<Material, Long> e : cat.items().entrySet()) {
                if (i >= 45) break;
                Material mat = e.getKey();
                long price = e.getValue();
                ItemStack icon = new ItemStack(mat);
                ItemMeta meta = icon.getItemMeta();
                meta.lore(m().lore("shop.item-lore",
                        Money.tag(m(), "price", price),
                        Money.tag(m(), "price16", price * Math.min(16, mat.getMaxStackSize())),
                        Money.tag(m(), "price64", price * mat.getMaxStackSize()),
                        Placeholder.unparsed("count16", String.valueOf(Math.min(16, mat.getMaxStackSize()))),
                        Placeholder.unparsed("stack", String.valueOf(mat.getMaxStackSize()))));
                icon.setItemMeta(meta);
                setClick(i++, icon, (v, click) -> {
                    int amount = click.isShiftClick() ? mat.getMaxStackSize()
                            : click == ClickType.RIGHT ? Math.min(16, mat.getMaxStackSize()) : 1;
                    buy(v, mat, amount, price);
                    open(v);
                });
            }
            for (int s = 45; s < 54; s++) set(s, pane(), null);
            set(45, Items.icon(Material.ARROW, m().item("shop.back"), List.of()), v -> new Main().open(v));
            set(49, Items.icon(Material.GOLD_INGOT, m().item("ah.balance.name"),
                    m().lore("ah.balance.lore", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())))), null);
        }
    }

    private void buy(Player p, Material mat, int amount, long unitPrice) {
        long total = unitPrice * amount;
        ItemStack goods = new ItemStack(mat, amount);
        if (!Items.hasRoom(p, goods)) {
            m().send(p, "ah.no-room");
            return;
        }
        if (!plugin.economy().withdraw(p.getUniqueId(), total, "shop", amount + " " + mat.name().toLowerCase())) {
            m().send(p, "eco.not-enough", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())));
            return;
        }
        Items.give(p, goods);
        plugin.achievements().grant(p, "shop");
        m().send(p, "shop.bought",
                Placeholder.component("item", Items.name(goods)),
                Placeholder.unparsed("count", String.valueOf(amount)),
                Money.tag(m(), "price", total));
        Fx.sound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 0.5f, 1.4f);
    }
}
