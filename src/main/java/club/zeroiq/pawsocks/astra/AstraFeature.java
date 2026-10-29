package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.astra.AstraItems.Ench;
import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.economy.Economy;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Астры ⭐: 1 за каждые 30 минут активной игры (не больше 5 в день) и ежедневная награда
 * при входе (1–3 астры + лапки). Тратятся в магазине астр (/astra).
 */
public final class AstraFeature implements CommandExecutor, TabCompleter, Listener {

    private final PawsocksPlugin plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AstraFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Astras astras() {
        return plugin.astras();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("astra.enabled", true);
    }

    public String today() {
        ZoneId zone;
        try {
            zone = ZoneId.of(plugin.getConfig().getString("astra.timezone", "Europe/Moscow"));
        } catch (Exception e) {
            zone = ZoneId.of("Europe/Moscow");
        }
        return LocalDate.now(zone).toString();
    }

    /** «3 ⭐ астры» */
    public static Component format(Messages m, int n) {
        return m.get("astra.format",
                Placeholder.unparsed("amount", String.valueOf(n)),
                Placeholder.unparsed("word", Money.word(n * 100L, m.raw("astra.one"), m.raw("astra.few"), m.raw("astra.many"))));
    }

    public static TagResolver tag(Messages m, String name, int n) {
        return Placeholder.component(name, format(m, n));
    }

