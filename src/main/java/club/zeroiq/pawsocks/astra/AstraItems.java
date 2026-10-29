package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Вещи из магазина астр. Чары — наши собственные: метка в данных предмета, строчка в описании
 * и блеск. Любая вещь с такой меткой (astra_item) не продаётся ни серверу, ни на аукционе.
 */
public final class AstraItems {

    public enum Ench {
        DRILL("drill", Material.DIAMOND_PICKAXE, m -> m.name().endsWith("_PICKAXE")),
        TIMBER("timber", Material.DIAMOND_AXE, m -> m.name().endsWith("_AXE")),
        SPAWNER("spawner", Material.SPAWNER, m -> m.name().endsWith("_PICKAXE"));

        public final String id;
        public final Material icon;
        private final Predicate<Material> target;

        Ench(String id, Material icon, Predicate<Material> target) {
            this.id = id;
            this.icon = icon;
            this.target = target;
        }

        public boolean fits(Material m) {
            return target.test(m);
        }

        public static Ench byId(String id) {
            for (Ench e : values()) if (e.id.equals(id)) return e;
            return null;
        }
    }

    private final PawsocksPlugin plugin;
    private final NamespacedKey enchKey;
    private final NamespacedKey lampKey;
    private final NamespacedKey spawnerKey;

    public AstraItems(PawsocksPlugin plugin) {
        this.plugin = plugin;
        this.enchKey = new NamespacedKey(plugin, "astra_ench");
        this.lampKey = new NamespacedKey(plugin, "star_lamp");
        this.spawnerKey = new NamespacedKey(plugin, "spawner_type");
    }

    private NamespacedKey astraKey() {
        return plugin.guard().astraKey();
    }

    public Set<Ench> enchants(ItemStack item) {
        Set<Ench> out = new LinkedHashSet<>();
        if (item == null || !item.hasItemMeta()) return out;
        String raw = item.getItemMeta().getPersistentDataContainer().get(enchKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return out;
        for (String id : raw.split(",")) {
            Ench e = Ench.byId(id.trim().toLowerCase(Locale.ROOT));
            if (e != null) out.add(e);
        }
        return out;
    }

    public boolean has(ItemStack item, Ench ench) {
        return enchants(item).contains(ench);
    }

    /** Нанести чару на предмет (предмет меняется на месте). */
    public void apply(ItemStack item, Ench ench) {
        Messages m = plugin.messages();
        Set<Ench> all = enchants(item);
        all.add(ench);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(enchKey, PersistentDataType.STRING, String.join(",", all.stream().map(e -> e.id).toList()));
        pdc.set(astraKey(), PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(m.item("astra.ench." + ench.id + ".lore-line"));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    // --- звёздный фонарь ---

    public ItemStack lamp() {
        Messages m = plugin.messages();
        ItemStack item = new ItemStack(Material.PEARLESCENT_FROGLIGHT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(m.item("astra.lamp.name"));
        meta.lore(m.lore("astra.lamp.lore", Placeholder.unparsed("radius", String.valueOf(plugin.lamps().radius()))));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(lampKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(astraKey(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isLamp(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(lampKey, PersistentDataType.BYTE);
    }

    // --- спавнер, добытый киркой для спавнеров ---

    /** Спавнер с мобом. Сам он не «астральный»: его можно передать или выставить на аукцион. */
    public ItemStack spawner(EntityType type) {
        Messages m = plugin.messages();
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta meta = item.getItemMeta();
        if (type != null) {
            meta.displayName(m.item("astra.spawner-item.name", Placeholder.component("mob", Component.translatable(type.translationKey()))));
            meta.getPersistentDataContainer().set(spawnerKey, PersistentDataType.STRING, type.getKey().toString());
        } else {
            meta.displayName(m.item("astra.spawner-item.empty"));
        }
        meta.lore(m.lore("astra.spawner-item.lore"));
        item.setItemMeta(meta);
        return item;
    }

    /** Какой моб записан в спавнере (null — пустой или обычный спавнер). */
    public EntityType spawnerType(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(spawnerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        NamespacedKey key = NamespacedKey.fromString(raw);
        return key == null ? null : org.bukkit.Registry.ENTITY_TYPE.get(key);
    }

    /** Значок астр в меню — осколок аметиста с блеском. */
    public ItemStack astraIcon(Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    public static List<Ench> all() {
        return Arrays.asList(Ench.values());
    }
}
