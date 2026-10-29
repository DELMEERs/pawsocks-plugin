package club.zeroiq.pawsocks.social;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.economy.Economy;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.gui.PawMenu;
import club.zeroiq.pawsocks.homes.HomesFeature;
import club.zeroiq.pawsocks.util.Dialogs;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Друзья (/friend) и парочки (/couple). На полосе локатора (1.21.6+) по умолчанию видны
 * только друзья и пара, а не все игроки подряд. У пары — сердечки рядом и общий дом.
 */
public final class FriendsFeature implements CommandExecutor, TabCompleter, Listener {

    private record Request(UUID from, long at) {
    }

    private final PawsocksPlugin plugin;
    private final Friends friends;
    private final NamespacedKey locatorKey;
    /** кому → от кого → когда */
    private final Map<UUID, Map<UUID, Long>> friendRequests = new HashMap<>();
    private final Map<UUID, Request> proposals = new HashMap<>();
    /** Для сетевого потока: кто в сети и кто видит на локаторе всех. */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Set<UUID> seeAll = ConcurrentHashMap.newKeySet();
    private BukkitTask heartsTask;

    public FriendsFeature(PawsocksPlugin plugin, Friends friends) {
        this.plugin = plugin;
        this.friends = friends;
        this.locatorKey = new NamespacedKey(plugin, "locator_all");
    }

    private Messages m() {
        return plugin.messages();
    }

    private Economy eco() {
        return plugin.economy();
    }

