package club.zeroiq.pawsocks.feature;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ColorableArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Носки: кожаные ботинки, которые крафтятся из шерсти. Верхний ряд — цвет носков,
 * нижний — цвет полоски (если цвета разные, носки получаются полосатыми).
 */
public final class SocksFeature implements Listener {

    private final PawsocksPlugin plugin;
    private final NamespacedKey recipeKey;
    private final NamespacedKey sockKey;

    public SocksFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "socks");
        this.sockKey = new NamespacedKey(plugin, "sock");
    }

    public NamespacedKey recipeKey() {
        return recipeKey;
    }

    public void registerRecipe() {
        List<Material> wool = new ArrayList<>();
        for (DyeColor c : DyeColor.values()) {
            wool.add(Material.valueOf(c.name() + "_WOOL"));
        }
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, build(DyeColor.PINK, DyeColor.WHITE));
        recipe.shape("T T", "B B");
        recipe.setIngredient('T', new RecipeChoice.MaterialChoice(wool));
        recipe.setIngredient('B', new RecipeChoice.MaterialChoice(wool));
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
    }

    public boolean isSock(ItemStack item) {
        return item != null && item.getType() == Material.LEATHER_BOOTS && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(sockKey, PersistentDataType.BYTE);
    }

    public boolean wearsSocks(Player player) {
        return isSock(player.getInventory().getBoots());
    }

    /** Собирает носки. stripe == color — однотонные. */
    public ItemStack build(DyeColor color, DyeColor stripe) {
        Messages m = plugin.messages();
        String colorId = color.name().toLowerCase(Locale.ROOT);
        String stripeId = stripe.name().toLowerCase(Locale.ROOT);
        ItemStack item = new ItemStack(Material.LEATHER_BOOTS);
        ColorableArmorMeta meta = (ColorableArmorMeta) item.getItemMeta();
        meta.setColor(color.getColor());
        if (stripe == color) {
            meta.displayName(m.item("socks.name",
                    Placeholder.unparsed("color", m.raw("socks.colors." + colorId))));
        } else {
            meta.displayName(m.item("socks.name-striped",
                    Placeholder.unparsed("color", m.raw("socks.colors." + colorId)),
                    Placeholder.unparsed("stripe", m.raw("socks.stripes." + stripeId))));
            meta.setTrim(new ArmorTrim(trimMaterial(stripe), TrimPattern.RIB));
        }
        meta.lore(m.lore("socks.lore"));
        meta.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_DYE);
        meta.getPersistentDataContainer().set(sockKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Ближайший к цвету шерсти материал отделки: он и красит полоски. */
    private static TrimMaterial trimMaterial(DyeColor c) {
        return switch (c) {
            case WHITE -> TrimMaterial.QUARTZ;
            case LIGHT_GRAY -> TrimMaterial.IRON;
            case GRAY, BLACK -> TrimMaterial.NETHERITE;
            case ORANGE, BROWN -> TrimMaterial.COPPER;
            case RED -> TrimMaterial.REDSTONE;
            case YELLOW -> TrimMaterial.GOLD;
            case LIME, GREEN -> TrimMaterial.EMERALD;
            case CYAN, LIGHT_BLUE -> TrimMaterial.DIAMOND;
            case BLUE -> TrimMaterial.LAPIS;
            case PURPLE, MAGENTA, PINK -> TrimMaterial.AMETHYST;
        };
    }

    private static DyeColor woolColor(ItemStack item) {
        if (item == null) return null;
        String name = item.getType().name();
        if (!name.endsWith("_WOOL")) return null;
        try {
            return DyeColor.valueOf(name.substring(0, name.length() - "_WOOL".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Подставляет в результат крафта носки нужного цвета. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shaped) || !shaped.getKey().equals(recipeKey)) return;
        if (!plugin.getConfig().getBoolean("socks.enabled", true)) {
            event.getInventory().setResult(null);
            return;
        }
        DyeColor top = null;
        DyeColor bottom = null;
        for (ItemStack item : event.getInventory().getMatrix()) {
            DyeColor c = woolColor(item);
            if (c == null) continue;
            if (top == null) top = c;
            bottom = c;
        }
        if (top == null) return;
        event.getInventory().setResult(build(top, bottom));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player) || !wearsSocks(player)) return;
        if (!plugin.getConfig().getBoolean("socks.enabled", true)) return;
        double mult = plugin.getConfig().getDouble("socks.fall-damage-multiplier", 0.6);
        event.setDamage(event.getDamage() * mult);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(recipeKey);
    }
}
