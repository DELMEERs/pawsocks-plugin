package club.zeroiq.pawsocks.social;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.astra.AstraFeature;
import club.zeroiq.pawsocks.data.Database;
import club.zeroiq.pawsocks.economy.Economy;
import club.zeroiq.pawsocks.util.Dialogs;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * Наставники: новичок указывает, кто позвал его на сервер (в приветственном окне или /mentor ник).
 * Когда новичок наиграет несколько часов, оба получают астры.
 */
public final class MentorFeature implements CommandExecutor, Listener {

    private final PawsocksPlugin plugin;
    private final Database db;
    private BukkitTask task;

    public MentorFeature(PawsocksPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    private Messages m() {
        return plugin.messages();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("mentors.enabled", true);
    }

    private static long hours(Player p) {
        return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 3600L;
    }

    // --- база ---

    public UUID mentorOf(UUID mentee) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c, "SELECT mentor FROM mentors WHERE mentee = ?", mentee.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        });
    }

    public int menteeCount(UUID mentor) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c, "SELECT COUNT(*) FROM mentors WHERE mentor = ?", mentor.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public int rewardedCount(UUID mentor) {
        return db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c, "SELECT COUNT(*) FROM mentors WHERE mentor = ? AND rewarded = 1", mentor.toString());
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Записать наставника. Сообщения — игроку. */
    public void choose(Player p, String name) {
        if (!enabled()) return;
        if (mentorOf(p.getUniqueId()) != null) {
            m().send(p, "mentors.already");
            return;
        }
        int window = plugin.getConfig().getInt("mentors.choose-max-hours", 3);
        if (hours(p) >= window) {
            m().send(p, "mentors.too-late", Placeholder.unparsed("hours", String.valueOf(window)));
            return;
        }
        Economy.Account mentor = plugin.economy().find(name);
        if (mentor == null) {
            m().send(p, "mentors.not-found", Placeholder.unparsed("name", name));
            return;
        }
        if (mentor.uuid().equals(p.getUniqueId()) || p.getUniqueId().equals(mentorOf(mentor.uuid()))) {
            m().send(p, "mentors.self");
            return;
        }
        db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT OR IGNORE INTO mentors (mentee, mentor, at) VALUES (?, ?, ?)",
                    p.getUniqueId().toString(), mentor.uuid().toString(), System.currentTimeMillis())) {
                ps.executeUpdate();
            }
            return null;
        });
        int need = plugin.getConfig().getInt("mentors.reward-hours", 5);
        m().send(p, "mentors.chosen", Placeholder.unparsed("name", mentor.name()), Placeholder.unparsed("hours", String.valueOf(need)));
        Player online = Bukkit.getPlayer(mentor.uuid());
        if (online != null) {
            m().send(online, "mentors.chosen-mentor", Placeholder.unparsed("name", p.getName()), Placeholder.unparsed("hours", String.valueOf(need)));
            Fx.sound(online.getLocation(), "minecraft:entity.player.levelup", 0.5f, 1.5f);
        }
    }

    // --- награда ---

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L * 60, 20L * 120);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void check() {
        if (!enabled()) return;
        int need = plugin.getConfig().getInt("mentors.reward-hours", 5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (hours(p) < need) continue;
            UUID mentor = db.read(c -> {
                try (PreparedStatement ps = Database.prepare(c,
                        "SELECT mentor FROM mentors WHERE mentee = ? AND rewarded = 0", p.getUniqueId().toString());
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? UUID.fromString(rs.getString(1)) : null;
                }
            });
            if (mentor == null) continue;
            boolean marked = db.read(c -> {
                try (PreparedStatement ps = Database.prepare(c,
                        "UPDATE mentors SET rewarded = 1 WHERE mentee = ? AND rewarded = 0", p.getUniqueId().toString())) {
                    return ps.executeUpdate() == 1;
                }
            });
            if (!marked) continue;
            int toMentee = plugin.getConfig().getInt("mentors.reward-mentee", 3);
            int toMentor = plugin.getConfig().getInt("mentors.reward-mentor", 5);
            plugin.astras().give(p.getUniqueId(), toMentee, "astra_mentee", mentor.toString());
            plugin.astras().give(mentor, toMentor, "astra_mentor", p.getUniqueId().toString());
            String mentorName = plugin.economy().name(mentor);
            m().send(p, "mentors.reward-mentee", Placeholder.unparsed("name", mentorName), AstraFeature.tag(m(), "astra", toMentee),
                    Placeholder.unparsed("hours", String.valueOf(need)));
            Player mo = Bukkit.getPlayer(mentor);
            if (mo != null) {
                m().send(mo, "mentors.reward-mentor", Placeholder.unparsed("name", p.getName()), AstraFeature.tag(m(), "astra", toMentor),
                        Placeholder.unparsed("hours", String.valueOf(need)));
                Fx.sound(mo.getLocation(), "minecraft:ui.toast.challenge_complete", 0.6f, 1.2f);
                plugin.achievements().grant(mo, "mentor");
            }
        }
    }

    // --- приветственное окно ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (p.hasPlayedBefore() || !plugin.getConfig().getBoolean("welcome.enabled", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) welcome(p);
        }, 20L * plugin.getConfig().getInt("welcome.delay-seconds", 4));
    }

    public void welcome(Player p) {
        if (!plugin.dialogs().enabled()) {
            for (var line : m().lore("welcome.body")) p.sendMessage(line);
            return;
        }
        List<io.papermc.paper.registry.data.dialog.input.DialogInput> inputs = enabled()
                ? List.of(Dialogs.text("mentor", m().get("welcome.mentor-label"), "", 16))
                : List.of();
        plugin.dialogs().notice(p, m().get("welcome.title", Placeholder.unparsed("name", p.getName())),
                m().lore("welcome.body"), inputs, m().get("welcome.button"), (v, view) -> {
                    String nick = Dialogs.textValue(view, "mentor");
                    if (!nick.isEmpty()) choose(v, nick);
                    Fx.sound(v.getLocation(), "minecraft:entity.cat.ambient", 0.8f, 1.3f);
                });
    }

    // --- /mentor ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (args.length > 0) {
            choose(p, args[0]);
            return true;
        }
        UUID mentor = mentorOf(p.getUniqueId());
        m().send(p, "mentors.info",
                Placeholder.unparsed("mentor", mentor == null ? m().raw("mentors.nobody") : plugin.economy().name(mentor)),
                Placeholder.unparsed("count", String.valueOf(menteeCount(p.getUniqueId()))),
                Placeholder.unparsed("hours", String.valueOf(plugin.getConfig().getInt("mentors.reward-hours", 5))));
        return true;
    }
}