    // --- начисление ---

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 60, 20L * 60);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        long afk = plugin.getConfig().getLong("astra.afk-minutes", 5) * 60_000L;
        int period = plugin.getConfig().getInt("astra.play-minutes", 30) * 60;
        int max = plugin.getConfig().getInt("astra.play-max-per-day", 5);
        String day = today();
        for (Player p : Bukkit.getOnlinePlayers()) {
            claimIfNeeded(p, day);
            Long last = lastActive.get(p.getUniqueId());
            if (last == null || now - last > afk) continue; // афк не считается
            try {
                int got = astras().addPlay(p.getUniqueId(), day, 60, period, max);
                if (got > 0) {
                    plugin.achievements().grant(p, "astra");
                    int earned = astras().daily(p.getUniqueId(), day).earned();
                    m().send(p, "astra.earned",
                            tag(m(), "astra", got),
                            Placeholder.unparsed("done", String.valueOf(earned)),
                            Placeholder.unparsed("max", String.valueOf(max)),
                            Placeholder.unparsed("minutes", String.valueOf(period / 60)));
                    Fx.sound(p.getLocation(), "minecraft:block.amethyst_block.chime", 1f, 1.2f);
                }
            } catch (Database.DataException e) {
                plugin.getLogger().warning("астры: не удалось засчитать время игры: " + e.getMessage());
            }
        }
    }

    /** Ежедневная награда: при входе и в полночь для тех, кто в игре. */
    private void claimIfNeeded(Player p, String day) {
        if (astras().daily(p.getUniqueId(), day).claimed()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int aMin = plugin.getConfig().getInt("astra.daily.astra-min", 1);
        int aMax = Math.max(aMin, plugin.getConfig().getInt("astra.daily.astra-max", 3));
        long lMin = Money.parse(plugin.getConfig().getString("astra.daily.lapki-min", "5"));
        long lMax = Math.max(lMin, Money.parse(plugin.getConfig().getString("astra.daily.lapki-max", "15")));
        int astra = r.nextInt(aMin, aMax + 1);
        long lapki = lMin <= 0 ? 0 : (r.nextLong(lMin / 100, lMax / 100 + 1) * 100);
        if (astras().claimDaily(p.getUniqueId(), day, astra, lapki)) {
            m().send(p, "astra.daily", tag(m(), "astra", astra), Money.tag(m(), "money", lapki));
            if (astra > 0) plugin.achievements().grant(p, "astra");
            Fx.sound(p.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.4f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        lastActive.put(p.getUniqueId(), System.currentTimeMillis());
        if (!enabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) claimIfNeeded(p, today());
        }, 20L * 4);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
    }

    // активность: поворот головы, клики, блоки, команды. Просто стоять в воде или в вагонетке — не считается
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLook(PlayerMoveEvent event) {
        if (event.hasChangedOrientation()) active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        active(event.getPlayer());
    }

    private void active(Player p) {
        lastActive.put(p.getUniqueId(), System.currentTimeMillis());
    }

    // --- команды ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length >= 1 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!enabled()) {
            m().send(p, "no-permission");
            return true;
        }
        openShop(p);
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pawsocks.astra.admin")) {
            m().send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            m().send(sender, "astra.admin-usage");
            return;
        }
        Economy.Account a = plugin.economy().find(args[1]);
        if (a == null) {
            m().send(sender, "eco.no-account");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            n = -1;
        }
        if (n < 0 || n > 1_000_000) {
            m().send(sender, "astra.admin-usage");
            return;
        }
        String note = "by " + sender.getName();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> astras().give(a.uuid(), n, "astra_admin_give", note);
            case "take" -> astras().give(a.uuid(), -n, "astra_admin_take", note);
            default -> astras().set(a.uuid(), n, note);
        }
        m().send(sender, "astra.admin-done", Placeholder.unparsed("name", a.name()), tag(m(), "astra", astras().balance(a.uuid())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("pawsocks.astra.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("give", "take", "set")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }

    // --- магазин астр ---

    private int price(String id) {
        return plugin.getConfig().getInt("astra.shop." + id, 30);
    }

    private static ItemStack pane() {
        return Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    public void openShop(Player p) {
        new ShopMenu().open(p);
    }

    private final class ShopMenu extends Menu {
        ShopMenu() {
            super(4, plugin.messages().get("astra.shop.title"));
        }

        @Override
        protected void render(Player p) {
            for (int i = 0; i < 36; i++) set(i, pane(), null);
            UUID u = p.getUniqueId();

            // чары: возьми инструмент в руку
            int[] slots = {10, 11, 12};
            List<Ench> all = AstraItems.all();
            for (int i = 0; i < all.size() && i < slots.length; i++) {
                Ench e = all.get(i);
                int cost = price(e.id);
                ItemStack icon = Items.icon(e.icon, m().item("astra.ench." + e.id + ".name"),
                        m().lore("astra.ench." + e.id + ".lore", tag(m(), "price", cost)));
                Items.glow(icon);
                set(slots[i], icon, v -> new ConfirmMenu(e.id, cost, this).open(v));
            }

            // звёздный фонарь
            int lampCost = price("lamp");
            ItemStack lamp = plugin.astraItems().lamp();
            var lampMeta = lamp.getItemMeta();
            List<Component> lampLore = new ArrayList<>(m().lore("astra.lamp.lore",
                    Placeholder.unparsed("radius", String.valueOf(plugin.lamps().radius()))));
            lampLore.addAll(m().lore("astra.shop.price-lore", tag(m(), "price", lampCost)));
            lampMeta.lore(lampLore);
            lamp.setItemMeta(lampMeta);
            set(14, lamp, v -> new ConfirmMenu("lamp", lampCost, this).open(v));

            // звёздный ник
            int nickCost = price("star-nick");
            boolean nickOwned = plugin.perks().owns(u, StarFeature.NICK);
            ItemStack nick = Items.icon(Material.NAME_TAG, m().item("astra.star-nick.name"),
                    concat(m().lore("astra.star-nick.lore"),
                            nickOwned ? m().lore("astra.shop.owned-open") : m().lore("astra.shop.price-lore", tag(m(), "price", nickCost))));
            Items.glow(nick);
            set(15, nick, v -> {
                if (plugin.perks().owns(v.getUniqueId(), StarFeature.NICK)) plugin.stars().openStyles(v, this);
                else new ConfirmMenu("star-nick", nickCost, this).open(v);
            });

            // звёздный вход
            int joinCost = price("star-join");
            boolean joinOwned = plugin.perks().owns(u, StarFeature.JOIN);
            List<Component> joinState = joinOwned
                    ? m().lore(plugin.stars().joinEnabled(u) ? "astra.star-join.state-on" : "astra.star-join.state-off")
                    : m().lore("astra.shop.price-lore", tag(m(), "price", joinCost));
            ItemStack join = Items.icon(Material.FIREWORK_ROCKET, m().item("astra.star-join.name"),
                    concat(m().lore("astra.star-join.lore"), joinState));
            Items.glow(join);
            set(16, join, v -> {
                if (plugin.perks().owns(v.getUniqueId(), StarFeature.JOIN)) {
                    boolean on = plugin.stars().toggleJoin(v);
                    m().send(v, on ? "astra.star-join.enabled" : "astra.star-join.disabled");
                    if (on) plugin.stars().starfall(v);
                    open(v);
                } else {
                    new ConfirmMenu("star-join", joinCost, this).open(v);
                }
            });

            String day = today();
            Astras.Daily d = astras().daily(u, day);
            int max = plugin.getConfig().getInt("astra.play-max-per-day", 5);
            set(31, plugin.astraItems().astraIcon(m().item("astra.shop.balance-name", tag(m(), "astra", astras().balance(u))),
                    m().lore("astra.shop.balance-lore",
                            Placeholder.unparsed("done", String.valueOf(d.earned())),
                            Placeholder.unparsed("max", String.valueOf(max)),
                            Placeholder.unparsed("minutes", String.valueOf(plugin.getConfig().getInt("astra.play-minutes", 30))),
                            Placeholder.component("daily", m().get(d.claimed() ? "astra.shop.daily-yes" : "astra.shop.daily-no")))), null);
        }
    }

    private static List<Component> concat(List<Component> a, List<Component> b) {
        List<Component> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private final class ConfirmMenu extends Menu {
        private final String id;
        private final int cost;
        private final ShopMenu back;

        ConfirmMenu(String id, int cost, ShopMenu back) {
            super(3, plugin.messages().get("astra.shop.confirm-title"));
            this.id = id;
            this.cost = cost;
            this.back = back;
        }

        @Override
        protected void render(Player p) {
            for (int i = 0; i < 27; i++) set(i, pane(), null);
            Ench e = Ench.byId(id);
            ItemStack shown = switch (id) {
                case "lamp" -> plugin.astraItems().lamp();
                case "star-nick" -> Items.glow(Items.icon(Material.NAME_TAG, m().item("astra.star-nick.name"), List.of()));
                case "star-join" -> Items.glow(Items.icon(Material.FIREWORK_ROCKET, m().item("astra.star-join.name"), List.of()));
                default -> Items.glow(Items.icon(e.icon, m().item("astra.ench." + id + ".name"), m().lore("astra.shop.need-" + id)));
            };
            set(13, shown, null);
            set(11, Items.icon(Material.LIME_WOOL, m().item("astra.shop.yes", tag(m(), "price", cost)), List.of()), v -> {
                v.closeInventory();
                buy(v, id, cost, back);
            });
            set(15, Items.icon(Material.RED_WOOL, m().item("astra.shop.no"), List.of()), v -> back.open(v));
        }
    }

    private void noAstra(Player p, int cost) {
        m().send(p, "astra.shop.no-astra", tag(m(), "price", cost), tag(m(), "astra", astras().balance(p.getUniqueId())));
    }

    private void bought(Player p) {
        Fx.sound(p.getLocation(), "minecraft:block.enchantment_table.use", 1f, 1f);
        Fx.sound(p.getLocation(), "minecraft:block.amethyst_block.resonate", 1f, 1.2f);
        plugin.achievements().grant(p, "astra_shop");
    }

    private void buy(Player p, String id, int cost, ShopMenu back) {
        switch (id) {
            case "lamp" -> {
                ItemStack lamp = plugin.astraItems().lamp();
                if (!Items.hasRoom(p, lamp)) {
                    m().send(p, "ah.no-room");
                    return;
                }
                if (!astras().spend(p.getUniqueId(), cost, id)) {
                    noAstra(p, cost);
                    return;
                }
                Items.give(p, lamp);
                m().send(p, "astra.lamp.bought");
                bought(p);
            }
            case "star-nick", "star-join" -> {
                String item = id.equals("star-nick") ? StarFeature.NICK : StarFeature.JOIN;
                switch (plugin.perks().buyAstra(p.getUniqueId(), item, cost, id)) {
                    case ALREADY -> m().send(p, "astra.shop.already-owned");
                    case NO_MONEY -> noAstra(p, cost);
                    case OK -> {
                        bought(p);
                        if (id.equals("star-nick")) {
                            m().send(p, "astra.star-nick.bought");
                            plugin.stars().openStyles(p, back);
                        } else {
                            plugin.stars().ownedJoin(p.getUniqueId());
                            m().send(p, "astra.star-join.bought");
                            plugin.stars().starfall(p);
                        }
                    }
                }
            }
            default -> {
                Ench e = Ench.byId(id);
                if (e == null) return;
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir() || !e.fits(hand.getType())) {
                    m().send(p, "astra.shop.wrong-item", Placeholder.component("need", m().get("astra.shop.need-" + id + "-short")));
                    return;
                }
                if (plugin.astraItems().has(hand, e)) {
                    m().send(p, "astra.shop.already");
                    return;
                }
                if (hand.getAmount() != 1) {
                    m().send(p, "astra.shop.one-item");
                    return;
                }
                if (!astras().spend(p.getUniqueId(), cost, id)) {
                    noAstra(p, cost);
                    return;
                }
                plugin.astraItems().apply(hand, e);
                p.getInventory().setItemInMainHand(hand);
                m().send(p, "astra.shop.applied", Placeholder.component("ench", m().get("astra.ench." + id + ".name")));
                bought(p);
            }
        }
    }
}
