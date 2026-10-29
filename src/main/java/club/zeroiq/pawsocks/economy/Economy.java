package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Счета игроков. Каждое изменение баланса — одна операция в базе с записью в журнал
 * transactions, поэтому потом всегда можно разобраться, откуда взялись лапки.
 */
public final class Economy {

    public record Account(UUID uuid, String name, long balance) {
    }

    private final Database db;
    private final long startBalance;

    public Economy(Database db, long startBalance) {
        this.db = db;
        this.startBalance = startBalance;
    }

    /** Создаёт счёт при первом входе и обновляет ник. */
    public void touch(UUID uuid, String name) {
        db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO accounts (uuid, name, balance) VALUES (?, ?, ?) " +
                            "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name",
                    uuid.toString(), name, startBalance)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    public long balance(UUID uuid) {
        return db.read(c -> balance(c, uuid));
    }

    private static long balance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c, "SELECT balance FROM accounts WHERE uuid = ?", uuid.toString());
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Имя по uuid (из счёта, даже если игрок не в сети). */
    public String name(UUID uuid) {
        return db.read(c -> {
            try (java.sql.PreparedStatement ps = Database.prepare(c, "SELECT name FROM accounts WHERE uuid = ?", uuid.toString());
                 java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "?";
            }
        });
    }

    public Account find(String name) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT uuid, name, balance FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1", name);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)) : null;
            }
        });
    }

    public List<Account> top(int limit) {
        return db.read(c -> {
            List<Account> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT uuid, name, balance FROM accounts WHERE balance > 0 ORDER BY balance DESC LIMIT ?", limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                }
            }
            return out;
        });
    }

    /** Сколько лапок всего в обороте — для /eco info. */
    public long supply() {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(balance), 0) FROM accounts");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        });
    }

    public boolean withdraw(UUID uuid, long cents, String kind, String note) {
        if (cents <= 0) return false;
        return db.tx(c -> withdraw(c, uuid, cents, kind, note));
    }

    public void deposit(UUID uuid, long cents, String kind, String note) {
        if (cents <= 0) return;
        db.tx(c -> {
            deposit(c, uuid, cents, kind, note);
            return null;
        });
    }

    /** Перевод от одного игрока другому: либо целиком, либо никак. */
    public boolean transfer(UUID from, UUID to, long cents, String kind, String note) {
        if (cents <= 0 || from.equals(to)) return false;
        return db.tx(c -> {
            if (!withdraw(c, from, cents, null, null)) return false;
            depositRaw(c, to, cents);
            log(c, kind, from, to, cents, note);
            return true;
        });
    }

    public void set(UUID uuid, long cents, String note) {
        db.tx(c -> {
            long before = balance(c, uuid);
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE accounts SET balance = ? WHERE uuid = ?", Math.max(0, Math.min(Money.MAX, cents)), uuid.toString())) {
                ps.executeUpdate();
            }
            log(c, "admin_set", null, uuid, cents - before, note);
            return null;
        });
    }

    // --- внутри уже открытой транзакции (для аукциона и продажи) ---

    /** Списать, только если хватает. Проверка и списание — одна команда, уйти в минус нельзя. */
    public static boolean withdraw(Connection c, UUID uuid, long cents, String kind, String note) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "UPDATE accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?",
                cents, uuid.toString(), cents)) {
            if (ps.executeUpdate() != 1) return false;
        }
        if (kind != null) log(c, kind, uuid, null, cents, note);
        return true;
    }

    public static void deposit(Connection c, UUID uuid, long cents, String kind, String note) throws SQLException {
        depositRaw(c, uuid, cents);
        log(c, kind, null, uuid, cents, note);
    }

    private static void depositRaw(Connection c, UUID uuid, long cents) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "UPDATE accounts SET balance = MIN(balance + ?, ?) WHERE uuid = ?",
                cents, Money.MAX, uuid.toString())) {
            if (ps.executeUpdate() != 1) {
                throw new SQLException("нет счёта " + uuid);
            }
        }
    }

    private static void log(Connection c, String kind, UUID from, UUID to, long cents, String note) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "INSERT INTO transactions (at, kind, from_uuid, to_uuid, amount, note) VALUES (?, ?, ?, ?, ?, ?)",
                System.currentTimeMillis(), kind,
                from == null ? null : from.toString(),
                to == null ? null : to.toString(),
                cents, note)) {
            ps.executeUpdate();
        }
    }
}
