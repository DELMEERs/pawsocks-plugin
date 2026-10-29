package club.zeroiq.pawsocks.homes;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Точки дома. Сколько их можно поставить — по правам:
 * pawsocks.homes.<n> (берётся наибольшее) или pawsocks.homes.unlimited, иначе homes.default-limit.
 * Телепорт с задержкой: пошевелился или получил урон — отменяется.
 */
public final class HomesFeature implements CommandExecutor, TabCompleter, Listener {

    public record Home(String name, String world, double x, double y, double z, float yaw, float pitch) {
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private record Pending(BukkitTask task, Location from) {
    }

    private static final int MAX_NAME = 16;

    private final PawsocksPlugin plugin;
    private final Database db;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();

    public HomesFeature(PawsocksPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    private Messages m() {
        return plugin.messages();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("homes.enabled", true);
    }

    private String defaultName() {
        return plugin.getConfig().getString("homes.default-name", "дом");
    }

    /** -1 — без ограничений. Лимит группы + докупленные за лапки. */
    public int limit(Player player) {
        if (player.hasPermission("pawsocks.homes.unlimited")) return -1;
        return groupLimit(player) + plugin.perks().counter(player.getUniqueId(), "home_slots");
    }

    private int groupLimit(Player player) {
        int best = plugin.getConfig().getInt("homes.default-limit", 1);
        for (int n = 1; n <= 54; n++) {
            if (player.hasPermission("pawsocks.homes." + n)) best = Math.max(best, n);
        }
        return best;
    }

    /** Цена следующей докупки или -1, если больше докупать нельзя. */
    private long nextSlotPrice(int bought) {
        List<String> prices = plugin.getConfig().getStringList("homes.extra-slot-prices");
        if (prices.isEmpty()) prices = List.of("150", "400", "1000", "2500");
        if (bought >= prices.size()) return -1;
        return Money.parse(prices.get(bought));
    }

    private void buySlot(Player p) {
        int bought = plugin.perks().counter(p.getUniqueId(), "home_slots");
        long price = nextSlotPrice(bought);
        if (price <= 0) {
            m().send(p, "homes.slots-max");
            return;
        }
        if (!plugin.perks().increment(p.getUniqueId(), "home_slots", bought, price, "home_slot " + (bought + 1))) {
            m().send(p, "eco.not-enough", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())));
            return;
        }
        m().send(p, "homes.slot-bought", Money.tag(m(), "price", price), Placeholder.unparsed("limit", String.valueOf(limit(p))));
        Fx.sound(p.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.4f);
    }

    // --- данные ---

