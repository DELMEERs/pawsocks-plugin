package club.zeroiq.pawsocks.feature;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Ocelot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Уют: шкала 0–100. Растёт у костра, рядом со своим питомцем, когда сидишь или спишь,
 * в носках, от поглаживания животных и обнимашек. На 100% — лёгкая регенерация,
 * пока игрока никто не бьёт.
 */
public final class CozyFeature implements Listener {

    private static final int BAR_CELLS = 10;

    private final PawsocksPlugin plugin;
    private final Map<UUID, Long> lastDamage = new HashMap<>();
    private final Map<UUID, Long> lastPetting = new HashMap<>();
    private final Set<UUID> full = new HashSet<>();
    private BukkitTask task;

    public CozyFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private boolean enabled() {
        return cfg().getBoolean("cozy.enabled", true);
    }

    /** Прибавить (или отнять) уют и показать шкалу. */
    public void add(Player player, int amount) {
        if (!enabled() || amount == 0) return;
        int before = PlayerData.cozy(player);
        PlayerData.cozy(player, before + amount);
        int after = PlayerData.cozy(player);
        if (after != before && amount > 0) {
            showBar(player, after);
        }
        checkFull(player, after);
    }

    private void tick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        long calmMillis = cfg().getLong("cozy.calm-seconds", 10) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) continue;
            boolean socks = plugin.socks().wearsSocks(player);
            int gain = 0;
            if (nearLitCampfire(player)) gain += cfg().getInt("cozy.gain.campfire", 4);
            if (nearOwnPet(player)) gain += cfg().getInt("cozy.gain.pet", 1);
            if (player.isInsideVehicle() || player.isSleeping()) gain += cfg().getInt("cozy.gain.sitting", 2);

            int before = PlayerData.cozy(player);
            int after;
            if (gain > 0) {
                if (socks) gain += cfg().getInt("cozy.gain.socks", 1);
                after = Math.min(100, before + gain);
            } else {
                int decay = socks ? cfg().getInt("cozy.decay-in-socks", 1) : cfg().getInt("cozy.decay", 2);
                after = Math.max(0, before - decay);
            }
            if (after != before) {
                PlayerData.cozy(player, after);
                if (after > before) showBar(player, after);
            }
            checkFull(player, after);

            Long hit = lastDamage.get(player.getUniqueId());
            boolean calm = hit == null || now - hit >= calmMillis;
            // эффект выдаём заново только когда прошлый закончился: если обновлять его
            // каждую секунду, майнкрафт лечит каждую секунду, а не раз в 2,5 с, как положено
            if (after >= 100 && calm && !player.hasPotionEffect(PotionEffectType.REGENERATION)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0, true, false, true));
            }
        }
    }

    private void checkFull(Player player, int value) {
        if (value >= 100) {
            if (full.add(player.getUniqueId())) {
                plugin.messages().send(player, "cozy.full");
                Fx.sound(player.getLocation(), "minecraft:entity.cat.purr", 0.8f, 1.1f);
            }
        } else if (value < 90) {
            full.remove(player.getUniqueId());
        }
    }

    private void showBar(Player player, int value) {
        if (!PlayerData.cozyBar(player)) return;
        int filled = Math.round(value / (100f / BAR_CELLS));
        String on = plugin.messages().raw("cozy.bar-full");
        String off = plugin.messages().raw("cozy.bar-empty");
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_CELLS; i++) {
            bar.append(i < filled ? on : off);
        }
        plugin.messages().actionBar(player, "cozy.bar",
                Placeholder.parsed("bar", bar.toString()),
                Placeholder.unparsed("percent", String.valueOf(value)));
    }

    private boolean nearLitCampfire(Player player) {
        int r = cfg().getInt("cozy.campfire-radius", 3);
        Location at = player.getLocation();
        Block center = at.getBlock();
        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = center.getRelative(x, y, z);
                    Material type = b.getType();
                    if (type != Material.CAMPFIRE && type != Material.SOUL_CAMPFIRE) continue;
                    BlockData data = b.getBlockData();
                    if (data instanceof Lightable lit && lit.isLit()) return true;
                }
            }
        }
        return false;
    }

    private boolean nearOwnPet(Player player) {
        double r = cfg().getDouble("cozy.pet-radius", 8);
        for (Entity e : player.getNearbyEntities(r, r / 2, r)) {
            if (e instanceof Tameable t && t.isTamed() && player.getUniqueId().equals(t.getOwnerUniqueId())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFinalDamage() <= 0) return;
        lastDamage.put(player.getUniqueId(), System.currentTimeMillis());
        if (enabled()) {
            PlayerData.cozy(player, PlayerData.cozy(player) - cfg().getInt("cozy.damage-penalty", 15));
            checkFull(player, PlayerData.cozy(player));
        }
    }

    /** Шифт + пкм пустой рукой по кошке, собаке и другим милым зверям — погладить. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPet(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!cfg().getBoolean("petting.enabled", true)) return;
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir()) return;
        Entity target = event.getRightClicked();
        if (!isPettable(target)) return;

        // не даём шифт+клику пересадить своего питомца
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long cooldown = cfg().getLong("petting.cooldown-seconds", 3) * 1000L;
        Long last = lastPetting.get(player.getUniqueId());
        if (last != null && now - last < cooldown) return;
        lastPetting.put(player.getUniqueId(), now);

        boolean cat = target instanceof Cat || target instanceof Ocelot;
        Fx.hearts(target, 3);
        Fx.sound(target.getLocation(), petSound(target), 1f, 1f);
        Component custom = target.customName();
        String name = custom != null
                ? PlainTextComponentSerializer.plainText().serialize(custom)
                : plugin.messages().raw(cat ? "petting.default-cat" : "petting.default-other");
        plugin.messages().actionBar(player, cat ? "petting.cat" : "petting.other", Placeholder.unparsed("name", name));
        add(player, cfg().getInt("cozy.petting-bonus", 8));
        plugin.achievements().grant(player, "pet");
        if (plugin.achievements().count(player, "pets") >= cfg().getInt("achievements.pets", 100)) {
            plugin.achievements().grant(player, "pet_100");
        }
    }

    private static String petSound(Entity e) {
        if (e instanceof Cat || e instanceof Ocelot) return "minecraft:entity.cat.purr";
        if (e instanceof Wolf) return "minecraft:entity.wolf.pant";
        if (e instanceof Fox) return "minecraft:entity.fox.sniff";
        if (e instanceof Rabbit) return "minecraft:entity.rabbit.ambient";
        return "minecraft:entity.parrot.ambient";
    }

    private static boolean isPettable(Entity e) {
        if (!(e instanceof LivingEntity)) return false;
        return e instanceof Cat || e instanceof Ocelot || e instanceof Wolf || e instanceof Fox
                || e instanceof Rabbit || e instanceof Parrot;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastDamage.remove(id);
        lastPetting.remove(id);
        full.remove(id);
    }
}
