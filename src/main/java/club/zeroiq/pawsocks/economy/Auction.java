package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.data.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Данные аукциона: лоты и «возврат» (вещи, которые не продались или сняты с продажи).
 * Каждое действие — транзакция с проверкой статуса, так что один лот нельзя купить дважды
 * или одновременно купить и снять.
 */
public final class Auction {

    public record Listing(long id, UUID seller, String sellerName, ItemStack item, long price, long created, long expires) {
    }

    public record Mail(long id, ItemStack item, String reason) {
    }

    public record SoldSummary(int count, long payout) {
    }

    public enum BuyResult { OK, GONE, OWN, NO_MONEY }

    /** Покупка не удалась внутри транзакции — откатываем всё. */
    private static final class Abort extends RuntimeException {
        final BuyResult result;

        Abort(BuyResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    private final Database db;

    public Auction(Database db) {
        this.db = db;
    }

    private static Listing listing(ResultSet rs) throws SQLException {
        return new Listing(rs.getLong("id"), UUID.fromString(rs.getString("seller")), rs.getString("seller_name"),
                ItemStack.deserializeBytes(rs.getBytes("item")), rs.getLong("price"),
                rs.getLong("created"), rs.getLong("expires"));
    }

    public List<Listing> active(int offset, int limit) {
        long now = System.currentTimeMillis();
        return db.read(c -> {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM listings WHERE status = 'active' AND expires > ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    now, limit, offset);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(listing(rs));
            }
            return out;
        });
    }

