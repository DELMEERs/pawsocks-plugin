package club.zeroiq.pawsocks.cosmetics;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.data.Perks;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.feature.Trail;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.gui.PawMenu;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Магазин косметики за лапки: цвета следов, ауры над головой, символ после ника в чате и табе.
 * Купленное — навсегда, выбрать или снять можно бесплатно.
 */
public final class CosmeticsFeature implements Listener {

    public enum Aura {
        HEARTS("hearts", Particle.HEART, Material.PINK_DYE),
        NOTES("notes", Particle.NOTE, Material.NOTE_BLOCK),
        PETALS("petals", Particle.CHERRY_LEAVES, Material.CHERRY_SAPLING),
        SNOW("snow", Particle.SNOWFLAKE, Material.SNOWBALL),
        SPARKS("sparks", Particle.END_ROD, Material.END_ROD),
        GLOW("glow", Particle.GLOW, Material.GLOW_INK_SAC);

        public final String id;
        public final Particle particle;
        public final Material icon;

        Aura(String id, Particle particle, Material icon) {
            this.id = id;
            this.particle = particle;
            this.icon = icon;
        }

        static Aura byId(String id) {
            for (Aura a : values()) if (a.id.equals(id)) return a;
            return null;
        }
    }

    /** Символы после ника — только те, что есть в родном шрифте майнкрафта. */
    public enum Suffix {
        NOTE("note", "♪", "#FF9CC2"),
        MUSIC("music", "♫", "#B79CFF"),
        SUN("sun", "☀", "#FFD166"),
        MOON("moon", "☽", "#C9C3D9"),
        SNOW("snow", "❄", "#7CC4FF"),
        ANCHOR("anchor", "⚓", "#7CC4FF"),
        YINYANG("yinyang", "☯", "#F4F1FA"),
        DIAMOND("diamond", "◆", "#9BE38A"),
        SKULL("skull", "☠", "#C9C3D9"),
        SWORDS("swords", "⚔", "#FF7171");

        public final String id;
        public final String symbol;
        public final String color;

        Suffix(String id, String symbol, String color) {
            this.id = id;
            this.symbol = symbol;
            this.color = color;
        }

        static Suffix byId(String id) {
            for (Suffix s : values()) if (s.id.equals(id)) return s;
            return null;
        }
    }

    private static final int SUFFIX_PRIORITY = 10;

    private final PawsocksPlugin plugin;
    private final Map<UUID, Set<String>> owned = new ConcurrentHashMap<>();
    private final NamespacedKey auraKey;
    private final NamespacedKey suffixKey;
    private BukkitTask auraTask;

