package club.zeroiq.pawsocks.feature;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Следы лапок: на земле за игроком остаются отпечатки из частиц. */
public final class TrailFeature implements Listener {

    /** Форма отпечатка: {вперёд, вбок} в блоках. Подушечка и четыре пальчика. */
    private static final double[][] PRINT = {
            {0.00, -0.05}, {0.00, 0.05}, {-0.06, 0.00}, {0.04, 0.00},
            {0.16, -0.13}, {0.22, -0.045}, {0.22, 0.045}, {0.16, 0.13},
    };

    private final PawsocksPlugin plugin;
    private final Map<UUID, Location> lastPrint = new HashMap<>();
    private final Map<UUID, Long> steps = new HashMap<>();

    public TrailFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean canUse(Player player, Trail trail) {
        if (!player.hasPermission("pawsocks.trails")) return false;
        return !trail.premium || player.hasPermission("pawsocks.trails.premium");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("trails.enabled", true)) return;
        Player player = event.getPlayer();
        Trail trail = Trail.byId(PlayerData.trail(player));
        if (trail == null || !canUse(player, trail)) return;
        if (!event.hasChangedPosition()) return;
        if (!shouldPrint(player)) return;

        Location now = event.getTo();
        Location last = lastPrint.get(player.getUniqueId());
        double step = plugin.getConfig().getDouble("trails.step", 1.1);
        if (last != null && last.getWorld() == now.getWorld()) {
            double dx = now.getX() - last.getX();
            double dz = now.getZ() - last.getZ();
            if (dx * dx + dz * dz < step * step) return;
        }
        lastPrint.put(player.getUniqueId(), now.clone());
        long n = steps.merge(player.getUniqueId(), 1L, Long::sum);
        print(now, trail.color(n), n % 2 == 0 ? 1 : -1);
    }

    private boolean shouldPrint(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        if (player.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        if (player.isFlying() || player.isGliding() || player.isSwimming() || player.isInsideVehicle()) return false;
        if (player.isInWater() || player.isInLava()) return false;
        return ((org.bukkit.entity.Entity) player).isOnGround();
    }

    /** side: +1 правая лапка, -1 левая. */
    private void print(Location at, org.bukkit.Color color, int side) {
        World world = at.getWorld();
        if (world == null) return;
        Vector forward = at.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-4) return;
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Location base = at.clone().add(right.clone().multiply(0.17 * side));
        base.setY(at.getY() + 0.06);

        Particle.DustOptions dust = new Particle.DustOptions(color, 0.6f);
        for (double[] p : PRINT) {
            Location dot = base.clone()
                    .add(forward.clone().multiply(p[0]))
                    .add(right.clone().multiply(p[1]));
            world.spawnParticle(Particle.DUST, dot, 1, 0, 0, 0, 0, dust);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPrint.remove(event.getPlayer().getUniqueId());
        steps.remove(event.getPlayer().getUniqueId());
    }
}
