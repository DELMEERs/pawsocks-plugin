package club.zeroiq.pawsocks.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Одна SQLite-база plugins/pawsocks/data.db (драйвер встроен в paper).
 * Все запросы идут с основного потока сервера по одному соединению — на пару десятков
 * игроков это быстро и исключает гонки (двойную покупку лота, двойное списание).
 */
public final class Database {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final Logger log;
    private Connection connection;

    public Database(Logger log) {
        this.log = log;
    }

    public synchronized void open(File file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("в сервере нет драйвера sqlite", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");

            s.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        uuid    TEXT PRIMARY KEY,
                        name    TEXT NOT NULL,
                        balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0)
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS accounts_name ON accounts (name COLLATE NOCASE)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        at        INTEGER NOT NULL,
                        kind      TEXT NOT NULL,
                        from_uuid TEXT,
                        to_uuid   TEXT,
                        amount    INTEGER NOT NULL,
                        note      TEXT
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS market (
                        material TEXT PRIMARY KEY,
                        pressure REAL NOT NULL,
                        updated  INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS listings (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller      TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item        BLOB NOT NULL,
                        price       INTEGER NOT NULL CHECK (price > 0),
                        created     INTEGER NOT NULL,
                        expires     INTEGER NOT NULL,
                        status      TEXT NOT NULL,
                        buyer       TEXT,
                        buyer_name  TEXT,
                        sold_at     INTEGER,
                        payout      INTEGER,
                        notified    INTEGER NOT NULL DEFAULT 1
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS listings_status ON listings (status, expires)");
            s.execute("CREATE INDEX IF NOT EXISTS listings_seller ON listings (seller, status)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS mailbox (
                        id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner   TEXT NOT NULL,
                        item    BLOB NOT NULL,
                        reason  TEXT NOT NULL,
                        created INTEGER NOT NULL
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS mailbox_owner ON mailbox (owner)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS homes (
                        uuid    TEXT NOT NULL,
                        name    TEXT NOT NULL,
                        world   TEXT NOT NULL,
                        x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
                        yaw REAL NOT NULL, pitch REAL NOT NULL,
                        created INTEGER NOT NULL,
                        PRIMARY KEY (uuid, name)
                    )""");

            // 1.2: астры, ежедневные награды, доска заказов, покупки, счётчики
            if (!hasColumn(s, "accounts", "astra")) {
                s.execute("ALTER TABLE accounts ADD COLUMN astra INTEGER NOT NULL DEFAULT 0");
            }
            s.execute("""
                    CREATE TABLE IF NOT EXISTS daily (
                        uuid    TEXT NOT NULL,
                        day     TEXT NOT NULL,
                        played  INTEGER NOT NULL DEFAULT 0,
                        earned  INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, day)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner       TEXT NOT NULL,
                        owner_name  TEXT NOT NULL,
                        material    TEXT NOT NULL,
                        amount      INTEGER NOT NULL CHECK (amount > 0),
                        filled      INTEGER NOT NULL DEFAULT 0,
                        price       INTEGER NOT NULL CHECK (price > 0),
                        escrow      INTEGER NOT NULL CHECK (escrow >= 0),
                        created     INTEGER NOT NULL,
                        expires     INTEGER NOT NULL,
                        status      TEXT NOT NULL
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS orders_status ON orders (status, expires)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS owned (
                        uuid TEXT NOT NULL,
                        item TEXT NOT NULL,
                        at   INTEGER NOT NULL,
                        PRIMARY KEY (uuid, item)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS counters (
                        uuid  TEXT NOT NULL,
                        key   TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        PRIMARY KEY (uuid, key)
                    )""");

            // 1.3: звёздные фонари, друзья, парочки, наставники
            s.execute("""
                    CREATE TABLE IF NOT EXISTS lamps (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                        owner TEXT NOT NULL,
                        placed INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS friends (
                        a     TEXT NOT NULL,
                        b     TEXT NOT NULL,
                        since INTEGER NOT NULL,
                        PRIMARY KEY (a, b)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS couples (
                        a     TEXT PRIMARY KEY,
                        b     TEXT NOT NULL,
                        since INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS couple_homes (
                        pair  TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
                        yaw REAL NOT NULL, pitch REAL NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS mentors (
                        mentee   TEXT PRIMARY KEY,
                        mentor   TEXT NOT NULL,
                        at       INTEGER NOT NULL,
                        rewarded INTEGER NOT NULL DEFAULT 0
                    )""");
        }
    }

    private static boolean hasColumn(Statement s, String table, String column) throws SQLException {
        try (var rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    /** Запрос без транзакции. */
    public synchronized <T> T read(Work<T> work) {
        try {
            return work.run(connection);
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    /** Всё внутри — одна транзакция: либо всё, либо ничего. */
    public synchronized <T> T tx(Work<T> work) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataException(e);
        }
    }

    public static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            log.warning("не удалось закрыть базу: " + e.getMessage());
        }
        connection = null;
    }

    public static final class DataException extends RuntimeException {
        public DataException(Throwable cause) {
            super(cause);
        }
    }
}
