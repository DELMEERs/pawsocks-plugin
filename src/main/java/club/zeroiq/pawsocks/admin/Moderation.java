package club.zeroiq.pawsocks.admin;

import club.zeroiq.pawsocks.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Хранилище санкций и ступеней наказаний в SQLite:
 * 1 ступень — предупреждение (WARN),
 * 2 ступень — мут или временный бан (MUTE / TEMPBAN),
 * 3 ступень — перманентный бан (BAN),
 * а также кик (KICK) и внеочередной бан за серьёзные нарушения (читы, гриферство, травля).
 */
public final class Moderation {

    public enum Type {
        WARN,
        MUTE,
        TEMPBAN,
        BAN,
        KICK;

        public static Type parse(String s) {
            try {
                return valueOf(s);
            } catch (Exception e) {
                return WARN;
            }
        }
    }

    public record Punishment(
            long id,
            UUID target,
            String targetName,
            UUID staff,
            String staffName,
            Type type,
            int stage,
            String reason,
            long created,
            long expires,
            boolean active,
            String revokedBy
    ) {
        public boolean isEffective(long now) {
            return active && (expires <= 0 || expires > now);
        }

        public long remainingMs(long now) {
            return expires <= 0 ? -1 : Math.max(0, expires - now);
        }
    }

    private final Database db;

    public Moderation(Database db) {
        this.db = db;
    }

    private static Punishment row(ResultSet rs) throws SQLException {
        return new Punishment(
                rs.getLong("id"),
                UUID.fromString(rs.getString("target")),
                rs.getString("target_name"),
                UUID.fromString(rs.getString("staff")),
                rs.getString("staff_name"),
                Type.parse(rs.getString("type")),
                rs.getInt("stage"),
                rs.getString("reason"),
                rs.getLong("created"),
                rs.getLong("expires"),
                rs.getInt("active") == 1,
                rs.getString("revoked_by")
        );
    }

