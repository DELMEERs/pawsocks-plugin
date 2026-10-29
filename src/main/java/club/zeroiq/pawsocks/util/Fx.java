package club.zeroiq.pawsocks.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;

/** Звуки и частицы. Звуки задаются строковыми ключами, чтобы не зависеть от версии api. */
public final class Fx {

    private Fx() {
    }

    public static void sound(Location at, String key, float volume, float pitch) {
        if (at.getWorld() == null) return;
        at.getWorld().playSound(Sound.sound(Key.key(key), Sound.Source.NEUTRAL, volume, pitch), at.getX(), at.getY(), at.getZ());
    }

    public static void hearts(Entity around, int count) {
        Location at = around.getLocation().add(0, around.getHeight() + 0.3, 0);
        around.getWorld().spawnParticle(Particle.HEART, at, count, 0.35, 0.25, 0.35, 0);
    }
}
