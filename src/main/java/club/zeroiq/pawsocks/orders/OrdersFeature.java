package club.zeroiq.pawsocks.orders;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.economy.Guard;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /order — доска заказов. «/order create кол-во цена» с предметом в руке — разместить,
 * /order — список: нажми на заказ, чтобы сдать подходящие вещи из инвентаря.
 */
public final class OrdersFeature implements CommandExecutor, TabCompleter {

    private static final int PAGE = 45;

    private final PawsocksPlugin plugin;
    private BukkitTask expiryTask;

    public OrdersFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Orders orders() {
        return plugin.orders();
    }

    private double commission() {
        return plugin.getConfig().getDouble("economy.orders.commission-percent", 3);
    }

    public void start() {
        stop();
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                orders().expireDue();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("заказы: не удалось закрыть истёкшие: " + e.getMessage());
            }
        }, 20L * 15, 20L * 60);
    }

    public void stop() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!plugin.getConfig().getBoolean("economy.orders.enabled", true)) {
            m().send(p, "no-permission");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("create")) {
            if (args.length < 3) {
                if (plugin.dialogs().enabled()) createDialog(p);
                else m().send(p, "orders.usage");
                return true;
            }
            create(p, args[1], args[2]);
            return true;
        }
        new BoardMenu(0).open(p);
        return true;
    }

    /** Окно заказа: сколько и за сколько, образец — в руке. */
    public void createDialog(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Material mat = hand.getType();
        if (mat.isAir()) {
            m().send(p, "orders.empty-hand");
            return;
        }
        if (Guard.neverSold(mat) || plugin.guard().blockedForTrade(hand)) {
            m().send(p, "orders.blocked");
            return;
        }
        int maxAmount = plugin.getConfig().getInt("economy.orders.max-amount", 2304);
        int initial = Math.min(maxAmount, mat.getMaxStackSize());
        long unit = Math.max(1, plugin.prices().base(mat));
        String suggested = Money.plain(unit * initial * 3 / 2);
        plugin.dialogs().confirm(p, m().get("orders.dialog.title"),
                m().lore("orders.dialog.body", Placeholder.component("item", Items.name(new ItemStack(mat))),
                        Placeholder.unparsed("commission", trim(commission()))),
                List.of(club.zeroiq.pawsocks.util.Dialogs.number("amount", m().get("orders.dialog.amount"), 1, maxAmount, initial),
                        club.zeroiq.pawsocks.util.Dialogs.text("price", m().get("orders.dialog.price"), suggested, 12)),
                m().get("orders.dialog.yes"), m().get("orders.dialog.no"),
                (v, view) -> {
                    if (v.getInventory().getItemInMainHand().getType() != mat) {
                        m().send(v, "orders.dialog.changed");
                        return;
                    }
                    Integer amount = club.zeroiq.pawsocks.util.Dialogs.intValue(view, "amount");
                    String price = club.zeroiq.pawsocks.util.Dialogs.textValue(view, "price").replace(',', '.');
                    create(v, String.valueOf(amount == null ? -1 : amount), price);
                });
    }

    private void create(Player p, String amountArg, String priceArg) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Material mat = hand.getType();
        if (mat.isAir()) {
            m().send(p, "orders.empty-hand");
            return;
        }
        if (Guard.neverSold(mat) || plugin.guard().blockedForTrade(hand)) {
            m().send(p, "orders.blocked");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(amountArg);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        int maxAmount = plugin.getConfig().getInt("economy.orders.max-amount", 2304);
        if (amount < 1 || amount > maxAmount) {
            m().send(p, "orders.bad-amount", Placeholder.unparsed("max", String.valueOf(maxAmount)));
            return;
        }
        long price = Money.parse(priceArg);
        if (price < amount || price > Money.parse("1000000")) {
            // меньше сотой лапки за штуку не бывает
            m().send(p, "orders.bad-price", Money.tag(m(), "min", amount));
            return;
        }
        int limit = plugin.getConfig().getInt("economy.orders.max-per-player", 3);
        if (orders().byOwner(p.getUniqueId()).size() >= limit) {
            m().send(p, "orders.limit", Placeholder.unparsed("limit", String.valueOf(limit)));
            return;
        }
        long hours = plugin.getConfig().getLong("economy.orders.duration-hours", 72);
        if (!orders().create(p.getUniqueId(), p.getName(), mat, amount, price, hours * 3_600_000L)) {
            m().send(p, "eco.not-enough", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())));
            return;
        }
        m().send(p, "orders.created",
                Placeholder.component("item", Items.name(new ItemStack(mat))),
                Placeholder.unparsed("count", String.valueOf(amount)),
                Money.tag(m(), "price", price),
                Placeholder.unparsed("hours", String.valueOf(hours)));
        Fx.sound(p.getLocation(), "minecraft:block.note_block.chime", 0.7f, 1.2f);
        plugin.achievements().grant(p, "order");
    }

    private void deliver(Player p, Orders.Order o) {
        if (o.owner().equals(p.getUniqueId())) {
            m().send(p, "orders.own");
            return;
        }
        PlayerInventory inv = p.getInventory();
        ItemStack plain = ItemStack.of(o.material());
        int have = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && s.isSimilar(plain)) have += s.getAmount();
        }
        int n = Math.min(have, o.left());
        if (n <= 0) {
            m().send(p, "orders.nothing", Placeholder.component("item", Items.name(plain)));
            return;
        }
        // сначала забираем вещи, потом платим; если заказ уже закрыт — возвращаем
        ItemStack take = plain.clone();
        take.setAmount(n);
        int removed = n - leftoverAmount(inv.removeItem(splitStacks(take)));
        Orders.Delivery d;
        try {
            d = orders().deliver(o.id(), p.getUniqueId(), removed, commission());
        } catch (RuntimeException e) {
            d = null;
            plugin.getLogger().warning("заказы: ошибка при сдаче: " + e.getMessage());
        }
        if (d == null) {
            ItemStack back = plain.clone();
            back.setAmount(removed);
            for (ItemStack s : splitStacks(back)) Items.give(p, s);
            m().send(p, "orders.gone");
            return;
        }
        if (d.delivered() < removed) {
            ItemStack back = plain.clone();
            back.setAmount(removed - d.delivered());
            for (ItemStack s : splitStacks(back)) Items.give(p, s);
        }
        plugin.achievements().grant(p, "order");
        m().send(p, "orders.delivered",
                Placeholder.unparsed("count", String.valueOf(d.delivered())),
                Placeholder.component("item", Items.name(plain)),
                Money.tag(m(), "money", d.paid()),
                Money.tag(m(), "commission", d.commission()));
        Fx.sound(p.getLocation(), "minecraft:entity.villager.yes", 0.7f, 1.1f);
        Player owner = Bukkit.getPlayer(d.owner());
        if (owner != null) {
            m().send(owner, d.done() ? "orders.owner-done" : "orders.owner-progress",
                    Placeholder.unparsed("count", String.valueOf(d.delivered())),
                    Placeholder.component("item", Items.name(plain)),
                    Placeholder.unparsed("name", p.getName()));
        }
    }

    private static ItemStack[] splitStacks(ItemStack item) {
        List<ItemStack> out = new ArrayList<>();
        int left = item.getAmount();
        int max = item.getMaxStackSize();
        while (left > 0) {
            ItemStack s = item.clone();
            s.setAmount(Math.min(left, max));
            out.add(s);
            left -= s.getAmount();
        }
        return out.toArray(ItemStack[]::new);
    }

    private static int leftoverAmount(java.util.Map<Integer, ItemStack> left) {
        int n = 0;
        for (ItemStack s : left.values()) n += s.getAmount();
        return n;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1 && "create".startsWith(args[0].toLowerCase(Locale.ROOT))) return List.of("create");
        return List.of();
    }

    // --- меню ---

    private static ItemStack pane() {
        return Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private ItemStack icon(Orders.Order o, String hintKey) {
        ItemStack icon = new ItemStack(o.material());
        ItemMeta meta = icon.getItemMeta();
        long each = o.price() / o.amount();
        List<Component> lore = new ArrayList<>(m().lore("orders.lot-lore",
                Placeholder.unparsed("left", String.valueOf(o.left())),
                Placeholder.unparsed("amount", String.valueOf(o.amount())),
                Money.tag(m(), "price", o.price()),
                Money.tag(m(), "each", Math.max(1, each)),
                Placeholder.unparsed("owner", o.ownerName()),
                Placeholder.unparsed("time", time(o.expires()))));
        lore.add(m().item(hintKey));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private String time(long expires) {
        long min = Math.max(0, expires - System.currentTimeMillis()) / 60_000;
        long d = min / 1440, h = (min % 1440) / 60, mm = min % 60;
        if (d > 0) return m().raw("time.dh").replace("<d>", String.valueOf(d)).replace("<h>", String.valueOf(h));
        if (h > 0) return m().raw("time.hm").replace("<h>", String.valueOf(h)).replace("<m>", String.valueOf(mm));
        return m().raw("time.m").replace("<m>", String.valueOf(Math.max(1, mm)));
    }

    private final class BoardMenu extends Menu {
        private int page;

        BoardMenu(int page) {
            super(6, plugin.messages().get("orders.title"));
            this.page = page;
        }

        @Override
        protected void render(Player p) {
            int total = orders().countActive();
            int pages = Math.max(1, (total + PAGE - 1) / PAGE);
            page = Math.max(0, Math.min(page, pages - 1));
            List<Orders.Order> list = orders().active(page * PAGE, PAGE);
            for (int i = 0; i < list.size(); i++) {
                Orders.Order o = list.get(i);
                boolean own = o.owner().equals(p.getUniqueId());
                set(i, icon(o, own ? "orders.lot-own" : "orders.lot-deliver"), v -> {
                    deliver(v, o);
                    open(v);
                });
            }
            for (int i = 45; i < 54; i++) set(i, pane(), null);
            if (page > 0) set(45, Items.icon(Material.ARROW, m().item("ah.prev.name"), List.of()), v -> {
                page--;
                open(v);
            });
            set(48, Items.icon(Material.CHEST, m().item("orders.mine-name"),
                    m().lore("orders.mine-lore", Placeholder.unparsed("count", String.valueOf(orders().byOwner(p.getUniqueId()).size())))),
                    v -> new MineMenu().open(v));
            set(50, Items.icon(Material.WRITABLE_BOOK, m().item("orders.help-name"),
                    m().lore("orders.help-lore", Placeholder.unparsed("commission", trim(commission())))), v -> {
                v.closeInventory();
                if (plugin.dialogs().enabled()) createDialog(v);
                else m().send(v, "orders.usage");
            });
            if (page < pages - 1) set(53, Items.icon(Material.ARROW, m().item("ah.next.name"), List.of()), v -> {
                page++;
                open(v);
            });
        }
    }

    private final class MineMenu extends Menu {
        MineMenu() {
            super(3, plugin.messages().get("orders.mine-title"));
        }

        @Override
        protected void render(Player p) {
            for (int i = 0; i < 27; i++) set(i, pane(), null);
            List<Orders.Order> list = orders().byOwner(p.getUniqueId());
            for (int i = 0; i < list.size() && i < 18; i++) {
                Orders.Order o = list.get(i);
                set(i, icon(o, "orders.lot-cancel"), v -> {
                    long back = orders().cancel(v.getUniqueId(), o.id());
                    if (back >= 0) m().send(v, "orders.cancelled", Money.tag(m(), "money", back));
                    open(v);
                });
            }
            set(18, Items.icon(Material.ARROW, m().item("orders.back"), List.of()), v -> new BoardMenu(0).open(v));
        }
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