    /** Записать новое наказание. Предыдущие активные баны/муты того же типа закрываются. */
    public Punishment issue(UUID target, String targetName, UUID staff, String staffName,
                            Type type, int stage, String reason, long durationMs) {
        long now = System.currentTimeMillis();
        long expires = durationMs > 0 ? now + durationMs : 0L;
        int active = type == Type.KICK ? 0 : 1;
        return db.tx(c -> {
            if (type == Type.MUTE) {
                try (PreparedStatement ps = Database.prepare(c,
                        "UPDATE punishments SET active = 0 WHERE target = ? AND type = 'MUTE' AND active = 1",
                        target.toString())) {
                    ps.executeUpdate();
                }
            } else if (type == Type.BAN || type == Type.TEMPBAN) {
                try (PreparedStatement ps = Database.prepare(c,
                        "UPDATE punishments SET active = 0 WHERE target = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1",
                        target.toString())) {
                    ps.executeUpdate();
                }
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO punishments (target, target_name, staff, staff_name, type, stage, reason, created, expires, active) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, target.toString());
                ps.setString(2, targetName);
                ps.setString(3, staff.toString());
                ps.setString(4, staffName);
                ps.setString(5, type.name());
                ps.setInt(6, stage);
                ps.setString(7, reason);
                ps.setLong(8, now);
                ps.setLong(9, expires);
                ps.setInt(10, active);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0L;
                }
            }
            return new Punishment(id, target, targetName, staff, staffName, type, stage, reason, now, expires, active == 1, null);
        });
    }

    /** Действующий бан (перманентный или временный). Истёкшие автоматически помечаются неактивными. */
    public Punishment activeBan(UUID target) {
        long now = System.currentTimeMillis();
        return db.tx(c -> {
            try (PreparedStatement exp = Database.prepare(c,
                    "UPDATE punishments SET active = 0 WHERE target = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1 AND expires > 0 AND expires <= ?",
                    target.toString(), now)) {
                exp.executeUpdate();
            }
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM punishments WHERE target = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1 ORDER BY id DESC LIMIT 1",
                    target.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        });
    }

    /** Действующий мут. Истёкшие автоматически помечаются неактивными. */
    public Punishment activeMute(UUID target) {
        long now = System.currentTimeMillis();
        return db.tx(c -> {
            try (PreparedStatement exp = Database.prepare(c,
                    "UPDATE punishments SET active = 0 WHERE target = ? AND type = 'MUTE' AND active = 1 AND expires > 0 AND expires <= ?",
                    target.toString(), now)) {
                exp.executeUpdate();
            }
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM punishments WHERE target = ? AND type = 'MUTE' AND active = 1 ORDER BY id DESC LIMIT 1",
                    target.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        });
    }

    /**
     * Текущая ступень нарушений игрока (0 — чист, 1 — было предупреждение, 2 — был мут/временный бан, 3 — пермабан).
     * Учитываются все неотменённые (revoked_by IS NULL) санкции.
     */
    public int currentStage(UUID target) {
        return db.read(c -> {
            int maxStage = 0;
            int warns = 0;
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT type, stage FROM punishments WHERE target = ? AND revoked_by IS NULL",
                    target.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Type t = Type.parse(rs.getString(1));
                    int st = rs.getInt(2);
                    if (t == Type.BAN) maxStage = Math.max(maxStage, 3);
                    else if (t == Type.TEMPBAN || t == Type.MUTE) maxStage = Math.max(maxStage, 2);
                    else if (t == Type.WARN) {
                        warns++;
                        maxStage = Math.max(maxStage, 1);
                    }
                    if (st > maxStage) maxStage = st;
                }
            }
            if (warns >= 2 && maxStage < 2) maxStage = 2;
            return Math.min(3, maxStage);
        });
    }

    /** Какая ступень полагается за следующее нарушение (1, 2 или 3). */
    public int nextStage(UUID target) {
        return Math.min(3, currentStage(target) + 1);
    }

    /** Количество активных предупреждений. */
    public int activeWarns(UUID target) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM punishments WHERE target = ? AND type = 'WARN' AND active = 1 AND revoked_by IS NULL",
                    target.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Всего неотменённых записей в истории игрока. */
    public int totalViolations(UUID target) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM punishments WHERE target = ? AND revoked_by IS NULL",
                    target.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Снять активный бан. */
    public boolean unban(UUID target, String staffName) {
        return db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE punishments SET active = 0, revoked_by = ? WHERE target = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1",
                    staffName, target.toString())) {
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Снять активный мут. */
    public boolean unmute(UUID target, String staffName) {
        return db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE punishments SET active = 0, revoked_by = ? WHERE target = ? AND type = 'MUTE' AND active = 1",
                    staffName, target.toString())) {
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Снять последнее активное предупреждение. */
    public boolean unwarn(UUID target, String staffName) {
        return db.tx(c -> {
            long id = -1;
            try (PreparedStatement find = Database.prepare(c,
                    "SELECT id FROM punishments WHERE target = ? AND type = 'WARN' AND active = 1 AND revoked_by IS NULL ORDER BY id DESC LIMIT 1",
                    target.toString());
                 ResultSet rs = find.executeQuery()) {
                if (rs.next()) id = rs.getLong(1);
            }
            if (id < 0) return false;
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE punishments SET active = 0, revoked_by = ? WHERE id = ?",
                    staffName, id)) {
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Полная амнистия (сбросить все ступени и наказания игрока — только для Создателя). */
    public int pardonAll(UUID target, String staffName) {
        return db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE punishments SET active = 0, revoked_by = ? WHERE target = ? AND revoked_by IS NULL",
                    staffName, target.toString())) {
                return ps.executeUpdate();
            }
        });
    }

    /** Отменить конкретную запись по ID. */
    public boolean revokeById(long id, String staffName) {
        return db.tx(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "UPDATE punishments SET active = 0, revoked_by = ? WHERE id = ? AND revoked_by IS NULL",
                    staffName, id)) {
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** История наказаний конкретного игрока. */
    public List<Punishment> history(UUID target, int limit) {
        return db.read(c -> {
            List<Punishment> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM punishments WHERE target = ? ORDER BY id DESC LIMIT ?",
                    target.toString(), limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        });
    }

    /** Последние наказания по всему серверу (журнал модерации). */
    public List<Punishment> recent(int limit) {
        return db.read(c -> {
            List<Punishment> out = new ArrayList<>();
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT * FROM punishments ORDER BY id DESC LIMIT ?", limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        });
    }

    // --- анти-абуз и лимиты для модераторов ---

    /** Когда модератор в последний раз выдавал наказание данного типа. */
    public long lastStaffAction(UUID staff, Type type) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COALESCE(MAX(created), 0) FROM punishments WHERE staff = ? AND type = ?",
                    staff.toString(), type.name());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    /** Сколько раз модератор применял этот тип к конкретному игроку с момента sinceMs. */
    public int countStaffTargetActions(UUID staff, UUID target, Type type, long sinceMs) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM punishments WHERE staff = ? AND target = ? AND type = ? AND created >= ?",
                    staff.toString(), target.toString(), type.name(), sinceMs);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Сколько всего наказаний выдал модератор с момента sinceMs. */
    public int countStaffActionsSince(UUID staff, long sinceMs) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "SELECT COUNT(*) FROM punishments WHERE staff = ? AND created >= ?",
                    staff.toString(), sinceMs);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}

