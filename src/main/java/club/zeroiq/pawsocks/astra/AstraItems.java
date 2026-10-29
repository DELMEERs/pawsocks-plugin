package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
        SMELT("smelt", Material.FURNACE, m -> m.name().endsWith("_PICKAXE")),
        MAGNET("magnet", Material.LODESTONE, m -> {
            String n = m.name();
            return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                    || n.endsWith("_HOE") || n.endsWith("_SWORD") || m == Material.MACE || m == Material.TRIDENT;
        }),
        CATSIGHT("catsight", Material.GOLDEN_HELMET, m -> m.name().endsWith("_HELMET"));

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
    private final NamespacedKey talismanKey;

    public AstraItems(PawsocksPlugin plugin) {
        this.plugin = plugin;
        this.enchKey = new NamespacedKey(plugin, "astra_ench");
        this.talismanKey = new NamespacedKey(plugin, "nine_lives");
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

    // --- талисман «девять жизней» ---

    public ItemStack talisman() {
        Messages m = plugin.messages();
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(m.item("astra.talisman.name"));
        meta.lore(m.lore("astra.talisman.lore"));
        meta.setItemModel(NamespacedKey.minecraft("heart_of_the_sea"));
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(talismanKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(astraKey(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isTalisman(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(talismanKey, PersistentDataType.BYTE);
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
