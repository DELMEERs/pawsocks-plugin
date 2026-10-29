package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.util.Fx;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Звёздный фонарь: блок, рядом с которым сами по себе не появляются враждебные мобы.
 * Спавнеры и фермы на спавнерах работают как обычно. Сломать фонарь может только хозяин —
 * тогда он возвращается предметом.
 */
public final class Lamps implements Listener {

    public record Lamp(String world, int x, int y, int z, UUID owner) {
        boolean at(Block b) {
            return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getName().equals(world);
        }
    }

    private final PawsocksPlugin plugin;
    private final Database db;
    private final Map<String, List<Lamp>> byWorld = new HashMap<>();
    private BukkitTask task;

    public Lamps(PawsocksPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public int radius() {
        return Math.max(1, plugin.getConfig().getInt("astra.lamp-radius", 32));
    }

    public void load() {
        byWorld.clear();
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, owner FROM lamps");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Lamp l = new Lamp(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), UUID.fromString(rs.getString(5)));
                    byWorld.computeIfAbsent(l.world(), k -> new ArrayList<>()).add(l);
                }
            }
            return null;
        });
    }

    public void start() {
        stop();
        // мерцание над фонарями, рядом с которыми кто-то есть
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<String, List<Lamp>> e : byWorld.entrySet()) {
                World w = Bukkit.getWorld(e.getKey());
                if (w == null || w.getPlayers().isEmpty()) continue;
                for (Lamp l : e.getValue()) {
                    if (!w.isChunkLoaded(l.x() >> 4, l.z() >> 4)) continue;
                    Location at = new Location(w, l.x() + 0.5, l.y() + 1.2, l.z() + 0.5);
                    boolean near = false;
                    for (Player p : w.getPlayers()) {
                        if (p.getLocation().distanceSquared(at) < 48 * 48) {
                            near = true;
                            break;
                        }
                    }
                    if (near) w.spawnParticle(Particle.END_ROD, at, 2, 0.3, 0.2, 0.3, 0.005);
                }
            }
        }, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public Lamp at(Block b) {
        List<Lamp> list = byWorld.get(b.getWorld().getName());
        if (list == null) return null;
        for (Lamp l : list) if (l.at(b)) return l;
        return null;
    }

    /** Есть ли фонарь в радиусе от точки. */
    public boolean protects(Location loc) {
        List<Lamp> list = byWorld.get(loc.getWorld().getName());
        if (list == null || list.isEmpty()) return false;
        double r2 = (double) radius() * radius();
        for (Lamp l : list) {
            double dx = loc.getX() - (l.x() + 0.5), dy = loc.getY() - (l.y() + 0.5), dz = loc.getZ() - (l.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    private void add(Block b, UUID owner) {
        Lamp l = new Lamp(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), owner);
        db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT OR REPLACE INTO lamps (world, x, y, z, owner, placed) VALUES (?, ?, ?, ?, ?, ?)",
                    l.world(), l.x(), l.y(), l.z(), owner.toString(), System.currentTimeMillis())) {
                ps.executeUpdate();
            }
            return null;
        });
        byWorld.computeIfAbsent(l.world(), k -> new ArrayList<>()).add(l);
    }

    private void remove(Lamp l) {
        db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "DELETE FROM lamps WHERE world = ? AND x = ? AND y = ? AND z = ?", l.world(), l.x(), l.y(), l.z())) {
                ps.executeUpdate();
            }
            return null;
        });
        List<Lamp> list = byWorld.get(l.world());
        if (list != null) list.remove(l);
    }

    // --- события ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.astraItems().isLamp(event.getItemInHand())) return;
        Block b = event.getBlockPlaced();
        add(b, event.getPlayer().getUniqueId());
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.END_ROD, at, 30, 0.4, 0.4, 0.4, 0.06);
        Fx.sound(at, "minecraft:block.amethyst_block.resonate", 1f, 1.5f);
        plugin.messages().send(event.getPlayer(), "astra.lamp.placed", Placeholder.unparsed("radius", String.valueOf(radius())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Lamp l = at(event.getBlock());
        if (l == null) return;
        Player p = event.getPlayer();
        if (!l.owner().equals(p.getUniqueId()) && !p.hasPermission("pawsocks.astra.admin")) {
            event.setCancelled(true);
            String owner = Bukkit.getOfflinePlayer(l.owner()).getName();
            plugin.messages().send(p, "astra.lamp.not-yours", Placeholder.unparsed("name", owner == null ? "?" : owner));
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        remove(l);
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack item = plugin.astraItems().lamp();
            Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
            Bukkit.getScheduler().runTask(plugin, () -> at.getWorld().dropItemNaturally(at, item));
        }
        plugin.messages().send(p, "astra.lamp.removed");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason r = event.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.PATROL
                && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        if (!(event.getEntity() instanceof Enemy)) return;
        if (protects(event.getLocation())) event.setCancelled(true);
    }

    // фонарь нельзя сдвинуть поршнем, взорвать или утащить эндерменом — иначе он пропал бы

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (at(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (at(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) {
        if (at(event.getBlock()) != null) event.setCancelled(true);
    }
}
