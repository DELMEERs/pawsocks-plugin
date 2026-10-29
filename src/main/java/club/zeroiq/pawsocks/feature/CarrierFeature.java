package club.zeroiq.pawsocks.feature;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Переноска: пкм по своему питомцу — он «садится» внутрь (сохраняется целиком, с именем,
 * ошейником, инвентарём), пкм по блоку — выпустить. Удобно перевезти кота через весь мир.
 */
public final class CarrierFeature implements Listener {

    private static final String NAME_TRANSLATE = "t:";

    private final PawsocksPlugin plugin;
    private final NamespacedKey recipeKey;
    private final NamespacedKey carrierKey;
    private final NamespacedKey petKey;
    private final NamespacedKey petNameKey;

    public CarrierFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "carrier");
        this.carrierKey = new NamespacedKey(plugin, "carrier");
        this.petKey = new NamespacedKey(plugin, "carrier_pet");
        this.petNameKey = new NamespacedKey(plugin, "carrier_pet_name");
    }

    private Messages m() {
        return plugin.messages();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("carrier.enabled", true);
    }

    public void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, empty());
        recipe.shape("SLS", "L L", "LLL");
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('L', Material.LEATHER);
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
    }

    public ItemStack empty() {
        ItemStack item = new ItemStack(Material.LEATHER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(m().item("carrier.name"));
        meta.lore(m().lore("carrier.lore-empty"));
        meta.setMaxStackSize(1);
        meta.setItemModel(NamespacedKey.minecraft("bundle"));
        meta.getPersistentDataContainer().set(carrierKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack full(byte[] pet, String storedName) {
        ItemStack item = empty();
        ItemMeta meta = item.getItemMeta();
        Component name = petName(storedName);
        meta.displayName(m().item("carrier.name-full", Placeholder.component("pet", name)));
        meta.lore(m().lore("carrier.lore-full", Placeholder.component("pet", name)));
        meta.setItemModel(NamespacedKey.minecraft("pink_bundle"));
        meta.setEnchantmentGlintOverride(true);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(petKey, PersistentDataType.BYTE_ARRAY, pet);
        pdc.set(petNameKey, PersistentDataType.STRING, storedName);
        item.setItemMeta(meta);
        return item;
    }

    private static Component petName(String stored) {
        if (stored.startsWith(NAME_TRANSLATE)) {
            return Component.translatable(stored.substring(NAME_TRANSLATE.length()));
        }
        return Component.text(stored);
    }

    private static String storedName(Entity e) {
        Component custom = e.customName();
        if (custom != null) return PlainTextComponentSerializer.plainText().serialize(custom);
        return NAME_TRANSLATE + e.getType().translationKey();
    }

    public boolean isCarrier(ItemStack item) {
        return item != null && item.getType() == Material.LEATHER && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(carrierKey, PersistentDataType.BYTE);
    }

    private boolean hasPet(ItemStack item) {
        return isCarrier(item) && item.getItemMeta().getPersistentDataContainer().has(petKey, PersistentDataType.BYTE_ARRAY);
    }

    /** Посадить питомца в переноску. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCatch(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isCarrier(hand)) return;
        event.setCancelled(true);
        if (!enabled() || hasPet(hand)) return;

        Entity pet = event.getRightClicked();
        if (!(pet instanceof Tameable t) || !t.isTamed() || !player.getUniqueId().equals(t.getOwnerUniqueId())) {
            m().actionBar(player, "carrier.not-yours");
            return;
        }
        if (!pet.getPassengers().isEmpty()) {
            m().actionBar(player, "carrier.busy");
            return;
        }
        if (pet instanceof LivingEntity living && living.isLeashed()) {
            living.setLeashHolder(null);
            pet.getWorld().dropItemNaturally(pet.getLocation(), new ItemStack(Material.LEAD));
        }

        byte[] data;
        try {
            data = Bukkit.getUnsafe().serializeEntity(pet);
        } catch (Exception e) {
            plugin.getLogger().warning("не удалось сохранить питомца в переноску: " + e.getMessage());
            m().actionBar(player, "carrier.failed");
            return;
        }
        String name = storedName(pet);
        Fx.sound(pet.getLocation(), "minecraft:item.bundle.insert", 1f, 1f);
        pet.remove();
        player.getInventory().setItemInMainHand(full(data, name));
        m().actionBar(player, "carrier.caught", Placeholder.component("pet", petName(name)));
    }

    /** Выпустить питомца: пкм по блоку. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRelease(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = event.getItem();
        if (!isCarrier(hand)) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        if (!enabled() || !hasPet(hand)) return;
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        PersistentDataContainer pdc = hand.getItemMeta().getPersistentDataContainer();
        byte[] data = pdc.get(petKey, PersistentDataType.BYTE_ARRAY);
        String name = pdc.getOrDefault(petNameKey, PersistentDataType.STRING, "?");
        if (data == null) return;

        Location at = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        at.setYaw(player.getLocation().getYaw() + 180);
        boolean ok;
        try {
            Entity pet = Bukkit.getUnsafe().deserializeEntity(data, at.getWorld(), true);
            ok = pet.spawnAt(at);
        } catch (Exception e) {
            plugin.getLogger().warning("не удалось выпустить питомца из переноски: " + e.getMessage());
            ok = false;
        }
        if (!ok) {
            m().actionBar(player, "carrier.failed");
            return;
        }
        player.getInventory().setItemInMainHand(empty());
        Fx.sound(at, "minecraft:item.bundle.drop_contents", 1f, 1f);
        m().actionBar(player, "carrier.released", Placeholder.component("pet", petName(name)));
    }

    /** Переноску нельзя пустить на крафт как обычную кожу — потеряешь питомца. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() instanceof ShapedRecipe shaped && shaped.getKey().equals(recipeKey)) {
            if (!enabled()) event.getInventory().setResult(null);
        }
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isCarrier(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(recipeKey);
    }
}
