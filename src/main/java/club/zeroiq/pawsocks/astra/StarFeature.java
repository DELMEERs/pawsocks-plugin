package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Звёздный ник (переливающийся ник в чате и табе, через ник FlectonePulse)
 * и звёздный вход (звездопад вокруг игрока и особое сообщение в телеграме).
 */
public final class StarFeature implements Listener {

    public static final String NICK = "star_nick";
    public static final String JOIN = "star_join";
    private static final String JOIN_OFF = "star_join_off";
    private static final Pattern TAG = Pattern.compile("<([a-z_]+)");

    private final PawsocksPlugin plugin;
    private final NamespacedKey styleKey;
    /** У кого звёздный вход куплен и включён. Читается и из потока tgbridge. */
    private final Set<UUID> joinOn = ConcurrentHashMap.newKeySet();

    public StarFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
        this.styleKey = new NamespacedKey(plugin, "star_nick_style");
    }

    private Messages m() {
        return plugin.messages();
    }

    public void load() {
        joinOn.clear();
        for (UUID u : plugin.perks().owners(JOIN)) {
            if (plugin.perks().counter(u, JOIN_OFF) == 0) joinOn.add(u);
        }
        if (plugin.telegram().suppressJoin(joinOn::contains)) {
            plugin.getLogger().info("звёздный вход: своё сообщение в телеграм вместо обычного");
        }
    }

    // --- звёздный ник ---

    /** id стиля → открывающий тег minimessage, по порядку из конфига. */
    public Map<String, String> styles() {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("astra.star-nick.styles");
        if (sec != null) {
            for (String id : sec.getKeys(false)) out.put(id, sec.getString(id, ""));
        }
        return out;
    }

    private static String close(String open) {
        Matcher mt = TAG.matcher(open);
        return mt.find() ? "</" + mt.group(1) + ">" : "";
    }

    public Component preview(String styleId, String name) {
        String open = styles().getOrDefault(styleId, "");
        return MiniMessage.miniMessage().deserialize(open + "<name>" + close(open), Placeholder.unparsed("name", name));
    }

    public String style(Player p) {
        return p.getPersistentDataContainer().get(styleKey, PersistentDataType.STRING);
    }

    /** Применить стиль (null — обычный ник). */
    public void applyNick(Player p, String styleId) {
        String open = styleId == null ? null : styles().get(styleId);
        String cmd;
        if (open == null) {
            p.getPersistentDataContainer().remove(styleKey);
            cmd = plugin.getConfig().getString("astra.star-nick.clear-command", "nickname other {player} clear");
        } else {
            p.getPersistentDataContainer().set(styleKey, PersistentDataType.STRING, styleId);
            cmd = plugin.getConfig().getString("astra.star-nick.command", "nickname other {player} {nick}")
                    .replace("{nick}", open + p.getName() + close(open));
        }
        cmd = cmd.replace("{player}", p.getName());
        if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    public void openStyles(Player p, Menu back) {
        new StyleMenu(back).open(p);
    }

    private final class StyleMenu extends Menu {
        private final Menu back;

        StyleMenu(Menu back) {
            super(3, plugin.messages().get("astra.star-nick.menu-title"));
            this.back = back;
        }

        @Override
        protected void render(Player p) {
            var pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
            for (int i = 0; i < 27; i++) set(i, pane, null);
            String current = style(p);
            int slot = 10;
            for (String id : styles().keySet()) {
                if (slot > 16) break;
                boolean sel = id.equals(current);
                List<Component> lore = new ArrayList<>();
                lore.add(m().item("astra.star-nick.style." + id));
                lore.add(m().item(sel ? "cosmetics.selected" : "astra.star-nick.click"));
                var icon = Items.icon(Material.NAME_TAG,
                        preview(id, p.getName()).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false), lore);
                if (sel) Items.glow(icon);
                set(slot++, icon, v -> {
                    applyNick(v, id);
                    m().send(v, "astra.star-nick.selected", Placeholder.component("nick", preview(id, v.getName())));
                    Fx.sound(v.getLocation(), "minecraft:block.amethyst_block.chime", 1f, 1.4f);
                    open(v);
                });
            }
            set(22, Items.icon(Material.BARRIER, m().item("astra.star-nick.off"), List.of()), v -> {
                applyNick(v, null);
                m().send(v, "astra.star-nick.removed");
                open(v);
            });
            if (back != null) set(18, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
        }
    }

    // --- звёздный вход ---

    public boolean joinEnabled(UUID uuid) {
        return joinOn.contains(uuid);
    }

    public void ownedJoin(UUID uuid) {
        plugin.perks().set(uuid, JOIN_OFF, 0);
        joinOn.add(uuid);
    }

    public boolean toggleJoin(Player p) {
        UUID u = p.getUniqueId();
        boolean on = !joinOn.contains(u);
        plugin.perks().set(u, JOIN_OFF, on ? 0 : 1);
        if (on) joinOn.add(u);
        else joinOn.remove(u);
        return on;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!joinOn.contains(p.getUniqueId()) || !p.hasPlayedBefore()) return;
        String html = m().raw("astra.star-join.telegram").replace("{name}", p.getName());
        plugin.telegram().sendHtml(html);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            starfall(p);
            Component bar = m().get("astra.star-join.broadcast", Placeholder.unparsed("name", p.getName()));
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(p)) other.sendActionBar(bar);
            }
        }, 30L);
    }

    /** Звездопад вокруг игрока. */
    public void starfall(Player p) {
        Location at = p.getLocation().add(0, 1, 0);
        var w = at.getWorld();
        w.spawnParticle(Particle.FIREWORK, at, 60, 0.6, 1.0, 0.6, 0.08);
        w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1.5, 0), 40, 1.2, 0.6, 1.2, 0.02);
        w.spawnParticle(Particle.DUST, at, 40, 0.8, 1.0, 0.8, 0, new Particle.DustOptions(Color.fromRGB(0xFFD166), 1.2f));
        w.spawnParticle(Particle.DUST, at, 40, 0.8, 1.0, 0.8, 0, new Particle.DustOptions(Color.fromRGB(0xFF9CC2), 1.2f));
        Fx.sound(at, "minecraft:entity.firework_rocket.twinkle", 1f, 1.2f);
        Fx.sound(at, "minecraft:block.amethyst_block.chime", 1f, 0.8f);
    }
}
