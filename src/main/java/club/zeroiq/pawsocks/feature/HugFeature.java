package club.zeroiq.pawsocks.feature;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Messages;
import club.zeroiq.pawsocks.util.PlayerData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Обнимашки: /hug ник → второй игрок подтверждает → сердечки, мурчание, реген и уют обоим. */
public final class HugFeature implements CommandExecutor, TabCompleter, Listener {

    private record Request(UUID from, long at) {
    }

    private final PawsocksPlugin plugin;
    /** кому → от кого */
    private final Map<UUID, Request> pending = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public HugFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!plugin.getConfig().getBoolean("hugs.enabled", true)) {
            m().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            m().send(player, "hug.usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> accept(player);
            case "deny" -> deny(player);
            default -> request(player, args[0]);
        }
        return true;
    }

    private void request(Player from, String targetName) {
        Player to = Bukkit.getPlayerExact(targetName);
        if (to == null || !from.canSee(to)) {
            m().send(from, "hug.not-found");
            return;
        }
        if (to.equals(from)) {
            m().send(from, "hug.self");
            return;
        }
        if (!PlayerData.hugsAllowed(to)) {
            m().send(from, "hug.disabled", Placeholder.unparsed("name", to.getName()));
            return;
        }
        if (!close(from, to)) {
            m().send(from, "hug.too-far", Placeholder.unparsed("name", to.getName()));
            return;
        }
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("hugs.cooldown-seconds", 30) * 1000L;
        Long last = cooldown.get(from.getUniqueId());
        if (last != null && now - last < cd) {
            long left = (cd - (now - last) + 999) / 1000;
            m().send(from, "hug.cooldown", Placeholder.unparsed("seconds", String.valueOf(left)));
            return;
        }
        cooldown.put(from.getUniqueId(), now);
        pending.put(to.getUniqueId(), new Request(from.getUniqueId(), now));
        m().send(from, "hug.sent", Placeholder.unparsed("name", to.getName()));
        m().send(to, "hug.request", Placeholder.unparsed("name", from.getName()));
        Fx.sound(to.getLocation(), "minecraft:entity.cat.ambient", 0.6f, 1.3f);
    }

    private void accept(Player to) {
        Request req = pending.remove(to.getUniqueId());
        if (req == null) {
            m().send(to, "hug.nothing");
            return;
        }
        long ttl = plugin.getConfig().getLong("hugs.request-seconds", 60) * 1000L;
        Player from = Bukkit.getPlayer(req.from());
        if (from == null || System.currentTimeMillis() - req.at() > ttl) {
            m().send(to, "hug.expired");
            return;
        }
        if (!close(from, to)) {
            m().send(to, "hug.too-far", Placeholder.unparsed("name", from.getName()));
            return;
        }
        hug(from, to);
    }

    private void deny(Player to) {
        Request req = pending.remove(to.getUniqueId());
        if (req == null) {
            m().send(to, "hug.nothing");
            return;
        }
        m().send(to, "hug.denied-target");
        Player from = Bukkit.getPlayer(req.from());
        if (from != null) {
            m().send(from, "hug.denied-sender", Placeholder.unparsed("name", to.getName()));
        }
    }

    private void hug(Player a, Player b) {
        // повернуть друг к другу
        face(a, b);
        face(b, a);

        Fx.hearts(a, 6);
        Fx.hearts(b, 6);
        Location mid = a.getLocation().add(b.getLocation()).multiply(0.5);
        Fx.sound(mid, "minecraft:entity.cat.purr", 1f, 1f);
        Fx.sound(mid, "minecraft:block.amethyst_block.chime", 0.8f, 1.4f);

        int regen = plugin.getConfig().getInt("hugs.regen-seconds", 5) * 20;
        int bonus = plugin.getConfig().getInt("cozy.hug-bonus", 25);
        for (Player p : List.of(a, b)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, regen, 0, true, false, true));
            PlayerData.addHug(p);
            plugin.cozy().add(p, bonus);
        }

        // видят все рядом
        double radius = 16;
        for (Player near : a.getWorld().getPlayers()) {
            if (near.getLocation().distanceSquared(mid) <= radius * radius) {
                m().send(near, "hug.done",
                        Placeholder.unparsed("a", a.getName()),
                        Placeholder.unparsed("b", b.getName()));
            }
        }
    }

    private static void face(Player who, Player target) {
        Location from = who.getLocation();
        Location look = from.clone().setDirection(target.getLocation().toVector().subtract(from.toVector()));
        who.setRotation(look.getYaw(), from.getPitch());
    }

    private boolean close(Player a, Player b) {
        if (a.getWorld() != b.getWorld()) return false;
        double max = plugin.getConfig().getDouble("hugs.max-distance", 8);
        return a.getLocation().distanceSquared(b.getLocation()) <= max * max;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (String s : List.of("accept", "deny")) {
            if (s.startsWith(prefix)) out.add(s);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(sender)) continue;
            if (sender instanceof Player viewer && !viewer.canSee(p)) continue;
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        cooldown.remove(id);
    }
}
