package club.zeroiq.pawsocks.social;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.gui.PawMenu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Эмоции: мяу, мурр, буп и другие — звук, частицы и строчка в чате для тех, кто рядом. */
public final class EmoteFeature implements CommandExecutor, TabCompleter, Listener {

    /** target — нужен ли тот, на кого смотришь (буп, обнюхать). */
    public enum Emote {
        MEOW("meow", Material.COD, "minecraft:entity.cat.ambient", false),
        PURR("purr", Material.STRING, "minecraft:entity.cat.purr", false),
        WAVE("wave", Material.FEATHER, "minecraft:entity.allay.item_thrown", false),
        BOOP("boop", Material.PINK_DYE, "minecraft:entity.chicken.egg", true),
        SNIFF("sniff", Material.SWEET_BERRIES, "minecraft:entity.fox.sniff", true),
        TAIL("tail", Material.BONE, "minecraft:entity.wolf.pant", false),
        BLUSH("blush", Material.PINK_TULIP, "minecraft:block.amethyst_block.chime", false),
        HISS("hiss", Material.FIRE_CHARGE, "minecraft:entity.cat.hiss", false),
        YAWN("yawn", Material.WHITE_BED, "minecraft:entity.fox.sleep", false);

        public final String id;
        public final Material icon;
        public final String sound;
        public final boolean target;

        Emote(String id, Material icon, String sound, boolean target) {
            this.id = id;
            this.icon = icon;
            this.sound = sound;
            this.target = target;
        }

        public static Emote byId(String id) {
            for (Emote e : values()) if (e.id.equalsIgnoreCase(id)) return e;
            return null;
        }
    }

    private final PawsocksPlugin plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public EmoteFeature(PawsocksPlugin plugin) {
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
        if (!plugin.getConfig().getBoolean("emotes.enabled", true)) {
            m().send(p, "no-permission");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        Emote e = name.equals("emote") ? (args.length > 0 ? Emote.byId(args[0]) : null) : Emote.byId(name);
        if (e == null) {
            new EmoteMenu().open(p);
            return true;
        }
        play(p, e);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("emote") && args.length == 1) {
            for (Emote e : Emote.values()) if (e.id.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(e.id);
        }
        return out;
    }

    public void play(Player p, Emote e) {
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("emotes.cooldown-seconds", 3) * 1000L;
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            m().actionBar(p, "emotes.cooldown");
            return;
        }

        Player target = null;
        if (e.target) {
            target = lookedAt(p);
            if (target == null) {
                m().send(p, "emotes.need-target");
                return;
            }
        }
        cooldown.put(p.getUniqueId(), now);

        Location head = p.getEyeLocation();
        World w = p.getWorld();
        float pitch = 0.9f + (float) Math.random() * 0.4f;
        Fx.sound(head, e.sound, 0.9f, pitch);
        switch (e) {
            case MEOW, PURR -> w.spawnParticle(Particle.NOTE, head.clone().add(0, 0.6, 0), 3, 0.3, 0.1, 0.3, 1);
            case WAVE -> {
                p.swingMainHand();
                w.spawnParticle(Particle.CHERRY_LEAVES, head.clone().add(0, 0.3, 0), 8, 0.4, 0.2, 0.4, 0);
            }
            case BOOP -> {
                Location nose = target.getEyeLocation().add(target.getLocation().getDirection().multiply(0.3));
                w.spawnParticle(Particle.HEART, nose, 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.DUST, nose, 8, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(0xFF9CC2), 0.8f));
                p.swingMainHand();
            }
            case SNIFF -> w.spawnParticle(Particle.CLOUD, target.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.01);
            case TAIL -> w.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 0.9, 0), 8, 0.3, 0.2, 0.3, 0);
            case BLUSH -> w.spawnParticle(Particle.DUST, head, 12, 0.25, 0.15, 0.25, 0, new Particle.DustOptions(Color.fromRGB(0xFF7FA8), 0.9f));
            case HISS -> w.spawnParticle(Particle.ANGRY_VILLAGER, head.clone().add(0, 0.5, 0), 2, 0.2, 0.1, 0.2, 0);
            case YAWN -> w.spawnParticle(Particle.CLOUD, head.clone().add(p.getLocation().getDirection().multiply(0.4)), 3, 0.05, 0.05, 0.05, 0.005);
        }

        double radius = plugin.getConfig().getDouble("emotes.radius", 24);
        Component line = target == null
                ? m().get("emotes.say." + e.id, Placeholder.unparsed("name", p.getName()))
                : m().get("emotes.say." + e.id, Placeholder.unparsed("name", p.getName()), Placeholder.unparsed("target", target.getName()));
        for (Player near : w.getPlayers()) {
            if (near.getLocation().distanceSquared(p.getLocation()) <= radius * radius) near.sendMessage(line);
        }
        if (target != null && e == Emote.BOOP) {
            m().actionBar(target, "emotes.booped", Placeholder.unparsed("name", p.getName()));
            plugin.achievements().grant(p, "boop");
        }
        plugin.achievements().grant(p, "emote");
    }

    /** Игрок, на которого смотришь (до 4 блоков). */
    private static Player lookedAt(Player p) {
        RayTraceResult hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 4.0, 0.3,
                ent -> ent instanceof Player other && !other.equals(p) && p.canSee(other)
                        && !other.hasPotionEffect(PotionEffectType.INVISIBILITY));
        Entity ent = hit == null ? null : hit.getHitEntity();
        return ent instanceof Player t ? t : null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldown.remove(event.getPlayer().getUniqueId());
    }

    private final class EmoteMenu extends Menu {
        EmoteMenu() {
            super(3, plugin.messages().get("emotes.title"));
        }

        @Override
        protected void render(Player p) {
            var pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
            for (int i = 0; i < 27; i++) set(i, pane, null);
            int slot = 9;
            for (Emote e : Emote.values()) {
                List<Component> lore = new ArrayList<>(m().lore("emotes.lore." + e.id));
                lore.add(m().item("emotes.command", Placeholder.unparsed("command", "/emote " + e.id)));
                set(slot++, Items.icon(e.icon, m().item("emotes.name." + e.id), lore), v -> {
                    v.closeInventory();
                    play(v, e);
                });
            }
            set(22, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> new PawMenu(plugin).open(v));
        }
    }
}
