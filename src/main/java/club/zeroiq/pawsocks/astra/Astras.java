package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.economy.Economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Астры — вторая, редкая валюта. Хранится в том же счёте, что и лапки (колонка astra).
 * Получить можно только игрой (за время) и ежедневной наградой, переводить нельзя.
 */
public final class Astras {

    public record Daily(int played, int earned, boolean claimed) {
    }

    private final Database db;

    public Astras(Database db) {
        this.db = db;
    }

    public int balance(UUID uuid) {
        return db.read(c -> balance(c, uuid));
    }

    private static int balance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c, "SELECT astra FROM accounts WHERE uuid = ?", uuid.toString());
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void add(Connection c, UUID uuid, int n, String kind, String note) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "UPDATE accounts SET astra = MAX(0, astra + ?) WHERE uuid = ?", n, uuid.toString())) {
            if (ps.executeUpdate() != 1) throw new SQLException("нет счёта " + uuid);
        }
        log(c, kind, uuid, n, note);
    }

    private static void log(Connection c, String kind, UUID uuid, int n, String note) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "INSERT INTO transactions (at, kind, from_uuid, to_uuid, amount, note) VALUES (?, ?, NULL, ?, ?, ?)",
                System.currentTimeMillis(), kind, uuid.toString(), n, note)) {
            ps.executeUpdate();
        }
    }

    /** Списать, только если хватает. Внутри уже открытой транзакции. */
    public static boolean spend(Connection c, UUID uuid, int n, String note) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "UPDATE accounts SET astra = astra - ? WHERE uuid = ? AND astra >= ?", n, uuid.toString(), n)) {
            if (ps.executeUpdate() != 1) return false;
        }
        log(c, "astra_spend", uuid, -n, note);
        return true;
    }

    public boolean spend(UUID uuid, int n, String note) {
        if (n <= 0) return false;
        return db.tx(c -> spend(c, uuid, n, note));
    }

    public void give(UUID uuid, int n, String kind, String note) {
        if (n == 0) return;
        db.tx(c -> {
            add(c, uuid, n, kind, note);
            return null;
        });
    }

    public void set(UUID uuid, int n, String note) {
        db.tx(c -> {
            int before = balance(c, uuid);
            add(c, uuid, Math.max(0, n) - before, "astra_admin_set", note);
            return null;
        });
    }

    public Daily daily(UUID uuid, String day) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT played, earned, claimed FROM daily WHERE uuid = ? AND day = ?", uuid.toString(), day);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Daily(rs.getInt(1), rs.getInt(2), rs.getInt(3) == 1) : new Daily(0, 0, false);
            }
        });
    }

    private static void ensureDay(Connection c, UUID uuid, String day) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "INSERT OR IGNORE INTO daily (uuid, day) VALUES (?, ?)", uuid.toString(), day)) {
            ps.executeUpdate();
        }
    }

    /**
     * Прибавить время игры. Возвращает, сколько астр выдано за это (0 или больше):
     * по одной за каждые period секунд, но не больше maxPerDay в день.
     */
    public int addPlay(UUID uuid, String day, int seconds, int period, int maxPerDay) {
        return db.tx(c -> {
            ensureDay(c, uuid, day);
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE daily SET played = played + ? WHERE uuid = ? AND day = ?", seconds, uuid.toString(), day)) {
                ps.executeUpdate();
            }
            int played, earned;
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT played, earned FROM daily WHERE uuid = ? AND day = ?", uuid.toString(), day);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                played = rs.getInt(1);
                earned = rs.getInt(2);
            }
            int due = Math.min(maxPerDay, period > 0 ? played / period : 0);
            if (due <= earned) return 0;
            int award = due - earned;
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE daily SET earned = ? WHERE uuid = ? AND day = ?", due, uuid.toString(), day)) {
                ps.executeUpdate();
            }
            add(c, uuid, award, "astra_play", day);
            return award;
        });
    }

    /** Ежедневная награда: один раз в день. false — уже получена. */
    public boolean claimDaily(UUID uuid, String day, int astra, long lapki) {
        return db.tx(c -> {
            ensureDay(c, uuid, day);
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE daily SET claimed = 1 WHERE uuid = ? AND day = ? AND claimed = 0", uuid.toString(), day)) {
                if (ps.executeUpdate() != 1) return false;
            }
            if (astra > 0) add(c, uuid, astra, "astra_daily", day);
            if (lapki > 0) Economy.deposit(c, uuid, lapki, "daily", day);
            return true;
        });
    }

    /** Сколько раз забрана ежедневная награда (для достижения). */
    public int claimedDays(UUID uuid) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM daily WHERE uuid = ? AND claimed = 1", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
