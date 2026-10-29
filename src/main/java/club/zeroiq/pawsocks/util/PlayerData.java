package club.zeroiq.pawsocks.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Всё, что плагин помнит об игроке, хранится прямо в его данных (PDC),
 * поэтому сохраняется вместе с миром и не требует своей базы.
 */
public final class PlayerData {

    private static NamespacedKey cozy;
    private static NamespacedKey hugs;
    private static NamespacedKey trail;
    private static NamespacedKey cozyBar;
    private static NamespacedKey hugsAllowed;

    private PlayerData() {
    }

    public static void init(Plugin plugin) {
        cozy = new NamespacedKey(plugin, "cozy");
        hugs = new NamespacedKey(plugin, "hugs");
        trail = new NamespacedKey(plugin, "trail");
        cozyBar = new NamespacedKey(plugin, "cozy_bar");
        hugsAllowed = new NamespacedKey(plugin, "hugs_allowed");
    }

    private static PersistentDataContainer pdc(Player p) {
        return p.getPersistentDataContainer();
    }

    public static int cozy(Player p) {
        return pdc(p).getOrDefault(cozy, PersistentDataType.INTEGER, 0);
    }

    public static void cozy(Player p, int value) {
        pdc(p).set(cozy, PersistentDataType.INTEGER, Math.max(0, Math.min(100, value)));
    }

    public static int hugs(Player p) {
        return pdc(p).getOrDefault(hugs, PersistentDataType.INTEGER, 0);
    }

    public static void addHug(Player p) {
        pdc(p).set(hugs, PersistentDataType.INTEGER, hugs(p) + 1);
    }

    /** id выбранных следов или null, если следы выключены. */
    public static String trail(Player p) {
        return pdc(p).get(trail, PersistentDataType.STRING);
    }

    public static void trail(Player p, String id) {
        if (id == null) {
            pdc(p).remove(trail);
        } else {
            pdc(p).set(trail, PersistentDataType.STRING, id);
        }
    }

    public static boolean cozyBar(Player p) {
        return pdc(p).getOrDefault(cozyBar, PersistentDataType.BOOLEAN, true);
    }

    public static void cozyBar(Player p, boolean value) {
        pdc(p).set(cozyBar, PersistentDataType.BOOLEAN, value);
    }

    public static boolean hugsAllowed(Player p) {
        return pdc(p).getOrDefault(hugsAllowed, PersistentDataType.BOOLEAN, true);
    }

    public static void hugsAllowed(Player p, boolean value) {
        pdc(p).set(hugsAllowed, PersistentDataType.BOOLEAN, value);
    }
}
