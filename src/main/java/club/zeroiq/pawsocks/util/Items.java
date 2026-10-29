package club.zeroiq.pawsocks.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class Items {

    private Items() {
    }

    /** Иконка для меню: название, описание, без лишних подсказок майнкрафта. */
    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    /** Название предмета: своё, если переименован, иначе переводимое (у каждого игрока на его языке). */
    public static Component name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component custom = meta.displayName();
            if (custom != null) return custom;
        }
        return Component.translatable(item.translationKey());
    }

    public static ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }
}