    public int countActive() {
        long now = System.currentTimeMillis();
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM listings WHERE status = 'active' AND expires > ?", now);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public List<Listing> bySeller(UUID seller) {
        return db.read(c -> {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM listings WHERE status = 'active' AND seller = ? ORDER BY id DESC", seller.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(listing(rs));
            }
            return out;
        });
    }

    public Listing get(long id) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c, "SELECT * FROM listings WHERE id = ? AND status = 'active'", id);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? listing(rs) : null;
            }
        });
    }

    public long create(UUID seller, String sellerName, ItemStack item, long price, long durationMillis) {
        long now = System.currentTimeMillis();
        return db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO listings (seller, seller_name, item, price, created, expires, status) VALUES (?, ?, ?, ?, ?, ?, 'active')",
                    seller.toString(), sellerName, item.serializeAsBytes(), price, now, now + durationMillis)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT last_insert_rowid()");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
    }

    /**
     * Покупка: списать с покупателя, пометить лот проданным, заплатить продавцу (за вычетом налога).
     * sellerOnline — продавец в сети и увидит сообщение сразу, иначе уведомим при входе.
     */
    public BuyResult buy(UUID buyer, String buyerName, long id, double taxPercent, boolean sellerOnline) {
        try {
            return db.tx(c -> {
                Listing l;
                try (PreparedStatement ps = Database.prepare(c,
                        "SELECT * FROM listings WHERE id = ? AND status = 'active' AND expires > ?", id, System.currentTimeMillis());
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return BuyResult.GONE;
                    l = listing(rs);
                }
                if (l.seller().equals(buyer)) return BuyResult.OWN;
                if (!Economy.withdraw(c, buyer, l.price(), "ah_buy", "лот #" + id)) return BuyResult.NO_MONEY;
                long payout = l.price() - Money.percent(l.price(), taxPercent);
                try (PreparedStatement ps = Database.prepare(c,
                        "UPDATE listings SET status = 'sold', buyer = ?, buyer_name = ?, sold_at = ?, payout = ?, notified = ? " +
                                "WHERE id = ? AND status = 'active'",
                        buyer.toString(), buyerName, System.currentTimeMillis(), payout, sellerOnline ? 1 : 0, id)) {
                    if (ps.executeUpdate() != 1) throw new Abort(BuyResult.GONE);
                }
                if (payout > 0) Economy.deposit(c, l.seller(), payout, "ah_sale", "лот #" + id);
                return BuyResult.OK;
            });
        } catch (Abort a) {
            return a.result;
        }
    }

    /** Снять свой лот: вещь уходит в возврат. */
    public boolean cancel(UUID seller, long id) {
        return db.tx(c -> {
            ItemStack item;
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT item FROM listings WHERE id = ? AND seller = ? AND status = 'active'", id, seller.toString());
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                item = ItemStack.deserializeBytes(rs.getBytes(1));
            }
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE listings SET status = 'cancelled' WHERE id = ? AND status = 'active'", id)) {
                if (ps.executeUpdate() != 1) return false;
            }
            mail(c, seller, item, "cancelled");
            return true;
        });
    }

    /** Просроченные лоты — в возврат продавцам. Возвращает, сколько лотов перенесено. */
    public int expireDue() {
        long now = System.currentTimeMillis();
        return db.tx(c -> {
            List<long[]> due = new ArrayList<>();
            List<String> sellers = new ArrayList<>();
            List<byte[]> items = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT id, seller, item FROM listings WHERE status = 'active' AND expires <= ?", now);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new long[]{rs.getLong(1)});
                    sellers.add(rs.getString(2));
                    items.add(rs.getBytes(3));
                }
            }
            for (int i = 0; i < due.size(); i++) {
                try (PreparedStatement ps = Database.prepare(c,
                        "UPDATE listings SET status = 'expired' WHERE id = ? AND status = 'active'", due.get(i)[0])) {
                    if (ps.executeUpdate() != 1) continue;
                }
                try (PreparedStatement ps = Database.prepare(c,
                        "INSERT INTO mailbox (owner, item, reason, created) VALUES (?, ?, 'expired', ?)",
                        sellers.get(i), items.get(i), now)) {
                    ps.executeUpdate();
                }
            }
            return due.size();
        });
    }

    private static void mail(Connection c, UUID owner, ItemStack item, String reason) throws SQLException {
        try (PreparedStatement ps = Database.prepare(c,
                "INSERT INTO mailbox (owner, item, reason, created) VALUES (?, ?, ?, ?)",
                owner.toString(), item.serializeAsBytes(), reason, System.currentTimeMillis())) {
            ps.executeUpdate();
        }
    }

    public void mail(UUID owner, ItemStack item, String reason) {
        db.tx(c -> {
            mail(c, owner, item, reason);
            return null;
        });
    }

    public List<Mail> mailbox(UUID owner) {
        return db.read(c -> {
            List<Mail> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT id, item, reason FROM mailbox WHERE owner = ? ORDER BY id", owner.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Mail(rs.getLong(1), ItemStack.deserializeBytes(rs.getBytes(2)), rs.getString(3)));
                }
            }
            return out;
        });
    }

    public int mailboxCount(UUID owner) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c, "SELECT COUNT(*) FROM mailbox WHERE owner = ?", owner.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Забрать вещь из возврата. Возвращает вещь, если она там была. */
    public ItemStack takeMail(UUID owner, long id) {
        return db.tx(c -> {
            ItemStack item;
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT item FROM mailbox WHERE id = ? AND owner = ?", id, owner.toString());
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                item = ItemStack.deserializeBytes(rs.getBytes(1));
            }
            try (PreparedStatement ps = Database.prepare(c, "DELETE FROM mailbox WHERE id = ? AND owner = ?", id, owner.toString())) {
                if (ps.executeUpdate() != 1) return null;
            }
            return item;
        });
    }

    /** Что продалось, пока продавца не было в сети. Помечает как показанное. */
    public SoldSummary unseenSales(UUID seller) {
        return db.tx(c -> {
            int count = 0;
            long sum = 0;
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*), COALESCE(SUM(payout), 0) FROM listings WHERE seller = ? AND status = 'sold' AND notified = 0",
                    seller.toString());
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                    sum = rs.getLong(2);
                }
            }
            if (count > 0) {
                try (PreparedStatement ps = Database.prepare(c,
                        "UPDATE listings SET notified = 1 WHERE seller = ? AND status = 'sold' AND notified = 0", seller.toString())) {
                    ps.executeUpdate();
                }
            }
            return new SoldSummary(count, sum);
        });
    }
}
