package club.zeroiq.pawsocks.orders;

import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.economy.Economy;
import club.zeroiq.pawsocks.economy.Money;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Доска заказов: «куплю 64 железа за 70 лапок». Деньги заказчика сразу откладываются (escrow),
 * сдатчик получает свою долю минус комиссию, вещи уходят заказчику в «возврат» аукциона.
 */
public final class Orders {

    public record Order(long id, UUID owner, String ownerName, Material material, int amount, int filled,
                        long price, long escrow, long expires) {
        public int left() {
            return amount - filled;
        }
    }

    public record Delivery(int delivered, long paid, long commission, boolean done, UUID owner) {
    }

    private final Database db;

    public Orders(Database db) {
        this.db = db;
    }

    private static Order order(ResultSet rs) throws SQLException {
        return new Order(rs.getLong("id"), UUID.fromString(rs.getString("owner")), rs.getString("owner_name"),
                Material.matchMaterial(rs.getString("material")), rs.getInt("amount"), rs.getInt("filled"),
                rs.getLong("price"), rs.getLong("escrow"), rs.getLong("expires"));
    }

    public List<Order> active(int offset, int limit) {
        long now = System.currentTimeMillis();
        return db.read(c -> {
            List<Order> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM orders WHERE status = 'active' AND expires > ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    now, limit, offset);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Order o = order(rs);
                    if (o.material() != null) out.add(o);
                }
            }
            return out;
        });
    }

    public int countActive() {
        long now = System.currentTimeMillis();
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM orders WHERE status = 'active' AND expires > ?", now);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public List<Order> byOwner(UUID owner) {
        return db.read(c -> {
            List<Order> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM orders WHERE status = 'active' AND owner = ? ORDER BY id DESC", owner.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Order o = order(rs);
                    if (o.material() != null) out.add(o);
                }
            }
            return out;
        });
    }

    /** Создать заказ: сразу списывает и откладывает price. false — не хватило лапок. */
    public boolean create(UUID owner, String ownerName, Material material, int amount, long price, long durationMillis) {
        long now = System.currentTimeMillis();
        return db.tx(c -> {
            if (!Economy.withdraw(c, owner, price, "order_escrow", amount + " " + material.name().toLowerCase())) return false;
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO orders (owner, owner_name, material, amount, filled, price, escrow, created, expires, status) " +
                            "VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, 'active')",
                    owner.toString(), ownerName, material.name(), amount, price, price, now, now + durationMillis)) {
                ps.executeUpdate();
            }
            return true;
        });
    }

    /**
     * Сдать count штук. Платит долю цены, вещи кладёт заказчику в возврат. Всё — одна транзакция.
     * null — заказа уже нет (выполнен, отменён, истёк) или он свой.
     */
    public Delivery deliver(long id, UUID deliverer, int count, double commissionPercent) {
        return db.tx(c -> {
            Order o;
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM orders WHERE id = ? AND status = 'active' AND expires > ?", id, System.currentTimeMillis());
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                o = order(rs);
            }
            if (o.material() == null || o.owner().equals(deliverer)) return null;
            int n = Math.min(count, o.left());
            if (n <= 0) return null;
            int filledAfter = o.filled() + n;
            boolean done = filledAfter >= o.amount();
            // последняя сдача забирает остаток, чтобы копейки от округления не терялись
            long gross = done ? o.escrow()
                    : Math.floorDiv(o.price() * filledAfter, o.amount()) - Math.floorDiv(o.price() * o.filled(), o.amount());
            gross = Math.max(0, Math.min(gross, o.escrow()));
            long commission = Money.percent(gross, commissionPercent);
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE orders SET filled = ?, escrow = escrow - ?, status = ? WHERE id = ? AND status = 'active' AND filled = ?",
                    filledAfter, gross, done ? "done" : "active", id, o.filled())) {
                if (ps.executeUpdate() != 1) return null;
            }
            if (gross - commission > 0) {
                Economy.deposit(c, deliverer, gross - commission, "order_paid", "заказ #" + id);
            }
            // вещи заказчику в «возврат» аукциона, стопками
            int left = n;
            int max = o.material().getMaxStackSize();
            while (left > 0) {
                int stack = Math.min(left, max);
                try (PreparedStatement ps = Database.prepare(c,
                        "INSERT INTO mailbox (owner, item, reason, created) VALUES (?, ?, 'order', ?)",
                        o.owner().toString(), new ItemStack(o.material(), stack).serializeAsBytes(), System.currentTimeMillis())) {
                    ps.executeUpdate();
                }
                left -= stack;
            }
            return new Delivery(n, gross - commission, commission, done, o.owner());
        });
    }

    /** Снять свой заказ: остаток денег возвращается. Возвращает сумму возврата или -1. */
    public long cancel(UUID owner, long id) {
        return db.tx(c -> close(c, id, owner, "cancelled"));
    }

    private static long close(Connection c, long id, UUID owner, String status) throws SQLException {
        long escrow;
        UUID who;
        try (PreparedStatement ps = Database.prepare(c,
                "SELECT owner, escrow FROM orders WHERE id = ? AND status = 'active'", id);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return -1;
            who = UUID.fromString(rs.getString(1));
            escrow = rs.getLong(2);
        }
        if (owner != null && !owner.equals(who)) return -1;
        try (PreparedStatement ps = Database.prepare(c,
                "UPDATE orders SET status = ?, escrow = 0 WHERE id = ? AND status = 'active'", status, id)) {
            if (ps.executeUpdate() != 1) return -1;
        }
        if (escrow > 0) Economy.deposit(c, who, escrow, "order_refund", "заказ #" + id);
        return escrow;
    }

    /** Истёкшие заказы закрываются, остаток денег возвращается заказчикам. */
    public int expireDue() {
        long now = System.currentTimeMillis();
        return db.tx(c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT id FROM orders WHERE status = 'active' AND expires <= ?", now);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            int n = 0;
            for (long id : ids) {
                if (close(c, id, null, "expired") >= 0) n++;
            }
            return n;
        });
    }
}
