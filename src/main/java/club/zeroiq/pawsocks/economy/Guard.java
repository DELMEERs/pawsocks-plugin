package club.zeroiq.pawsocks.economy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * Что нельзя продать серверу, выставить на аукцион или сдать по заказу:
 * яйцо дракона, вещи за астры (и всё, внутри чего они лежат), служебные предметы.
 */
public final class Guard {

    /** Предметы, которых не бывает в обычной игре или которые слишком особенные. */
    private static final Set<Material> NEVER = EnumSet.noneOf(Material.class);

    static {
        for (String name : new String[]{
                "DRAGON_EGG", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
                "COMMAND_BLOCK_MINECART", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "BARRIER", "LIGHT",
                "DEBUG_STICK", "KNOWLEDGE_BOOK", "BEDROCK", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE",
                "SPAWNER", "TRIAL_SPAWNER", "VAULT", "PETRIFIED_OAK_SLAB", "TEST_BLOCK", "TEST_INSTANCE_BLOCK"}) {
            Material m = Material.getMaterial(name);
            if (m != null) NEVER.add(m);
        }
        for (Material m : Material.values()) {
            if (m.name().endsWith("_SPAWN_EGG")) NEVER.add(m);
        }
    }

    private final NamespacedKey astraItem;
    private final NamespacedKey carrier;

    public Guard(Plugin plugin) {
        this.astraItem = new NamespacedKey(plugin, "astra_item");
        this.carrier = new NamespacedKey(plugin, "carrier");
    }

    public NamespacedKey astraKey() {
        return astraItem;
    }

    public static boolean neverSold(Material m) {
        return NEVER.contains(m);
    }

    public boolean isAstra(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(astraItem, PersistentDataType.BYTE);
    }

    private boolean isCarrier(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(carrier, PersistentDataType.BYTE);
    }

    /** Нельзя на аукцион и в заказы: яйцо дракона, вещи за астры — в том числе внутри шалкеров и мешков. */
    public boolean blockedForTrade(ItemStack item) {
        return blocked(item, 0);
    }

    private boolean blocked(ItemStack item, int depth) {
        if (item == null || item.getType().isAir()) return false;
        if (item.getType() == Material.DRAGON_EGG || isAstra(item)) return true;
        if (depth > 3 || !item.hasItemMeta()) return false;
        for (ItemStack inner : contents(item.getItemMeta())) {
            if (blocked(inner, depth + 1)) return true;
        }
        return false;
    }

    /** Нельзя продать серверу: всё запрещённое + служебное + переноски + контейнеры с вещами внутри. */
    public boolean blockedForSell(ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        if (neverSold(item.getType()) || isAstra(item) || isCarrier(item)) return true;
        return item.hasItemMeta() && contents(item.getItemMeta()).length > 0;
    }

    private static ItemStack[] contents(ItemMeta meta) {
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            return bundle.getItems().toArray(ItemStack[]::new);
        }
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container container) {
            ItemStack[] all = container.getInventory().getContents();
            int n = 0;
            for (ItemStack s : all) if (s != null && !s.getType().isAir()) n++;
            ItemStack[] out = new ItemStack[n];
            int i = 0;
            for (ItemStack s : all) if (s != null && !s.getType().isAir()) out[i++] = s;
            return out;
        }
        return new ItemStack[0];
    }
}