    public CosmeticsFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
        this.auraKey = new NamespacedKey(plugin, "aura");
        this.suffixKey = new NamespacedKey(plugin, "suffix");
    }

    private Messages m() {
        return plugin.messages();
    }

    private Perks perks() {
        return plugin.perks();
    }

    /** Есть ли у игрока эта покупка (из кэша — можно звать часто, например на каждый шаг). */
    public boolean owns(Player p, String item) {
        Set<String> set = owned.get(p.getUniqueId());
        if (set == null) {
            set = ConcurrentHashMap.newKeySet();
            set.addAll(perks().owned(p.getUniqueId()));
            owned.put(p.getUniqueId(), set);
        }
        return set.contains(item);
    }

    private long price(String kind) {
        return Money.parse(plugin.getConfig().getString("economy.cosmetics." + kind, "200"));
    }

    // --- ауры ---

    public void start() {
        stop();
        auraTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Aura a = Aura.byId(p.getPersistentDataContainer().get(auraKey, PersistentDataType.STRING));
                if (a == null || !owns(p, "aura:" + a.id)) continue;
                if (p.getGameMode() == GameMode.SPECTATOR || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) continue;
                Location at = p.getLocation().add(0, p.getHeight() + 0.45, 0);
                p.getWorld().spawnParticle(a.particle, at, 1, 0.3, 0.1, 0.3, 0);
            }
        }, 20L, 12L);
    }

    public void stop() {
        if (auraTask != null) {
            auraTask.cancel();
            auraTask = null;
        }
    }

    // --- символ после ника (через luckperms, его показывает flectonepulse) ---

    private boolean luckperms() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    private void applySuffix(Player p, Suffix s) {
        if (!luckperms()) return;
        String cmd = s == null
                ? "lp user " + p.getUniqueId() + " meta removesuffix " + SUFFIX_PRIORITY
                : "lp user " + p.getUniqueId() + " meta setsuffix " + SUFFIX_PRIORITY + " \" <color:" + s.color + ">" + s.symbol + "</color>\"";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        owned.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        owned.remove(event.getPlayer().getUniqueId());
    }

    // --- покупка ---

    private void buy(Player p, String item, long price, Runnable then) {
        Perks.BuyResult r = perks().buy(p.getUniqueId(), item, price, item);
        switch (r) {
            case OK -> {
                owned.remove(p.getUniqueId());
                m().send(p, "cosmetics.bought", Money.tag(m(), "price", price));
                Fx.sound(p.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.5f);
                then.run();
            }
            case ALREADY -> then.run();
            case NO_MONEY -> m().send(p, "eco.not-enough", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())));
        }
    }

    // --- меню ---

    public void open(Player p) {
        new CosmeticsMenu().open(p);
    }

    private static ItemStack pane() {
        return Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private List<Component> stateLore(boolean has, boolean selected, long price) {
        if (selected) return List.of(m().item("cosmetics.selected"));
        if (has) return List.of(m().item("cosmetics.click-select"));
        return m().lore("cosmetics.click-buy", Money.tag(m(), "price", price));
    }

    private final class CosmeticsMenu extends Menu {
        CosmeticsMenu() {
            super(6, plugin.messages().get("cosmetics.title"));
        }

        @Override
        protected void render(Player p) {
            for (int i = 0; i < 54; i++) set(i, pane(), null);

            // следы, которые можно купить
            set(0, Items.icon(Material.BONE, m().item("cosmetics.trails-header"), m().lore("cosmetics.trails-header-lore")), null);
            int slot = 1;
            long trailPrice = price("trail");
            for (Trail t : Trail.values()) {
                if (!t.buyable) continue;
                boolean has = plugin.trails().canUse(p, t);
                boolean sel = t.id.equals(club.zeroiq.pawsocks.util.PlayerData.trail(p));
                ItemStack icon = Items.icon(t.icon, m().item("menu.trail-name", Placeholder.unparsed("name", m().raw("trails.names." + t.id))),
                        stateLore(has, sel, trailPrice));
                if (sel) Items.glow(icon);
                set(slot++, icon, v -> {
                    Runnable select = () -> {
                        club.zeroiq.pawsocks.util.PlayerData.trail(v, t.id);
                        m().send(v, "trails.selected", Placeholder.unparsed("name", m().raw("trails.names." + t.id)));
                        open(v);
                    };
                    if (plugin.trails().canUse(v, t)) select.run();
                    else buy(v, "trail:" + t.id, trailPrice, select);
                });
            }

            // ауры
            set(18, Items.icon(Material.END_ROD, m().item("cosmetics.auras-header"), m().lore("cosmetics.auras-header-lore")), null);
            long auraPrice = price("aura");
            String current = p.getPersistentDataContainer().get(auraKey, PersistentDataType.STRING);
            slot = 19;
            for (Aura a : Aura.values()) {
                boolean has = owns(p, "aura:" + a.id);
                boolean sel = a.id.equals(current);
                ItemStack icon = Items.icon(a.icon, m().item("cosmetics.aura." + a.id), stateLore(has, sel, auraPrice));
                if (sel) Items.glow(icon);
                set(slot++, icon, v -> {
                    Runnable select = () -> {
                        v.getPersistentDataContainer().set(auraKey, PersistentDataType.STRING, a.id);
                        m().send(v, "cosmetics.aura-selected", Placeholder.component("name", m().get("cosmetics.aura." + a.id)));
                        open(v);
                    };
                    if (owns(v, "aura:" + a.id)) select.run();
                    else buy(v, "aura:" + a.id, auraPrice, select);
                });
            }
            set(26, Items.icon(Material.BARRIER, m().item("cosmetics.aura-off"), List.of()), v -> {
                v.getPersistentDataContainer().remove(auraKey);
                m().send(v, "cosmetics.aura-removed");
                open(v);
            });

            // символ после ника
            set(36, Items.icon(Material.NAME_TAG, m().item("cosmetics.suffix-header"), m().lore("cosmetics.suffix-header-lore")), null);
            long suffixPrice = price("suffix");
            String curSuffix = p.getPersistentDataContainer().get(suffixKey, PersistentDataType.STRING);
            slot = 37;
            for (Suffix s : Suffix.values()) {
                if (slot == 44) slot = 46; // перенос на следующий ряд, мимо краёв
                boolean has = owns(p, "suffix:" + s.id);
                boolean sel = s.id.equals(curSuffix);
                ItemStack icon = Items.icon(Material.PAPER,
                        m().item("cosmetics.suffix-name", Placeholder.parsed("symbol", "<color:" + s.color + ">" + s.symbol + "</color>")),
                        stateLore(has, sel, suffixPrice));
                if (sel) Items.glow(icon);
                set(slot++, icon, v -> {
                    Runnable select = () -> {
                        v.getPersistentDataContainer().set(suffixKey, PersistentDataType.STRING, s.id);
                        applySuffix(v, s);
                        m().send(v, "cosmetics.suffix-selected", Placeholder.parsed("symbol", "<color:" + s.color + ">" + s.symbol + "</color>"));
                        open(v);
                    };
                    if (owns(v, "suffix:" + s.id)) select.run();
                    else buy(v, "suffix:" + s.id, suffixPrice, select);
                });
            }
            set(53, Items.icon(Material.BARRIER, m().item("cosmetics.suffix-off"), List.of()), v -> {
                v.getPersistentDataContainer().remove(suffixKey);
                applySuffix(v, null);
                m().send(v, "cosmetics.suffix-removed");
                open(v);
            });

            set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> new PawMenu(plugin).open(v));
            set(49, Items.icon(Material.GOLD_INGOT, m().item("ah.balance.name"),
                    m().lore("ah.balance.lore", Money.tag(m(), "balance", plugin.economy().balance(p.getUniqueId())))), null);
        }
    }

    public List<String> ownedList(Player p) {
        return new ArrayList<>(perks().owned(p.getUniqueId()));
    }
}
