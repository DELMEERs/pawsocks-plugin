package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.PawsocksPlugin;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Аукцион игроков: /ah — смотреть и покупать, /ah sell цена — выставить вещь из руки.
 * С каждой продажи сервер берёт налог — это главный «сток» лапок, чтобы они не копились бесконечно.
 */
public final class AuctionFeature implements CommandExecutor, TabCompleter, Listener {

    private static final int PAGE = 45;

    private final PawsocksPlugin plugin;
    private BukkitTask expiryTask;

    public AuctionFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Auction ah() {
        return plugin.auction();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("economy.auction.enabled", true);
    }

    private double tax() {
        return plugin.getConfig().getDouble("economy.auction.tax-percent", 5);
    }

    public void start() {
        stop();
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                ah().expireDue();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("аукцион: не удалось вернуть просроченные лоты: " + e.getMessage());
            }
        }, 20L * 10, 20L * 60);
    }

    public void stop() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
    }

    // --- команды ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!enabled()) {
            m().send(player, "no-permission");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("sell")) {
            if (args.length < 2) {
                m().send(player, "ah.usage");
                return true;
            }
            list(player, args[1]);
            return true;
        }
        new BrowseMenu(0).open(player);
        return true;
    }

    private int limit(Player player) {
        int best = plugin.getConfig().getInt("economy.auction.max-listings", 5);
        for (int n = 1; n <= 100; n++) {
            if (player.hasPermission("pawsocks.ah.listings." + n)) best = Math.max(best, n);
        }
        return best;
    }

    private void list(Player player, String priceArg) {
        long price = Money.parse(priceArg);
        long min = Money.parse(plugin.getConfig().getString("economy.auction.min-price", "0.01"));
        long max = Money.parse(plugin.getConfig().getString("economy.auction.max-price", "1000000"));
        if (price <= 0 || (min > 0 && price < min) || (max > 0 && price > max)) {
            m().send(player, "ah.bad-price", Money.tag(m(), "min", Math.max(min, 1)), Money.tag(m(), "max", max));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            m().send(player, "ah.empty-hand");
            return;
        }
        int limit = limit(player);
        if (ah().bySeller(player.getUniqueId()).size() >= limit) {
            m().send(player, "ah.limit", Placeholder.unparsed("limit", String.valueOf(limit)));
            return;
        }
        ItemStack item = hand.clone();
        player.getInventory().setItemInMainHand(null);
        long hours = plugin.getConfig().getLong("economy.auction.duration-hours", 48);
        try {
            ah().create(player.getUniqueId(), player.getName(), item, price, hours * 3_600_000L);
        } catch (RuntimeException e) {
            // не вышло записать — вернуть вещь
            player.getInventory().setItemInMainHand(item);
            plugin.getLogger().warning("аукцион: не удалось выставить лот: " + e.getMessage());
            m().send(player, "error");
            return;
        }
        long payout = price - Money.percent(price, tax());
        m().send(player, "ah.listed",
                Placeholder.component("item", Items.name(item)),
                Placeholder.unparsed("count", String.valueOf(item.getAmount())),
                Money.tag(m(), "price", price),
                Money.tag(m(), "payout", payout),
                Placeholder.unparsed("tax", trim(tax())),
                Placeholder.unparsed("hours", String.valueOf(hours)));
        Fx.sound(player.getLocation(), "minecraft:block.note_block.chime", 0.7f, 1.2f);
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private void buy(Player player, Auction.Listing l) {
        if (!hasRoom(player, l.item())) {
            m().send(player, "ah.no-room");
            return;
        }
        Player seller = Bukkit.getPlayer(l.seller());
        Auction.BuyResult r = ah().buy(player.getUniqueId(), player.getName(), l.id(), tax(), seller != null);
        switch (r) {
            case GONE -> m().send(player, "ah.gone");
            case OWN -> m().send(player, "ah.own");
            case NO_MONEY -> m().send(player, "ah.no-money", Money.tag(m(), "price", l.price()),
                    Money.tag(m(), "balance", plugin.economy().balance(player.getUniqueId())));
            case OK -> {
                give(player, l.item());
                m().send(player, "ah.bought",
                        Placeholder.component("item", Items.name(l.item())),
                        Placeholder.unparsed("count", String.valueOf(l.item().getAmount())),
                        Money.tag(m(), "price", l.price()),
                        Placeholder.unparsed("seller", l.sellerName()));
                Fx.sound(player.getLocation(), "minecraft:entity.villager.yes", 0.7f, 1.1f);
                if (seller != null) {
                    long payout = l.price() - Money.percent(l.price(), tax());
                    m().send(seller, "ah.sold",
                            Placeholder.component("item", Items.name(l.item())),
                            Placeholder.unparsed("count", String.valueOf(l.item().getAmount())),
                            Placeholder.unparsed("buyer", player.getName()),
                            Money.tag(m(), "payout", payout));
                    Fx.sound(seller.getLocation(), "minecraft:entity.experience_orb.pickup", 0.7f, 1f);
                }
            }
        }
    }

    private static boolean hasRoom(Player player, ItemStack item) {
        int need = item.getAmount();
        int max = item.getMaxStackSize();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                need -= max;
            } else if (slot.isSimilar(item)) {
                need -= Math.max(0, max - slot.getAmount());
            }
            if (need <= 0) return true;
        }
        return false;
    }

    private static void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            Auction.SoldSummary s = ah().unseenSales(player.getUniqueId());
            if (s.count() > 0) {
                m().send(player, "ah.sold-offline",
                        Placeholder.unparsed("count", String.valueOf(s.count())),
                        Money.tag(m(), "payout", s.payout()));
            }
            int mail = ah().mailboxCount(player.getUniqueId());
            if (mail > 0) {
                m().send(player, "ah.mail-waiting", Placeholder.unparsed("count", String.valueOf(mail)));
            }
        }, 60L);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1 && "sell".startsWith(args[0].toLowerCase(Locale.ROOT))) return List.of("sell");
        return List.of();
    }

    // --- меню ---

    private String left(long expires) {
        long ms = Math.max(0, expires - System.currentTimeMillis());
        long min = ms / 60_000;
        long d = min / 1440, h = (min % 1440) / 60, mm = min % 60;
        if (d > 0) return m().raw("time.dh").replace("<d>", String.valueOf(d)).replace("<h>", String.valueOf(h));
        if (h > 0) return m().raw("time.hm").replace("<h>", String.valueOf(h)).replace("<m>", String.valueOf(mm));
        return m().raw("time.m").replace("<m>", String.valueOf(Math.max(1, mm)));
    }

    /** Иконка лота: сама вещь + цена, продавец и сколько осталось. */
    private ItemStack icon(Auction.Listing l, String hintKey) {
        ItemStack icon = l.item().clone();
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(m().lore("ah.lot-lore",
                Money.tag(m(), "price", l.price()),
                Placeholder.unparsed("seller", l.sellerName()),
                Placeholder.unparsed("left", left(l.expires()))));
        lore.add(m().item(hintKey));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack button(Material type, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        return Items.icon(type, m().item(key + ".name", r), m().lore(key + ".lore", r));
    }

    private static ItemStack pane() {
        return Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private final class BrowseMenu extends Menu {
        private int page;

        BrowseMenu(int page) {
            super(6, plugin.messages().get("ah.title"));
            this.page = page;
        }

        @Override
        protected void render(Player p) {
            int total = ah().countActive();
            int pages = Math.max(1, (total + PAGE - 1) / PAGE);
            page = Math.max(0, Math.min(page, pages - 1));
            List<Auction.Listing> lots = ah().active(page * PAGE, PAGE);
            for (int i = 0; i < lots.size(); i++) {
                Auction.Listing l = lots.get(i);
                boolean own = l.seller().equals(p.getUniqueId());
                set(i, icon(l, own ? "ah.lot-own" : "ah.lot-buy"), viewer -> {
                    if (l.seller().equals(viewer.getUniqueId())) {
                        m().send(viewer, "ah.own");
                        return;
                    }
                    new ConfirmMenu(l, this).open(viewer);
                });
            }
            for (int i = 45; i < 54; i++) set(i, pane(), null);
            if (page > 0) set(45, button(Material.ARROW, "ah.prev"), v -> {
                page--;
                open(v);
            });
            set(47, button(Material.CHEST, "ah.mine",
                    Placeholder.unparsed("count", String.valueOf(ah().bySeller(p.getUniqueId()).size())),
                    Placeholder.unparsed("limit", String.valueOf(limit(p)))), v -> new MineMenu().open(v));
            set(48, button(Material.HOPPER, "ah.mailbox",
                    Placeholder.unparsed("count", String.valueOf(ah().mailboxCount(p.getUniqueId())))), v -> new MailMenu().open(v));
            set(49, button(Material.GOLD_INGOT, "ah.balance",
                    Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId()))), null);
            set(50, button(Material.CLOCK, "ah.refresh",
                    Placeholder.unparsed("page", String.valueOf(page + 1)),
                    Placeholder.unparsed("pages", String.valueOf(pages)),
                    Placeholder.unparsed("count", String.valueOf(total))), this::open);
            set(51, button(Material.WRITABLE_BOOK, "ah.help",
                    Placeholder.unparsed("tax", trim(tax()))), null);
            if (page < pages - 1) set(53, button(Material.ARROW, "ah.next"), v -> {
                page++;
                open(v);
            });
        }
    }

    private final class ConfirmMenu extends Menu {
        private final Auction.Listing lot;
        private final BrowseMenu back;

        ConfirmMenu(Auction.Listing lot, BrowseMenu back) {
            super(3, plugin.messages().get("ah.confirm-title"));
            this.lot = lot;
            this.back = back;
        }

        @Override
        protected void render(Player p) {
            for (int i = 0; i < 27; i++) set(i, pane(), null);
            set(13, icon(lot, "ah.lot-confirm"), null);
            set(11, button(Material.LIME_WOOL, "ah.confirm-yes", Money.tag(m(), "price", lot.price())), v -> {
                v.closeInventory();
                buy(v, lot);
            });
            set(15, button(Material.RED_WOOL, "ah.confirm-no"), v -> back.open(v));
        }
    }

    private final class MineMenu extends Menu {
        MineMenu() {
            super(6, plugin.messages().get("ah.mine-title"));
        }

        @Override
        protected void render(Player p) {
            List<Auction.Listing> lots = ah().bySeller(p.getUniqueId());
            for (int i = 0; i < lots.size() && i < PAGE; i++) {
                Auction.Listing l = lots.get(i);
                set(i, icon(l, "ah.lot-cancel"), v -> {
                    if (ah().cancel(v.getUniqueId(), l.id())) {
                        m().send(v, "ah.cancelled");
                    }
                    open(v);
                });
            }
            for (int i = 45; i < 54; i++) set(i, pane(), null);
            set(45, button(Material.ARROW, "ah.back"), v -> new BrowseMenu(0).open(v));
            set(48, button(Material.HOPPER, "ah.mailbox",
                    Placeholder.unparsed("count", String.valueOf(ah().mailboxCount(p.getUniqueId())))), v -> new MailMenu().open(v));
        }
    }

    private final class MailMenu extends Menu {
        MailMenu() {
            super(6, plugin.messages().get("ah.mailbox-title"));
        }

        @Override
        protected void render(Player p) {
            List<Auction.Mail> mail = ah().mailbox(p.getUniqueId());
            for (int i = 0; i < mail.size() && i < PAGE; i++) {
                Auction.Mail mm = mail.get(i);
                ItemStack icon = mm.item().clone();
                ItemMeta meta = icon.getItemMeta();
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(m().item("ah.mail-reason." + mm.reason()));
                lore.add(m().item("ah.mail-take"));
                meta.lore(lore);
                icon.setItemMeta(meta);
                set(i, icon, v -> {
                    if (!hasRoom(v, mm.item())) {
                        m().send(v, "ah.no-room");
                        return;
                    }
                    ItemStack item = ah().takeMail(v.getUniqueId(), mm.id());
                    if (item != null) give(v, item);
                    open(v);
                });
            }
            for (int i = 45; i < 54; i++) set(i, pane(), null);
            set(45, button(Material.ARROW, "ah.back"), v -> new BrowseMenu(0).open(v));
        }
    }
}