    public List<Home> homes(UUID uuid) {
        return db.read(c -> {
            List<Home> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT name, world, x, y, z, yaw, pitch FROM homes WHERE uuid = ? ORDER BY created", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Home(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            rs.getFloat(6), rs.getFloat(7)));
                }
            }
            return out;
        });
    }

    private Home find(UUID uuid, String name) {
        for (Home h : homes(uuid)) {
            if (h.name().equalsIgnoreCase(name)) return h;
        }
        return null;
    }

    private void save(UUID uuid, String name, Location l) {
        db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO homes (uuid, name, world, x, y, z, yaw, pitch, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(uuid, name) DO UPDATE SET world = excluded.world, x = excluded.x, y = excluded.y, " +
                            "z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch",
                    uuid.toString(), name, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                    (double) l.getYaw(), (double) l.getPitch(), System.currentTimeMillis())) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    private boolean delete(UUID uuid, String name) {
        return db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "DELETE FROM homes WHERE uuid = ? AND name = ? COLLATE NOCASE", uuid.toString(), name)) {
                return ps.executeUpdate() > 0;
            }
        });
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
        String name = args.length == 0 ? null : args[0];
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> setHome(player, name == null ? defaultName() : name);
            case "home" -> {
                if (name == null) {
                    List<Home> list = homes(player.getUniqueId());
                    if (list.size() == 1) {
                        teleport(player, list.get(0));
                    } else if (list.isEmpty()) {
                        m().send(player, "homes.none");
                    } else {
                        new HomesMenu().open(player);
                    }
                } else {
                    Home h = find(player.getUniqueId(), name);
                    if (h == null) {
                        m().send(player, "homes.not-found", Placeholder.unparsed("name", name));
                    } else {
                        teleport(player, h);
                    }
                }
            }
            case "delhome" -> {
                String target = name;
                if (target == null) {
                    List<Home> list = homes(player.getUniqueId());
                    if (list.size() != 1) {
                        m().send(player, "homes.delhome-usage");
                        return true;
                    }
                    target = list.get(0).name();
                }
                if (delete(player.getUniqueId(), target)) {
                    m().send(player, "homes.deleted", Placeholder.unparsed("name", target));
                } else {
                    m().send(player, "homes.not-found", Placeholder.unparsed("name", target));
                }
            }
            case "homes" -> new HomesMenu().open(player);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setHome(Player player, String name) {
        if (name.length() > MAX_NAME || !name.matches("[\\p{L}\\p{N}_-]+")) {
            m().send(player, "homes.bad-name", Placeholder.unparsed("max", String.valueOf(MAX_NAME)));
            return;
        }
        List<Home> list = homes(player.getUniqueId());
        Home existing = null;
        for (Home h : list) {
            if (h.name().equalsIgnoreCase(name)) existing = h;
        }
        int limit = limit(player);
        if (existing == null && limit >= 0 && list.size() >= limit) {
            m().send(player, "homes.limit", Placeholder.unparsed("limit", String.valueOf(limit)));
            return;
        }
        String stored = existing != null ? existing.name() : name;
        save(player.getUniqueId(), stored, player.getLocation());
        m().send(player, existing != null ? "homes.moved" : "homes.set", Placeholder.unparsed("name", stored));
        plugin.achievements().grant(player, "home");
        Fx.sound(player.getLocation(), "minecraft:block.note_block.bell", 0.6f, 1.4f);
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
    }

    // --- телепорт ---

    public void teleport(Player player, Home home) {
        Location to = home.location();
        if (to == null) {
            m().send(player, "homes.no-world", Placeholder.unparsed("name", home.name()));
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("homes.cooldown-seconds", 5) * 1000L;
        Long last = lastTeleport.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            m().send(player, "homes.cooldown", Placeholder.unparsed("seconds", String.valueOf((cooldown - (now - last) + 999) / 1000)));
            return;
        }
        cancel(player, false);
        int delay = plugin.getConfig().getInt("homes.warmup-seconds", 3);
        if (delay <= 0 || player.hasPermission("pawsocks.homes.instant")) {
            go(player, home, to);
            return;
        }
        m().send(player, "homes.warmup", Placeholder.unparsed("name", home.name()), Placeholder.unparsed("seconds", String.valueOf(delay)));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) go(player, home, to);
        }, delay * 20L);
        pending.put(player.getUniqueId(), new Pending(task, player.getLocation().clone()));
    }

    private void go(Player player, Home home, Location to) {
        Location from = player.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.2);
        player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (!ok) return;
            lastTeleport.put(player.getUniqueId(), System.currentTimeMillis());
            Fx.sound(to, "minecraft:entity.enderman.teleport", 0.6f, 1.2f);
            m().actionBar(player, "homes.arrived", Placeholder.unparsed("name", home.name()));
        });
    }

    private void cancel(Player player, boolean notify) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return;
        p.task().cancel();
        if (notify) m().send(player, "homes.cancelled");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null || !event.hasChangedPosition()) return;
        Location to = event.getTo();
        if (to.getWorld() != p.from().getWorld() || to.distanceSquared(p.from()) > 0.25) {
            cancel(event.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && pending.containsKey(player.getUniqueId())) {
            cancel(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
        lastTeleport.remove(event.getPlayer().getUniqueId());
    }

    // --- меню ---

    private final class HomesMenu extends Menu {
        HomesMenu() {
            super(6, plugin.messages().get("homes.title"));
        }

        @Override
        protected void render(Player p) {
            List<Home> list = homes(p.getUniqueId());
            int limit = limit(p);
            for (int i = 0; i < list.size() && i < 45; i++) {
                Home h = list.get(i);
                ItemStack icon = Items.icon(Material.RED_BED,
                        m().item("homes.icon-name", Placeholder.unparsed("name", h.name())),
                        m().lore("homes.icon-lore",
                                Placeholder.unparsed("world", worldName(h.world())),
                                Placeholder.unparsed("x", String.valueOf((int) Math.floor(h.x()))),
                                Placeholder.unparsed("y", String.valueOf((int) Math.floor(h.y()))),
                                Placeholder.unparsed("z", String.valueOf((int) Math.floor(h.z())))));
                setClick(i, icon, (v, click) -> {
                    if (click == ClickType.SHIFT_RIGHT) {
                        delete(v.getUniqueId(), h.name());
                        m().send(v, "homes.deleted", Placeholder.unparsed("name", h.name()));
                        open(v);
                    } else {
                        v.closeInventory();
                        teleport(v, h);
                    }
                });
            }
            ItemStack pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
            for (int i = 45; i < 54; i++) set(i, pane, null);
            set(49, Items.icon(Material.COMPASS, m().item("homes.info-name"),
                    m().lore("homes.info-lore",
                            Placeholder.unparsed("count", String.valueOf(list.size())),
                            Placeholder.component("limit", limit < 0 ? m().get("homes.unlimited") : Component.text(limit)))), null);
            if (limit >= 0) {
                int bought = plugin.perks().counter(p.getUniqueId(), "home_slots");
                long price = nextSlotPrice(bought);
                set(51, Items.icon(Material.EMERALD, m().item("homes.buy-name"),
                        price > 0 ? m().lore("homes.buy-lore", Money.tag(m(), "price", price),
                                Placeholder.unparsed("bought", String.valueOf(bought)))
                                : m().lore("homes.buy-max")), v -> {
                    buySlot(v);
                    open(v);
                });
            }
        }
    }

    private String worldName(String world) {
        return switch (world) {
            case "world" -> m().raw("homes.world.overworld");
            case "world_nether" -> m().raw("homes.world.nether");
            case "world_the_end" -> m().raw("homes.world.end");
            default -> world;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) return List.of();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!cmd.equals("home") && !cmd.equals("delhome") && !cmd.equals("sethome")) return List.of();
        List<String> out = new ArrayList<>();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (Home h : homes(player.getUniqueId())) {
            if (h.name().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(h.name());
        }
        return out;
    }
}