    public void start() {
        friends.loadCouples();
        for (Player p : Bukkit.getOnlinePlayers()) joined(p);
        stopTask();
        heartsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::hearts, 60L, 60L);
    }

    public void stopTask() {
        if (heartsTask != null) {
            heartsTask.cancel();
            heartsTask = null;
        }
    }

    private void joined(Player p) {
        friends.cache(p.getUniqueId());
        online.add(p.getUniqueId());
        if (p.getPersistentDataContainer().getOrDefault(locatorKey, PersistentDataType.BOOLEAN, false)) seeAll.add(p.getUniqueId());
        else seeAll.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        joined(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        friends.uncache(u);
        online.remove(u);
        seeAll.remove(u);
        friendRequests.remove(u);
        proposals.remove(u);
    }

    /** Показывать ли target на полосе локатора у receiver. Вызывается из сетевого потока. */
    public boolean visibleOnLocator(UUID receiver, UUID target) {
        if (!plugin.getConfig().getBoolean("friends.locator-friends-only", true)) return true;
        if (receiver.equals(target) || !online.contains(target)) return true; // не игрок — не наше дело
        if (seeAll.contains(receiver)) return true;
        return friends.cached(receiver).contains(target) || target.equals(friends.partner(receiver));
    }

    // --- команды ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("couple")) {
            couple(p, args);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            new FriendsMenu().open(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            m().send(p, "friends.usage");
            return true;
        }
        switch (sub) {
            case "add" -> request(p, args[1]);
            case "accept" -> accept(p, args[1]);
            case "deny" -> deny(p, args[1]);
            case "remove" -> remove(p, args[1]);
            default -> m().send(p, "friends.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        List<String> subs = command.getName().equalsIgnoreCase("couple")
                ? List.of("propose", "accept", "deny", "home", "sethome", "leave")
                : List.of("add", "accept", "deny", "remove", "list");
        if (args.length == 1) {
            for (String s : subs) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }

    private long ttl() {
        return plugin.getConfig().getLong("friends.request-minutes", 5) * 60_000L;
    }

    public void request(Player from, String name) {
        Player to = Bukkit.getPlayerExact(name);
        if (to == null || !from.canSee(to)) {
            m().send(from, "friends.not-found");
            return;
        }
        if (to.equals(from)) {
            m().send(from, "friends.self");
            return;
        }
        if (friends.areFriends(from.getUniqueId(), to.getUniqueId())) {
            m().send(from, "friends.already", Placeholder.unparsed("name", to.getName()));
            return;
        }
        int max = plugin.getConfig().getInt("friends.max", 50);
        if (friends.cached(from.getUniqueId()).size() >= max) {
            m().send(from, "friends.limit", Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        // встречная заявка — сразу дружим
        Map<UUID, Long> mine = friendRequests.get(from.getUniqueId());
        if (mine != null && mine.containsKey(to.getUniqueId())) {
            accept(from, to.getName());
            return;
        }
        friendRequests.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>()).put(from.getUniqueId(), System.currentTimeMillis());
        m().send(from, "friends.sent", Placeholder.unparsed("name", to.getName()));
        // parsed — чтобы ник подставился и в команду кнопки
        m().send(to, "friends.request", Placeholder.parsed("name", from.getName()));
        Fx.sound(to.getLocation(), "minecraft:entity.cat.ambient", 0.6f, 1.4f);
    }

    private void accept(Player to, String name) {
        Map<UUID, Long> reqs = friendRequests.get(to.getUniqueId());
        Economy.Account from = eco().find(name);
        Long at = (reqs == null || from == null) ? null : reqs.remove(from.uuid());
        if (at == null || System.currentTimeMillis() - at > ttl()) {
            m().send(to, "friends.nothing");
            return;
        }
        friends.add(to.getUniqueId(), from.uuid());
        m().send(to, "friends.added", Placeholder.unparsed("name", from.name()));
        Fx.sound(to.getLocation(), "minecraft:entity.player.levelup", 0.5f, 1.6f);
        plugin.achievements().grant(to, "friend");
        Player other = Bukkit.getPlayer(from.uuid());
        if (other != null) {
            m().send(other, "friends.added", Placeholder.unparsed("name", to.getName()));
            Fx.sound(other.getLocation(), "minecraft:entity.player.levelup", 0.5f, 1.6f);
            plugin.achievements().grant(other, "friend");
        }
    }

    private void deny(Player to, String name) {
        Map<UUID, Long> reqs = friendRequests.get(to.getUniqueId());
        Economy.Account from = eco().find(name);
        if (reqs == null || from == null || reqs.remove(from.uuid()) == null) {
            m().send(to, "friends.nothing");
            return;
        }
        m().send(to, "friends.denied");
    }

    private void remove(Player p, String name) {
        Economy.Account other = eco().find(name);
        if (other == null || !friends.areFriends(p.getUniqueId(), other.uuid())) {
            m().send(p, "friends.not-friend", Placeholder.unparsed("name", name));
            return;
        }
        removeFriend(p, other.uuid(), other.name());
    }

    private void removeFriend(Player p, UUID other, String name) {
        if (other.equals(friends.partner(p.getUniqueId()))) {
            m().send(p, "friends.partner-first");
            return;
        }
        friends.remove(p.getUniqueId(), other);
        m().send(p, "friends.removed", Placeholder.unparsed("name", name));
    }

    // --- парочки ---

    private void couple(Player p, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "propose" -> {
                if (args.length < 2) m().send(p, "couples.usage");
                else propose(p, args[1]);
            }
            case "accept" -> acceptProposal(p);
            case "deny" -> {
                Request r = proposals.remove(p.getUniqueId());
                if (r == null) m().send(p, "couples.nothing");
                else {
                    m().send(p, "couples.denied-target");
                    Player from = Bukkit.getPlayer(r.from());
                    if (from != null) m().send(from, "couples.denied-sender", Placeholder.unparsed("name", p.getName()));
                }
            }
            case "home" -> home(p);
            case "sethome" -> setHome(p);
            case "leave" -> leave(p);
            default -> new FriendsMenu().open(p);
        }
    }

    private void propose(Player from, String name) {
        Player to = Bukkit.getPlayerExact(name);
        if (to == null || !from.canSee(to) || to.equals(from)) {
            m().send(from, "friends.not-found");
            return;
        }
        if (!friends.areFriends(from.getUniqueId(), to.getUniqueId())) {
            m().send(from, "couples.friends-first", Placeholder.unparsed("name", to.getName()));
            return;
        }
        if (friends.partner(from.getUniqueId()) != null) {
            m().send(from, "couples.already-you");
            return;
        }
        if (friends.partner(to.getUniqueId()) != null) {
            m().send(from, "couples.already-them", Placeholder.unparsed("name", to.getName()));
            return;
        }
        proposals.put(to.getUniqueId(), new Request(from.getUniqueId(), System.currentTimeMillis()));
        m().send(from, "couples.sent", Placeholder.unparsed("name", to.getName()));
        m().send(to, "couples.request", Placeholder.unparsed("name", from.getName()));
        Fx.hearts(to, 4);
        Fx.sound(to.getLocation(), "minecraft:block.amethyst_block.chime", 1f, 1.5f);
    }

    private void acceptProposal(Player to) {
        Request r = proposals.remove(to.getUniqueId());
        if (r == null || System.currentTimeMillis() - r.at() > ttl()) {
            m().send(to, "couples.nothing");
            return;
        }
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            m().send(to, "couples.nothing");
            return;
        }
        if (friends.partner(to.getUniqueId()) != null || friends.partner(from.getUniqueId()) != null) {
            m().send(to, "couples.already-you");
            return;
        }
        friends.marry(from.getUniqueId(), to.getUniqueId());
        Component msg = m().get("couples.formed", Placeholder.unparsed("a", from.getName()), Placeholder.unparsed("b", to.getName()));
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
        plugin.telegram().sendHtml(m().raw("couples.telegram").replace("{a}", from.getName()).replace("{b}", to.getName()));
        for (Player p : List.of(from, to)) {
            Fx.hearts(p, 10);
            Fx.sound(p.getLocation(), "minecraft:entity.player.levelup", 0.7f, 1.2f);
            plugin.achievements().grant(p, "couple");
        }
    }

    private void leave(Player p) {
        UUID partner = friends.partner(p.getUniqueId());
        if (partner == null) {
            m().send(p, "couples.none");
            return;
        }
        friends.split(p.getUniqueId());
        String name = eco().name(partner);
        m().send(p, "couples.left", Placeholder.unparsed("name", name));
        Player other = Bukkit.getPlayer(partner);
        if (other != null) m().send(other, "couples.left-other", Placeholder.unparsed("name", p.getName()));
    }

    private void setHome(Player p) {
        UUID partner = friends.partner(p.getUniqueId());
        if (partner == null) {
            m().send(p, "couples.none");
            return;
        }
        Location l = p.getLocation();
        friends.setHome(p.getUniqueId(), partner, new Friends.Spot(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
        m().send(p, "couples.home-set");
        Player other = Bukkit.getPlayer(partner);
        if (other != null) m().send(other, "couples.home-set-other", Placeholder.unparsed("name", p.getName()));
        Fx.hearts(p, 5);
    }

    private void home(Player p) {
        UUID partner = friends.partner(p.getUniqueId());
        if (partner == null) {
            m().send(p, "couples.none");
            return;
        }
        Friends.Spot s = friends.home(p.getUniqueId(), partner);
        if (s == null) {
            m().send(p, "couples.no-home");
            return;
        }
        plugin.homes().teleport(p, new HomesFeature.Home(m().raw("couples.home-name"), s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch()));
    }

    /** Сердечки над парой, когда они рядом. */
    private void hearts() {
        Set<UUID> done = new HashSet<>();
        for (Map.Entry<UUID, UUID> e : friends.couples().entrySet()) {
            if (!done.add(e.getKey()) || !done.add(e.getValue())) continue;
            Player a = Bukkit.getPlayer(e.getKey());
            Player b = Bukkit.getPlayer(e.getValue());
            if (a == null || b == null || a.getWorld() != b.getWorld()) continue;
            if (a.getLocation().distanceSquared(b.getLocation()) > 16) continue;
            Location mid = a.getLocation().add(b.getLocation()).multiply(0.5).add(0, 2.1, 0);
            a.getWorld().spawnParticle(Particle.HEART, mid, 1, 0.3, 0.1, 0.3, 0);
        }
    }

    // --- меню ---

    private ItemStack head(UUID uuid, String name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        meta.setOwningPlayer(op);
        meta.displayName(m().item("friends.menu.friend-name", Placeholder.unparsed("name", name)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public void open(Player p) {
        new FriendsMenu().open(p);
    }

    private final class FriendsMenu extends Menu {
        FriendsMenu() {
            super(6, plugin.messages().get("friends.menu.title"));
        }

        @Override
        protected void render(Player p) {
            var pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
            for (int i = 45; i < 54; i++) set(i, pane, null);
            UUID me = p.getUniqueId();
            UUID partner = friends.partner(me);
            List<UUID> list = friends.list(me);
            for (int i = 0; i < list.size() && i < 45; i++) {
                UUID f = list.get(i);
                String name = eco().name(f);
                boolean on = Bukkit.getPlayer(f) != null;
                List<Component> lore = new ArrayList<>();
                if (f.equals(partner)) lore.add(m().item("friends.menu.partner"));
                lore.add(m().item(on ? "friends.menu.online" : "friends.menu.offline"));
                lore.addAll(m().lore("friends.menu.friend-lore"));
                setClick(i, head(f, name, lore), (v, click) -> {
                    if (click == ClickType.SHIFT_RIGHT) {
                        removeFriend(v, f, name);
                        open(v);
                    } else if (click == ClickType.LEFT && partner == null && Bukkit.getPlayer(f) != null) {
                        v.closeInventory();
                        propose(v, name);
                    }
                });
            }
            if (list.isEmpty()) {
                set(22, Items.icon(Material.CAKE, m().item("friends.menu.empty-name"), m().lore("friends.menu.empty-lore")), null);
            }

            set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> new PawMenu(plugin).open(v));
            set(47, Items.icon(Material.WRITABLE_BOOK, m().item("friends.menu.add-name"), m().lore("friends.menu.add-lore")), v -> {
                v.closeInventory();
                addDialog(v);
            });

            if (partner != null) {
                String pn = eco().name(partner);
                set(49, Items.glow(Items.icon(Material.POPPY, m().item("friends.menu.couple-name", Placeholder.unparsed("name", pn)),
                        m().lore("friends.menu.couple-lore"))), v -> {
                    v.closeInventory();
                    home(v);
                });
            } else {
                set(49, Items.icon(Material.POPPY, m().item("friends.menu.single-name"), m().lore("friends.menu.single-lore")), null);
            }

            boolean all = seeAll.contains(me);
            set(51, Items.icon(Material.COMPASS, m().item("friends.menu.locator-name"),
                    m().lore(all ? "friends.menu.locator-all" : "friends.menu.locator-friends")), v -> {
                boolean now = !seeAll.contains(v.getUniqueId());
                v.getPersistentDataContainer().set(locatorKey, PersistentDataType.BOOLEAN, now);
                if (now) seeAll.add(v.getUniqueId());
                else seeAll.remove(v.getUniqueId());
                m().send(v, now ? "friends.locator-all" : "friends.locator-friends");
                open(v);
            });
        }
    }

    /** Окно «добавить друга» с полем для ника. */
    public void addDialog(Player p) {
        if (!plugin.dialogs().enabled()) {
            m().send(p, "friends.usage");
            return;
        }
        plugin.dialogs().confirm(p, m().get("friends.dialog.title"), m().lore("friends.dialog.body"),
                List.of(Dialogs.text("nick", m().get("friends.dialog.nick"), "", 16)),
                m().get("friends.dialog.yes"), m().get("friends.dialog.no"),
                (v, view) -> {
                    String nick = Dialogs.textValue(view, "nick");
                    if (!nick.isEmpty()) request(v, nick);
                });
    }
}
