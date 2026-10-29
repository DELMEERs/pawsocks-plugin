package club.zeroiq.pawsocks.data;

import club.zeroiq.pawsocks.economy.Economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Покупки игроков: что куплено навсегда (косметика) и счётчики (докупленные дома).
 * Покупка — одна транзакция: списание и запись, либо ничего.
 */
public final class Perks {

    private final Database db;

    public Perks(Database db) {
        this.db = db;
    }

    public Set<String> owned(UUID uuid) {
        return db.read(c -> {
            Set<String> out = new HashSet<>();
            try (PreparedStatement ps = Database.prepare(c, "SELECT item FROM owned WHERE uuid = ?", uuid.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    public boolean owns(UUID uuid, String item) {
        return db.read(c -> owns(c, uuid, item));
    }

    private static boolean owns(Connection c, UUID uuid, String item) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c, "SELECT 1 FROM owned WHERE uuid = ? AND item = ?", uuid.toString(), item);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    public enum BuyResult { OK, ALREADY, NO_MONEY }

    /** Купить навсегда за лапки. */
    public BuyResult buy(UUID uuid, String item, long price, String note) {
        return db.tx(c -> {
            if (owns(c, uuid, item)) return BuyResult.ALREADY;
            if (price > 0 && !Economy.withdraw(c, uuid, price, "perk", note)) return BuyResult.NO_MONEY;
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO owned (uuid, item, at) VALUES (?, ?, ?)", uuid.toString(), item, System.currentTimeMillis())) {
                ps.executeUpdate();
            }
            return BuyResult.OK;
        });
    }

    public int counter(UUID uuid, String key) {
        return db.read(c -> counter(c, uuid, key));
    }

    private static int counter(Connection c, UUID uuid, String key) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c, "SELECT value FROM counters WHERE uuid = ? AND key = ?", uuid.toString(), key);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Купить +1 к счётчику за лапки. expected — каким счётчик должен быть сейчас
     * (защита от двойного клика: второй клик увидит другое значение и ничего не спишет).
     */
    public boolean increment(UUID uuid, String key, int expected, long price, String note) {
        return db.tx(c -> {
            if (counter(c, uuid, key) != expected) return false;
            if (price > 0 && !Economy.withdraw(c, uuid, price, "perk", note)) return false;
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO counters (uuid, key, value) VALUES (?, ?, 1) " +
                            "ON CONFLICT(uuid, key) DO UPDATE SET value = value + 1", uuid.toString(), key)) {
                ps.executeUpdate();
            }
            return true;
        });
    }
}
