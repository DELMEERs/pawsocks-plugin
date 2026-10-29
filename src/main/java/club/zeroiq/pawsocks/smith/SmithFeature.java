package club.zeroiq.pawsocks.smith;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Кузнец: починка за лапки вместо опыта, сброс штрафа наковальни («слишком дорого!»)
 * и переименование. Всё — с предметом в руке.
 */
public final class SmithFeature implements CommandExecutor {

    private static final int MAX_NAME = 32;

    private final PawsocksPlugin plugin;

    public SmithFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!plugin.getConfig().getBoolean("economy.smith.enabled", true)) {
            m().send(p, "no-permission");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("name")) {
            if (args.length == 1 && plugin.dialogs().enabled()) {
                nameDialog(p);
                return true;
            }
            rename(p, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim());
            return true;
        }
        if (p.getInventory().getItemInMainHand().getType().isAir()) {
            m().send(p, "smith.empty-hand");
            return true;
        }
        new SmithMenu().open(p);
        return true;
    }

    // --- расчёт цен ---

    private int maxDamage(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && d.hasMaxDamage()) return d.getMaxDamage();
        return item.getType().getMaxDurability();
    }

    private int damage(ItemStack item) {
        return item.getItemMeta() instanceof Damageable d ? d.getDamage() : 0;
    }

    /** Починка: доля стоимости предмета, пропорционально поломке. */
    private long repairCost(ItemStack item) {
        int max = maxDamage(item);
        int dmg = damage(item);
        if (max <= 0 || dmg <= 0) return 0;
        double factor = plugin.getConfig().getDouble("economy.smith.repair-factor", 0.5);
        long min = Money.parse(plugin.getConfig().getString("economy.smith.repair-min", "0.5"));
        long value = plugin.prices().base(item.getType());
        long cost = (long) Math.ceil(value * ((double) dmg / max) * factor);
        return Math.max(Math.max(1, min), cost);
    }

    private int penalty(ItemStack item) {
        return item.getItemMeta() instanceof Repairable r && r.hasRepairCost() ? r.getRepairCost() : 0;
    }

    private long resetCost(ItemStack item) {
        long base = Money.parse(plugin.getConfig().getString("economy.smith.reset-base", "10"));
        long per = Money.parse(plugin.getConfig().getString("economy.smith.reset-per-level", "2"));
        return Math.max(0, base) + Math.max(0, per) * penalty(item);
    }

    private long renameCost() {
        return Math.max(0, Money.parse(plugin.getConfig().getString("economy.smith.rename", "5")));
    }

    // --- действия ---

    /** Предмет в руке не поменялся с открытия меню. */
    private boolean same(Player p, ItemStack seen) {
        ItemStack now = p.getInventory().getItemInMainHand();
        return now.getType() == seen.getType() && now.getAmount() == seen.getAmount();
    }

    private boolean pay(Player p, long cost, String what) {
        if (cost <= 0) return true;
        if (plugin.economy().withdraw(p.getUniqueId(), cost, "smith", what)) return true;
        m().send(p, "eco.not-enough", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())));
        return false;
    }

    private void repair(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        long cost = repairCost(hand);
        if (cost <= 0) {
            m().send(p, "smith.not-damaged");
            return;
        }
        if (!pay(p, cost, "repair " + hand.getType().name().toLowerCase())) return;
        ItemMeta meta = hand.getItemMeta();
        if (meta instanceof Damageable d) d.setDamage(0);
        hand.setItemMeta(meta);
        m().send(p, "smith.repaired", Money.tag(m(), "money", cost));
        plugin.achievements().grant(p, "smith");
        Fx.sound(p.getLocation(), "minecraft:block.anvil.use", 0.6f, 1.2f);
    }

    private void reset(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (penalty(hand) <= 0) {
            m().send(p, "smith.no-penalty");
            return;
        }
        long cost = resetCost(hand);
        if (!pay(p, cost, "reset " + hand.getType().name().toLowerCase())) return;
        ItemMeta meta = hand.getItemMeta();
        if (meta instanceof Repairable r) r.setRepairCost(0);
        hand.setItemMeta(meta);
        m().send(p, "smith.reset", Money.tag(m(), "money", cost));
        plugin.achievements().grant(p, "smith");
        Fx.sound(p.getLocation(), "minecraft:block.grindstone.use", 0.7f, 1.1f);
    }

    /** Окно с полем для нового имени. Пустое поле — убрать имя. */
    public void nameDialog(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            m().send(p, "smith.empty-hand");
            return;
        }
        String current = hand.hasItemMeta() && hand.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(hand.getItemMeta().displayName())
                : "";
        if (current.length() > MAX_NAME) current = current.substring(0, MAX_NAME);
        org.bukkit.Material type = hand.getType();
        plugin.dialogs().confirm(p, m().get("smith.dialog.title"),
                m().lore("smith.dialog.body", Money.tag(m(), "price", renameCost()), Placeholder.unparsed("max", String.valueOf(MAX_NAME))),
                java.util.List.of(club.zeroiq.pawsocks.util.Dialogs.text("name", m().get("smith.dialog.label"), current, MAX_NAME)),
                m().get("smith.dialog.yes"), m().get("smith.dialog.no"),
                (v, view) -> {
                    if (v.getInventory().getItemInMainHand().getType() != type) {
                        m().send(v, "smith.dialog.changed");
                        return;
                    }
                    rename(v, club.zeroiq.pawsocks.util.Dialogs.textValue(view, "name"));
                });
    }

    private void rename(Player p, String text) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            m().send(p, "smith.empty-hand");
            return;
        }
        if (text.length() > MAX_NAME) {
            m().send(p, "smith.name-too-long", Placeholder.unparsed("max", String.valueOf(MAX_NAME)));
            return;
        }
        long cost = renameCost();
        if (!pay(p, cost, "rename")) return;
        ItemMeta meta = hand.getItemMeta();
        if (text.isEmpty()) {
            meta.displayName(null);
        } else {
            // только простой текст, без цветов и кликов — чтобы никто не подделал чужие предметы
            meta.displayName(Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        }
        hand.setItemMeta(meta);
        m().send(p, text.isEmpty() ? "smith.unnamed" : "smith.renamed", Money.tag(m(), "money", cost));
        plugin.achievements().grant(p, "smith");
        Fx.sound(p.getLocation(), "minecraft:block.anvil.use", 0.5f, 1.5f);
    }

    // --- меню ---

    private final class SmithMenu extends Menu {
        SmithMenu() {
            super(3, plugin.messages().get("smith.title"));
        }

        @Override
        protected void render(Player p) {
            ItemStack pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
            for (int i = 0; i < 27; i++) set(i, pane, null);
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                p.closeInventory();
                return;
            }
            ItemStack seen = hand.clone();
            set(13, seen.clone(), null);

            long repair = repairCost(hand);
            set(11, Items.icon(Material.ANVIL, m().item("smith.repair-name"),
                    repair > 0
                            ? m().lore("smith.repair-lore", Money.tag(m(), "price", repair),
                            Placeholder.unparsed("damage", String.valueOf(damage(hand))),
                            Placeholder.unparsed("max", String.valueOf(maxDamage(hand))))
                            : m().lore("smith.repair-none")), v -> {
                if (!same(v, seen)) {
                    open(v);
                    return;
                }
                repair(v);
                open(v);
            });

            int pen = penalty(hand);
            set(15, Items.icon(Material.GRINDSTONE, m().item("smith.reset-name"),
                    pen > 0
                            ? m().lore("smith.reset-lore", Money.tag(m(), "price", resetCost(hand)),
                            Placeholder.unparsed("level", String.valueOf(pen)))
                            : m().lore("smith.reset-none")), v -> {
                if (!same(v, seen)) {
                    open(v);
                    return;
                }
                reset(v);
                open(v);
            });

            set(22, Items.icon(Material.NAME_TAG, m().item("smith.rename-name"),
                    m().lore("smith.rename-lore", Money.tag(m(), "price", renameCost()),
                            Placeholder.unparsed("max", String.valueOf(MAX_NAME)))), v -> {
                v.closeInventory();
                if (plugin.dialogs().enabled()) nameDialog(v);
                else m().send(v, "smith.name-usage");
            });
        }
    }
}
