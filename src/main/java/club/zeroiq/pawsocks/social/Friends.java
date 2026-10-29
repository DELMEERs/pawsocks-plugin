package club.zeroiq.pawsocks.social;

import club.zeroiq.pawsocks.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Друзья и парочки в базе. Дружба хранится в обе стороны (a→b и b→a), пара — тоже.
 * Кэш друзей читается и из сетевого потока (фильтр полосы локатора), поэтому он потокобезопасный.
 */
public final class Friends {

    public record Spot(String world, double x, double y, double z, float yaw, float pitch) {
    }

    private final Database db;
    private final Map<UUID, Set<UUID>> friends = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partners = new ConcurrentHashMap<>();

    public Friends(Database db) {
        this.db = db;
    }

    /** Пары — все сразу (их мало), друзья — по мере входа игроков. */
    public void loadCouples() {
        partners.clear();
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT a, b FROM couples");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) partners.put(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)));
            }
            return null;
        });
    }

    public void cache(UUID uuid) {
        friends.put(uuid, ConcurrentHashMap.newKeySet());
        friends.get(uuid).addAll(load(uuid));
    }

    public void uncache(UUID uuid) {
        friends.remove(uuid);
    }

    private List<UUID> load(UUID uuid) {
        return db.read(c -> {
            List<UUID> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c, "SELECT b FROM friends WHERE a = ? ORDER BY since", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
            }
            return out;
        });
    }

    public List<UUID> list(UUID uuid) {
        return load(uuid);
    }

    /** Быстрая проверка по кэшу (для онлайн-игроков). */
    public boolean areFriends(UUID a, UUID b) {
        Set<UUID> set = friends.get(a);
        if (set != null) return set.contains(b);
        return load(a).contains(b);
    }

    public Set<UUID> cached(UUID uuid) {
        Set<UUID> set = friends.get(uuid);
        return set == null ? Collections.emptySet() : set;
    }

    public void add(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        db.tx(c -> {
            for (UUID[] pair : new UUID[][]{{a, b}, {b, a}}) {
                try (PreparedStatement ps = Database.prepare(c,
                        "INSERT OR IGNORE INTO friends (a, b, since) VALUES (?, ?, ?)", pair[0].toString(), pair[1].toString(), now)) {
                    ps.executeUpdate();
                }
            }
            return null;
        });
        Set<UUID> sa = friends.get(a);
        if (sa != null) sa.add(b);
        Set<UUID> sb = friends.get(b);
        if (sb != null) sb.add(a);
    }

    public void remove(UUID a, UUID b) {
        db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "DELETE FROM friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)", a.toString(), b.toString(), b.toString(), a.toString())) {
                ps.executeUpdate();
            }
            return null;
        });
        Set<UUID> sa = friends.get(a);
        if (sa != null) sa.remove(b);
        Set<UUID> sb = friends.get(b);
        if (sb != null) sb.remove(a);
    }

    // --- пары ---

    public UUID partner(UUID uuid) {
        return partners.get(uuid);
    }

    public Map<UUID, UUID> couples() {
        return partners;
    }

    public void marry(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        db.tx(c -> {
            for (UUID[] pair : new UUID[][]{{a, b}, {b, a}}) {
                try (PreparedStatement ps = Database.prepare(c,
                        "INSERT OR REPLACE INTO couples (a, b, since) VALUES (?, ?, ?)", pair[0].toString(), pair[1].toString(), now)) {
                    ps.executeUpdate();
                }
            }
            return null;
        });
        partners.put(a, b);
        partners.put(b, a);
    }

    public void split(UUID a) {
        UUID b = partners.get(a);
        db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c, "DELETE FROM couples WHERE a = ? OR b = ?", a.toString(), a.toString())) {
                ps.executeUpdate();
            }
            if (b != null) {
                try (PreparedStatement ps = Database.prepare(c, "DELETE FROM couple_homes WHERE pair = ?", pair(a, b))) {
                    ps.executeUpdate();
                }
            }
            return null;
        });
        partners.remove(a);
        if (b != null) partners.remove(b);
    }

    private static String pair(UUID a, UUID b) {
        String x = a.toString(), y = b.toString();
        return x.compareTo(y) < 0 ? x + ":" + y : y + ":" + x;
    }

    public void setHome(UUID a, UUID b, Spot s) {
        db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT OR REPLACE INTO couple_homes (pair, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    pair(a, b), s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch())) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Spot home(UUID a, UUID b) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT world, x, y, z, yaw, pitch FROM couple_homes WHERE pair = ?", pair(a, b));
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Spot(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getFloat(5), rs.getFloat(6)) : null;
            }
        });
    }
}
