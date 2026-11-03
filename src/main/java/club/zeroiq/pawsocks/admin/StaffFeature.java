package club.zeroiq.pawsocks.admin;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.admin.Moderation.Punishment;
import club.zeroiq.pawsocks.admin.Moderation.Type;
import club.zeroiq.pawsocks.astra.AstraFeature;
import club.zeroiq.pawsocks.astra.StarFeature;
import club.zeroiq.pawsocks.cosmetics.CosmeticsFeature;
import club.zeroiq.pawsocks.economy.Economy;
import club.zeroiq.pawsocks.economy.Money;
import club.zeroiq.pawsocks.feature.Trail;
import club.zeroiq.pawsocks.gui.Menu;
import club.zeroiq.pawsocks.homes.HomesFeature;
import club.zeroiq.pawsocks.util.Dialogs;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import club.zeroiq.pawsocks.util.PlayerData;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Админ-панель и система модерации для ролей Создатель и Модератор (/staff, /mod, /admin, /panel).
 * Включает:
 * - 3 ступени наказаний (1: предупреждение, 2: мут или временный бан, 3: перманентный бан) + бан за серьёзные нарушения;
 * - разумные ограничения для модераторов (запрет пермабана/пермамута, потолок сроков, кулдаун кика, защита от спама);
 * - инструменты слежки (невидимка Vanish, заморозка на проверку Freeze, осмотр инвентаря/эндер-сундука/домов, Command Spy);
 * - наведение порядка на сервере (медленный чат, тишина в чате, очистка чата, уборка мусора/мобов, статистика TPS/RAM);
 * - выдачу особых привилегий, ролей и валюты (только для Создателя).
 */
public final class StaffFeature implements CommandExecutor, TabCompleter, Listener {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));

    private final PawsocksPlugin plugin;
    private final Moderation moderation;

    /** Кто сейчас в невидимке (читается и из потока локатора). */
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    /** Кто заморожен на проверку. */
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    /** Кто включил слежку за командами (Command Spy). */
    private final Set<UUID> commandSpy = ConcurrentHashMap.newKeySet();
    /** Анти-спам в медленном чате: игрок -> время последнего сообщения. */
    private final Map<UUID, Long> lastChatAt = new ConcurrentHashMap<>();

    private volatile boolean chatMuted = false;
    private volatile int chatSlowSeconds = 0;
    private BukkitTask statusTask;

    public StaffFeature(PawsocksPlugin plugin, Moderation moderation) {
        this.plugin = plugin;
        this.moderation = moderation;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Economy eco() {
        return plugin.economy();
    }

    public Moderation moderation() {
        return moderation;
    }

    // --- роли и права ---

    /** Создатель — полный доступ без ограничений. */
    public boolean isOwner(CommandSender sender) {
        if (!(sender instanceof Player p)) return true;
        return p.isOp()
                || p.hasPermission("pawsocks.owner")
                || p.hasPermission("group.owner")
                || p.hasPermission("group.creator");
    }

    /** Модератор или Создатель — доступ к админ-панели. */
    public boolean isStaff(CommandSender sender) {
        if (isOwner(sender)) return true;
        if (!(sender instanceof Player p)) return false;
        return p.hasPermission("pawsocks.mod")
                || p.hasPermission("group.moderator")
                || p.hasPermission("group.mod");
    }

    public String roleBadge(Player p) {
        if (isOwner(p)) return m().raw("staff.role.owner");
        if (isStaff(p)) return m().raw("staff.role.mod");
        if (p.hasPermission("group.supporter") || p.hasPermission("pawsocks.trails.premium")) {
            return m().raw("staff.role.supporter");
        }
        if (p.hasPermission("group.active") || p.hasPermission("pawsocks.homes.2")) {
            return m().raw("staff.role.active");
        }
        return m().raw("staff.role.player");
    }

    /** Защищён ли целевой игрок от действий модератора (Создателя и других Модераторов трогать нельзя). */
    private boolean isProtectedTarget(Player actor, UUID targetUuid) {
        if (actor.getUniqueId().equals(targetUuid)) return true;
        if (isOwner(actor)) return false; // Создатель может управлять кем угодно, кроме себя
        Player onlineTarget = Bukkit.getPlayer(targetUuid);
        if (onlineTarget != null && isStaff(onlineTarget)) return true;
        return false;
    }

    // --- лимиты модератора из config.yml ---

    public int modMaxMuteMinutes() {
        return Math.max(5, plugin.getConfig().getInt("staff.mod-limits.max-mute-minutes", 720));
    }

    public int modMaxBanHours() {
        return Math.max(1, plugin.getConfig().getInt("staff.mod-limits.max-ban-hours", 72));
    }

    public int modKickCooldownSeconds() {
        return Math.max(5, plugin.getConfig().getInt("staff.mod-limits.kick-cooldown-seconds", 60));
    }

    public int modMaxKicksPerTargetHour() {
        return Math.max(1, plugin.getConfig().getInt("staff.mod-limits.max-kicks-per-target-hour", 2));
    }

    public int modMaxPunishmentsPerHour() {
        return Math.max(3, plugin.getConfig().getInt("staff.mod-limits.max-punishments-per-hour", 15));
    }

    // --- запуск и фоновая задача ---

    public void start() {
        stop();
        statusTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID u : vanished) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    m().actionBar(p, "staff.vanish.actionbar");
                }
            }
            for (UUID u : frozen) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    m().actionBar(p, "staff.freeze.target-actionbar");
                    p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1.2, 0), 6, 0.35, 0.5, 0.35, 0.01);
                }
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
        for (UUID u : new ArrayList<>(vanished)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) setVanish(p, false, false);
        }
        vanished.clear();
        frozen.clear();
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    // --- выдача санкций с проверкой ступеней и ограничений модератора ---

    public void issueWarn(Player staff, UUID target, String targetName, String reason) {
        if (!checkTargetAndRateLimit(staff, target, targetName)) return;
        String cleanReason = cleanReason(reason);
        Punishment p = moderation.issue(target, targetName, staff.getUniqueId(), staff.getName(),
                Type.WARN, 1, cleanReason, 0L);
        plugin.perks().set(target, "unread_warn", (int) p.id());
        notifyStaffAndTelegram(p);
        m().send(staff, "staff.punish.warn-done",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("reason", cleanReason));

        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            deliverWarnToPlayer(online, p);
        }
    }

    private void deliverWarnToPlayer(Player target, Punishment p) {
        plugin.perks().set(target.getUniqueId(), "unread_warn", 0);
        for (Component line : m().lore("staff.screen.warn-chat",
                Placeholder.unparsed("staff", p.staffName()),
                Placeholder.unparsed("reason", p.reason()))) {
            target.sendMessage(line);
        }
        Title title = Title.title(
                m().get("staff.screen.warn-title"),
                m().get("staff.screen.warn-subtitle", Placeholder.unparsed("reason", p.reason())),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(700))
        );
        target.showTitle(title);
        Fx.sound(target.getLocation(), "minecraft:block.note_block.BASS", 1f, 0.7f);
    }

    public void issueMute(Player staff, UUID target, String targetName, int minutes, String reason) {
        if (!checkTargetAndRateLimit(staff, target, targetName)) return;
        if (minutes <= 0) {
            m().send(staff, "staff.limits.bad-duration");
            return;
        }
        if (!isOwner(staff) && minutes > modMaxMuteMinutes()) {
            m().send(staff, "staff.limits.mod-max-mute",
                    Placeholder.unparsed("max", formatDuration(modMaxMuteMinutes() * 60_000L)));
            return;
        }
        String cleanReason = cleanReason(reason);
        long durationMs = minutes * 60_000L;
        Punishment p = moderation.issue(target, targetName, staff.getUniqueId(), staff.getName(),
                Type.MUTE, 2, cleanReason, durationMs);
        notifyStaffAndTelegram(p);
        String durText = formatDuration(durationMs);
        m().send(staff, "staff.punish.mute-done",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("duration", durText),
                Placeholder.unparsed("reason", cleanReason));

        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            for (Component line : m().lore("staff.screen.mute-chat",
                    Placeholder.unparsed("staff", staff.getName()),
                    Placeholder.unparsed("duration", durText),
                    Placeholder.unparsed("reason", cleanReason))) {
                online.sendMessage(line);
            }
            Fx.sound(online.getLocation(), "minecraft:block.anvil.land", 0.6f, 0.8f);
        }
    }

    public void issueTempBan(Player staff, UUID target, String targetName, int hours, int stage, String reason) {
        if (!checkTargetAndRateLimit(staff, target, targetName)) return;
        if (hours <= 0) {
            m().send(staff, "staff.limits.bad-duration");
            return;
        }
        if (!isOwner(staff) && hours > modMaxBanHours()) {
            m().send(staff, "staff.limits.mod-max-ban",
                    Placeholder.unparsed("max", String.valueOf(modMaxBanHours())));
            return;
        }
        String cleanReason = cleanReason(reason);
        long durationMs = hours * 3600_000L;
        Punishment p = moderation.issue(target, targetName, staff.getUniqueId(), staff.getName(),
                Type.TEMPBAN, stage, cleanReason, durationMs);
        frozen.remove(target);
        notifyStaffAndTelegram(p);
        String durText = formatDuration(durationMs);
        m().send(staff, "staff.punish.tempban-done",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("duration", durText),
                Placeholder.unparsed("reason", cleanReason));

        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            online.kick(formatBanScreen(p));
        }
    }

    public void issuePermBan(Player staff, UUID target, String targetName, int stage, String reason) {
        if (!isOwner(staff)) {
            m().send(staff, "staff.limits.owner-only-permban",
                    Placeholder.unparsed("max", String.valueOf(modMaxBanHours())));
            return;
        }
        if (!checkTargetAndRateLimit(staff, target, targetName)) return;
        String cleanReason = cleanReason(reason);
        Punishment p = moderation.issue(target, targetName, staff.getUniqueId(), staff.getName(),
                Type.BAN, stage, cleanReason, 0L);
        frozen.remove(target);
        notifyStaffAndTelegram(p);
        m().send(staff, "staff.punish.permban-done",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("reason", cleanReason));

        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            online.kick(formatBanScreen(p));
        }
    }

    public void issueKick(Player staff, UUID target, String targetName, String reason) {
        Player online = Bukkit.getPlayer(target);
        if (online == null) {
            m().send(staff, "friends.not-found");
            return;
        }
        if (!checkTargetAndRateLimit(staff, target, targetName)) return;
        long now = System.currentTimeMillis();
        if (!isOwner(staff)) {
            long lastKick = moderation.lastStaffAction(staff.getUniqueId(), Type.KICK);
            long cooldownMs = modKickCooldownSeconds() * 1000L;
            if (now - lastKick < cooldownMs) {
                long waitSec = (cooldownMs - (now - lastKick) + 999) / 1000;
                m().send(staff, "staff.limits.kick-cooldown",
                        Placeholder.unparsed("seconds", String.valueOf(waitSec)));
                return;
            }
            int kicksTargetHour = moderation.countStaffTargetActions(
                    staff.getUniqueId(), target, Type.KICK, now - 3600_000L);
            if (kicksTargetHour >= modMaxKicksPerTargetHour()) {
                m().send(staff, "staff.limits.kick-target-limit",
                        Placeholder.unparsed("target", targetName),
                        Placeholder.unparsed("max", String.valueOf(modMaxKicksPerTargetHour())));
                return;
            }
        }
        String cleanReason = cleanReason(reason);
        Punishment p = moderation.issue(target, targetName, staff.getUniqueId(), staff.getName(),
                Type.KICK, 0, cleanReason, 0L);
        frozen.remove(target);
        notifyStaffAndTelegram(p);
        m().send(staff, "staff.punish.kick-done",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("reason", cleanReason));
        online.kick(formatKickScreen(p));
    }

    /** Проверка иерархии и часового лимита модератора. */
    private boolean checkTargetAndRateLimit(Player staff, UUID target, String targetName) {
        if (staff.getUniqueId().equals(target)) {
            m().send(staff, "staff.limits.self");
            return false;
        }
        if (isProtectedTarget(staff, target)) {
            m().send(staff, "staff.limits.protected", Placeholder.unparsed("target", targetName));
            return false;
        }
        if (!isOwner(staff)) {
            long hourAgo = System.currentTimeMillis() - 3600_000L;
            int count = moderation.countStaffActionsSince(staff.getUniqueId(), hourAgo);
            if (count >= modMaxPunishmentsPerHour()) {
                m().send(staff, "staff.limits.hourly-limit",
                        Placeholder.unparsed("max", String.valueOf(modMaxPunishmentsPerHour())));
                return false;
            }
        }
        return true;
    }

    private String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return m().raw("staff.reasons.default");
        }
        String trimmed = reason.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private void notifyStaffAndTelegram(Punishment p) {
        String typeLabel = typeLabel(p.type(), p.stage());
        String duration = p.expires() > 0
                ? formatDuration(Math.max(1000L, p.expires() - p.created()))
                : (p.type() == Type.BAN ? m().raw("staff.duration.permanent") : "—");
        Component alert = m().get("staff.broadcast.staff-alert",
                Placeholder.unparsed("staff", p.staffName()),
                Placeholder.unparsed("target", p.targetName()),
                Placeholder.unparsed("type", typeLabel),
                Placeholder.unparsed("duration", duration),
                Placeholder.unparsed("reason", p.reason()));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isStaff(online)) online.sendMessage(alert);
        }
        if (plugin.getConfig().getBoolean("staff.telegram-log", true)) {
            String html = m().raw("staff.broadcast.telegram")
                    .replace("{staff}", escapeHtml(p.staffName()))
                    .replace("{target}", escapeHtml(p.targetName()))
                    .replace("{type}", escapeHtml(typeLabel))
                    .replace("{duration}", escapeHtml(duration))
                    .replace("{reason}", escapeHtml(p.reason()));
            plugin.telegram().sendHtml(html);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public String typeLabel(Type type, int stage) {
        return switch (type) {
            case WARN -> m().raw("staff.type.warn");
            case MUTE -> m().raw("staff.type.mute");
            case TEMPBAN -> stage >= 3 ? m().raw("staff.type.serious-ban") : m().raw("staff.type.tempban");
            case BAN -> stage == 3 ? m().raw("staff.type.permban") : m().raw("staff.type.serious-permban");
            case KICK -> m().raw("staff.type.kick");
        };
    }

    public String formatDuration(long ms) {
        long totalMin = Math.max(1, (ms + 59_999L) / 60_000L);
        long days = totalMin / (60 * 24);
        long hours = (totalMin % (60 * 24)) / 60;
        long mins = totalMin % 60;
        if (days > 0) {
            return m().raw("time.dh")
                    .replace("<d>", String.valueOf(days))
                    .replace("<h>", String.valueOf(hours));
        }
        if (hours > 0) {
            return m().raw("time.hm")
                    .replace("<h>", String.valueOf(hours))
                    .replace("<m>", String.valueOf(mins));
        }
        return m().raw("time.m").replace("<m>", String.valueOf(mins));
    }

    private Component formatBanScreen(Punishment p) {
        long now = System.currentTimeMillis();
        String left = p.expires() <= 0
                ? m().raw("staff.duration.permanent")
                : formatDuration(p.remainingMs(now));
        String stageText = p.stage() > 0 ? String.valueOf(p.stage()) : "!";
        List<Component> lines = m().lore("staff.screen.ban",
                Placeholder.unparsed("stage", stageText),
                Placeholder.unparsed("type", typeLabel(p.type(), p.stage())),
                Placeholder.unparsed("reason", p.reason()),
                Placeholder.unparsed("staff", p.staffName()),
                Placeholder.unparsed("left", left));
        return joinLines(lines);
    }

    private Component formatKickScreen(Punishment p) {
        List<Component> lines = m().lore("staff.screen.kick",
                Placeholder.unparsed("reason", p.reason()),
                Placeholder.unparsed("staff", p.staffName()));
        return joinLines(lines);
    }

    private static Component joinLines(List<Component> lines) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(lines.get(i));
        }
        return out;
    }

    // --- инструменты слежки: Vanish, Freeze, InvSee, EnderChest ---

    public void setVanish(Player p, boolean enable, boolean notify) {
        UUID u = p.getUniqueId();
        if (enable) {
            vanished.add(u);
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(p) && !isStaff(other)) {
                    other.hidePlayer(plugin, p);
                }
            }
            p.setAllowFlight(true);
            p.setSleepingIgnored(true);
            if (notify) {
                m().send(p, "staff.vanish.on");
                Fx.sound(p.getLocation(), "minecraft:entity.illusioner.mirror_move", 0.6f, 1.4f);
            }
        } else {
            vanished.remove(u);
            for (Player other : Bukkit.getOnlinePlayers()) {
                other.showPlayer(plugin, p);
            }
            p.setSleepingIgnored(false);
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                if (!isOwner(p)) {
                    p.setFlying(false);
                    p.setAllowFlight(false);
                }
            }
            if (notify) {
                m().send(p, "staff.vanish.off");
                Fx.sound(p.getLocation(), "minecraft:entity.illusioner.mirror_move", 0.6f, 0.9f);
            }
        }
    }

    public void toggleFreeze(Player staff, UUID targetUuid, String targetName) {
        if (isProtectedTarget(staff, targetUuid)) {
            m().send(staff, "staff.limits.protected", Placeholder.unparsed("target", targetName));
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            frozen.remove(targetUuid);
            m().send(staff, "friends.not-found");
            return;
        }
        if (frozen.remove(targetUuid)) {
            m().send(staff, "staff.freeze.unfrozen-staff", Placeholder.unparsed("target", target.getName()));
            m().send(target, "staff.freeze.unfrozen-target", Placeholder.unparsed("staff", staff.getName()));
            Fx.sound(target.getLocation(), "minecraft:block.beacon.deactivate", 0.7f, 1.4f);
        } else {
            frozen.add(targetUuid);
            target.closeInventory();
            m().send(staff, "staff.freeze.frozen-staff", Placeholder.unparsed("target", target.getName()));
            for (Component line : m().lore("staff.freeze.frozen-target", Placeholder.unparsed("staff", staff.getName()))) {
                target.sendMessage(line);
            }
            Title title = Title.title(
                    m().get("staff.freeze.title"),
                    m().get("staff.freeze.subtitle", Placeholder.unparsed("staff", staff.getName())),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            target.showTitle(title);
            Fx.sound(target.getLocation(), "minecraft:block.respawn_anchor.deplete", 0.9f, 0.7f);
        }
    }

    // --- события: вход, чат, заморозка, невидимка, слежка за командами ---

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Punishment ban = moderation.activeBan(event.getUniqueId());
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, formatBanScreen(ban));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // скрываем всех, кто в ванише, от обычных игроков
        if (!isStaff(p)) {
            for (UUID v : vanished) {
                Player vp = Bukkit.getPlayer(v);
                if (vp != null && !vp.equals(p)) {
                    p.hidePlayer(plugin, vp);
                }
            }
        } else {
            for (UUID v : vanished) {
                Player vp = Bukkit.getPlayer(v);
                if (vp != null && !vp.equals(p)) {
                    p.showPlayer(plugin, vp);
                }
            }
        }
        // если у игрока было непрочитанное предупреждение, пока его не было в сети
        int warnId = plugin.perks().counter(p.getUniqueId(), "unread_warn");
        if (warnId > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                List<Punishment> hist = moderation.history(p.getUniqueId(), 5);
                for (Punishment pun : hist) {
                    if (pun.id() == warnId && pun.type() == Type.WARN && pun.active()) {
                        deliverWarnToPlayer(p, pun);
                        break;
                    }
                }
                plugin.perks().set(p.getUniqueId(), "unread_warn", 0);
            }, 40L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID u = p.getUniqueId();
        lastChatAt.remove(u);
        if (vanished.remove(u)) {
            p.setSleepingIgnored(false);
        }
        if (frozen.remove(u)) {
            Component alert = m().get("staff.freeze.quit-alert", Placeholder.unparsed("target", p.getName()));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (isStaff(online)) online.sendMessage(alert);
            }
            if (plugin.getConfig().getBoolean("staff.telegram-log", true)) {
                plugin.telegram().sendHtml("❄️⚠️ <b>" + escapeHtml(p.getName()) + "</b> вышел(ла) с сервера во время заморозки на проверке!");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        // 1. индивидуальный мут
        Punishment mute = moderation.activeMute(p.getUniqueId());
        if (mute != null) {
            event.setCancelled(true);
            String left = formatDuration(mute.remainingMs(System.currentTimeMillis()));
            m().send(p, "staff.screen.muted-try",
                    Placeholder.unparsed("left", left),
                    Placeholder.unparsed("reason", mute.reason()));
            return;
        }
        if (isStaff(p)) return;

        // 2. глобальная тишина в чате
        if (chatMuted) {
            event.setCancelled(true);
            m().send(p, "staff.order.chat-is-muted");
            return;
        }
        // 3. медленный режим чата
        int slow = chatSlowSeconds;
        if (slow > 0) {
            long now = System.currentTimeMillis();
            Long prev = lastChatAt.get(p.getUniqueId());
            long cooldownMs = slow * 1000L;
            if (prev != null && now - prev < cooldownMs) {
                event.setCancelled(true);
                long wait = (cooldownMs - (now - prev) + 999) / 1000;
                m().send(p, "staff.order.chat-slow-wait", Placeholder.unparsed("seconds", String.valueOf(wait)));
                return;
            }
            lastChatAt.put(p.getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        String msg = event.getMessage().trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        // если игрок заморожен — нельзя телепортироваться и убегать командами
        if (frozen.contains(p.getUniqueId()) && !isStaff(p)) {
            if (!lower.startsWith("/msg ") && !lower.startsWith("/tell ") && !lower.startsWith("/w ") && !lower.startsWith("/r ")) {
                event.setCancelled(true);
                m().send(p, "staff.freeze.blocked-action");
                return;
            }
        }

        // если игрок в муте — блокируем обход мута через эмоции и личные сообщения
        if (lower.startsWith("/emote") || lower.startsWith("/meow") || lower.startsWith("/purr")
                || lower.startsWith("/boop") || lower.startsWith("/wave")
                || lower.startsWith("/me ") || lower.startsWith("/say ")) {
            Punishment mute = moderation.activeMute(p.getUniqueId());
            if (mute != null) {
                event.setCancelled(true);
                String left = formatDuration(mute.remainingMs(System.currentTimeMillis()));
                m().send(p, "staff.screen.muted-try",
                        Placeholder.unparsed("left", left),
                        Placeholder.unparsed("reason", mute.reason()));
                return;
            }
        }

        // Command Spy для администрации
        if (!commandSpy.isEmpty() && !lower.startsWith("/login") && !lower.startsWith("/register") && !lower.startsWith("/l ")) {
            Component spyLine = m().get("staff.spy.line",
                    Placeholder.unparsed("player", p.getName()),
                    Placeholder.unparsed("command", msg));
            for (UUID u : commandSpy) {
                if (u.equals(p.getUniqueId())) continue;
                Player watcher = Bukkit.getPlayer(u);
                if (watcher != null && isStaff(watcher)) {
                    watcher.sendMessage(spyLine);
                }
            }
        }
    }

    // --- защита при заморозке и удобства в невидимке ---

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId())) return;
        if (event.hasChangedPosition()) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                event.setTo(back);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenBreak(BlockBreakEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            m().send(event.getPlayer(), "staff.freeze.blocked-action");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenPlace(BlockPlaceEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            m().send(event.getPlayer(), "staff.freeze.blocked-action");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrozenDrop(PlayerDropItemEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            m().send(event.getPlayer(), "staff.freeze.blocked-action");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            if (frozen.contains(p.getUniqueId()) || vanished.contains(p.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player attacker) {
            if (frozen.contains(attacker.getUniqueId())) {
                event.setCancelled(true);
                m().send(attacker, "staff.freeze.blocked-action");
            } else if (vanished.contains(attacker.getUniqueId()) && !isOwner(attacker)) {
                // модератор в невидимке не может бить игроков и мобов
                event.setCancelled(true);
                m().actionBar(attacker, "staff.vanish.no-hit");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p) {
            if (vanished.contains(p.getUniqueId()) || frozen.contains(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player p && (vanished.contains(p.getUniqueId()) || frozen.contains(p.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    /** Бесшумный осмотр сундуков/бочек/шалкеров в невидимке без анимации крышки. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSilentContainer(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (frozen.contains(p.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!vanished.contains(p.getUniqueId()) || event.getAction() != Action.RIGHT_CLICK_BLOCK || p.isSneaking()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Container container)) return;
        event.setCancelled(true);
        Inventory src = container.getInventory();
        new ReadOnlyContainerMenu(src).open(p);
        m().actionBar(p, "staff.vanish.silent-chest");
    }

    // --- команды /staff (/mod, /admin, /panel) ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!isStaff(p)) {
            m().send(p, "no-permission");
            return true;
        }
        if (args.length == 0) {
            openMain(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "vanish", "v" -> setVanish(p, !vanished.contains(p.getUniqueId()), true);
            case "spy" -> toggleSpy(p);
            case "nv", "nightvision" -> toggleNightVision(p);
            case "log", "history" -> {
                if (args.length >= 2) {
                    Economy.Account acc = resolveAccount(args[1]);
                    if (acc == null) m().send(p, "eco.no-account");
                    else new HistoryMenu(acc.uuid(), acc.name(), null).open(p);
                } else {
                    new AuditLogMenu(0).open(p);
                }
            }
            case "order", "server" -> new ServerOrderMenu().open(p);
            case "player", "p", "check" -> {
                if (args.length < 2) {
                    searchPlayerDialog(p);
                    return true;
                }
                openPlayerByName(p, args[1]);
            }
            case "freeze", "ss" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else toggleFreeze(p, acc.uuid(), acc.name());
            }
            case "invsee", "inv" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) m().send(p, "friends.not-found");
                else openInvSee(p, target, null);
            }
            case "ec", "enderchest" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) m().send(p, "friends.not-found");
                else openEnderChest(p, target, null);
            }
            case "warn" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else issueWarn(p, acc.uuid(), acc.name(), joinArgs(args, 2));
            }
            case "mute" -> {
                if (args.length < 3) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                int min = parseInt(args[2], -1);
                if (acc == null) m().send(p, "eco.no-account");
                else issueMute(p, acc.uuid(), acc.name(), min, joinArgs(args, 3));
            }
            case "tempban", "tban" -> {
                if (args.length < 3) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                int hours = parseInt(args[2], -1);
                if (acc == null) m().send(p, "eco.no-account");
                else issueTempBan(p, acc.uuid(), acc.name(), hours, 2, joinArgs(args, 3));
            }
            case "ban", "permban" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else issuePermBan(p, acc.uuid(), acc.name(), 3, joinArgs(args, 2));
            }
            case "kick" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else issueKick(p, acc.uuid(), acc.name(), joinArgs(args, 2));
            }
            case "unmute" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else if (moderation.unmute(acc.uuid(), p.getName())) {
                    m().send(p, "staff.punish.unmuted", Placeholder.unparsed("target", acc.name()));
                } else {
                    m().send(p, "staff.punish.nothing-to-revoke");
                }
            }
            case "unban", "pardon" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else if (moderation.unban(acc.uuid(), p.getName())) {
                    m().send(p, "staff.punish.unbanned", Placeholder.unparsed("target", acc.name()));
                } else {
                    m().send(p, "staff.punish.nothing-to-revoke");
                }
            }
            case "unwarn" -> {
                if (args.length < 2) {
                    m().send(p, "staff.usage");
                    return true;
                }
                Economy.Account acc = resolveAccount(args[1]);
                if (acc == null) m().send(p, "eco.no-account");
                else if (moderation.unwarn(acc.uuid(), p.getName())) {
                    m().send(p, "staff.punish.unwarned", Placeholder.unparsed("target", acc.name()));
                } else {
                    m().send(p, "staff.punish.nothing-to-revoke");
                }
            }
            default -> openPlayerByName(p, args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!isStaff(sender)) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pref = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("vanish", "spy", "nv", "player", "warn", "mute", "tempban", "ban",
                    "kick", "unmute", "unban", "unwarn", "freeze", "invsee", "ec", "log", "order")) {
                if (s.startsWith(pref)) out.add(s);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(pref)) out.add(p.getName());
            }
        } else if (args.length == 2) {
            String pref = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(pref)) out.add(p.getName());
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("mute")) return List.of("15", "30", "120", "360", "720");
            if (sub.equals("tempban") || sub.equals("tban")) return List.of("6", "12", "24", "72");
        }
        return out;
    }

    private Economy.Account resolveAccount(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Economy.Account(online.getUniqueId(), online.getName(), eco().balance(online.getUniqueId()));
        }
        return eco().find(name);
    }

    private void openPlayerByName(Player staff, String name) {
        Economy.Account acc = resolveAccount(name);
        if (acc == null) {
            m().send(staff, "eco.no-account");
            return;
        }
        new PlayerManageMenu(acc.uuid(), acc.name(), 0).open(staff);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String joinArgs(String[] args, int from) {
        if (args.length <= from) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // --- быстрые действия сотрудника ---

    private void toggleSpy(Player p) {
        UUID u = p.getUniqueId();
        if (commandSpy.remove(u)) {
            m().send(p, "staff.spy.off");
        } else {
            commandSpy.add(u);
            m().send(p, "staff.spy.on");
        }
        Fx.sound(p.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
    }

    private void toggleNightVision(Player p) {
        if (p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            m().send(p, "staff.tools.nv-off");
        } else {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
            m().send(p, "staff.tools.nv-on");
        }
        Fx.sound(p.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
    }

    private void toggleSpectator(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
            if (vanished.contains(p.getUniqueId()) || isOwner(p)) {
                p.setAllowFlight(true);
            }
            m().send(p, "staff.tools.gamemode-survival");
        } else {
            if (!isOwner(p) && !vanished.contains(p.getUniqueId())) {
                setVanish(p, true, false);
            }
            p.setGameMode(GameMode.SPECTATOR);
            m().send(p, "staff.tools.gamemode-spectator");
        }
        Fx.sound(p.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
    }

    private void teleportToPlayer(Player staff, Player target) {
        if (target == null || !target.isOnline()) {
            m().send(staff, "friends.not-found");
            return;
        }
        staff.teleportAsync(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (ok) {
                Fx.sound(staff.getLocation(), "minecraft:entity.enderman.teleport", 0.5f, 1.3f);
                m().actionBar(staff, "friends.tp-arrived", Placeholder.unparsed("name", target.getName()));
            }
        });
    }

    public void openMain(Player p) {
        new StaffMainMenu(0).open(p);
    }

    private static ItemStack pane(Material mat) {
        return Items.icon(mat, Component.empty(), List.of());
    }

    private ItemStack head(UUID uuid, String name, Component displayName, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            meta.setPlayerProfile(online.getPlayerProfile());
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            meta.setOwningPlayer(op);
        }
        meta.displayName(displayName);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // =========================================================================
    // 1. ГЛАВНОЕ МЕНЮ АДМИН-ПАНЕЛИ
    // =========================================================================

    private final class StaffMainMenu extends Menu {
        private final int page;

        StaffMainMenu(int page) {
            super(6, plugin.messages().get("staff.menu.title"));
            this.page = Math.max(0, page);
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            ItemStack border = pane(Material.GRAY_STAINED_GLASS_PANE);
            for (int i = 0; i < 9; i++) set(i, border, null);
            for (int i = 9; i < 45; i++) set(i, bg, null);
            for (int i = 45; i < 54; i++) set(i, border, null);

            boolean owner = isOwner(viewer);
            // памятка о ступенях наказаний (слот 0)
            set(0, Items.icon(Material.BOOK, m().item("staff.menu.rules-name"), m().lore("staff.menu.rules-lore")), null);

            // карточка сотрудника (слот 4)
            List<Component> badgeLore = owner
                    ? m().lore("staff.menu.badge-owner-lore",
                    Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())))
                    : m().lore("staff.menu.badge-mod-lore",
                    Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())),
                    Placeholder.unparsed("max_mute", formatDuration(modMaxMuteMinutes() * 60_000L)),
                    Placeholder.unparsed("max_ban", String.valueOf(modMaxBanHours())),
                    Placeholder.unparsed("kick_cd", String.valueOf(modKickCooldownSeconds())),
                    Placeholder.unparsed("hour_limit", String.valueOf(modMaxPunishmentsPerHour())));
            set(4, head(viewer.getUniqueId(), viewer.getName(),
                    m().item("staff.menu.badge-name",
                            Placeholder.unparsed("name", viewer.getName()),
                            Placeholder.parsed("role", roleBadge(viewer))),
                    badgeLore), null);

            // поиск любого игрока (даже оффлайн) (слот 8)
            set(8, Items.icon(Material.SPYGLASS, m().item("staff.menu.search-name"), m().lore("staff.menu.search-lore")), v -> {
                v.closeInventory();
                searchPlayerDialog(v);
            });

            // список онлайн игроков (слоты 9..44 = 36 на страницу)
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
            int perPage = 36;
            int start = page * perPage;
            for (int i = 0; i < perPage && start + i < players.size(); i++) {
                Player target = players.get(start + i);
                UUID tu = target.getUniqueId();
                int stage = moderation.currentStage(tu);
                int total = moderation.totalViolations(tu);
                Punishment mute = moderation.activeMute(tu);
                List<String> flags = new ArrayList<>();
                if (vanished.contains(tu)) flags.add(m().raw("staff.flag.vanished"));
                if (frozen.contains(tu)) flags.add(m().raw("staff.flag.frozen"));
                if (mute != null) flags.add(m().raw("staff.flag.muted"));
                String statusLine = flags.isEmpty() ? m().raw("staff.flag.normal") : String.join(" ", flags);

                Location loc = target.getLocation();
                int hours = target.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600;
                List<Component> lore = m().lore("staff.menu.player-lore",
                        Placeholder.parsed("role", roleBadge(target)),
                        Placeholder.parsed("status", statusLine),
                        Placeholder.unparsed("stage", String.valueOf(stage)),
                        Placeholder.unparsed("violations", String.valueOf(total)),
                        Placeholder.unparsed("hp", String.valueOf((int) Math.ceil(target.getHealth()))),
                        Placeholder.unparsed("ping", String.valueOf(target.getPing())),
                        Placeholder.unparsed("gamemode", target.getGameMode().name().toLowerCase(Locale.ROOT)),
                        Placeholder.unparsed("world", loc.getWorld().getName()),
                        Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                        Placeholder.unparsed("y", String.valueOf(loc.getBlockY())),
                        Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())),
                        Placeholder.unparsed("hours", String.valueOf(hours)),
                        Placeholder.unparsed("cozy", String.valueOf(PlayerData.cozy(target))),
                        Money.tag(m(), "balance", eco().balance(tu)),
                        AstraFeature.tag(m(), "astra", plugin.astras().balance(tu)));

                ItemStack pHead = head(tu, target.getName(),
                        m().item("staff.menu.player-name", Placeholder.unparsed("name", target.getName())), lore);
                if (frozen.contains(tu) || mute != null) Items.glow(pHead);

                setClick(9 + i, pHead, (v, click) -> {
                    Fx.sound(v.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
                    if (click == ClickType.RIGHT) {
                        v.closeInventory();
                        teleportToPlayer(v, target);
                    } else if (click == ClickType.SHIFT_LEFT) {
                        openInvSee(v, target, this);
                    } else if (click == ClickType.SHIFT_RIGHT) {
                        toggleFreeze(v, tu, target.getName());
                        open(v);
                    } else {
                        new PlayerManageMenu(tu, target.getName(), page).open(v);
                    }
                });
            }

            // нижняя панель: пагинация, личные переключатели, порядок на сервере, журнал
            if (page > 0) {
                set(45, Items.icon(Material.SPECTRAL_ARROW, m().item("ah.prev.name"), List.of()), v -> new StaffMainMenu(page - 1).open(v));
            }

            boolean isVanish = vanished.contains(viewer.getUniqueId());
            ItemStack vanishIcon = Items.icon(isVanish ? Material.ENDER_EYE : Material.ENDER_PEARL,
                    m().item("staff.tools.vanish-name"),
                    m().lore("staff.tools.vanish-lore", Placeholder.component("state", stateComp(isVanish))));
            if (isVanish) Items.glow(vanishIcon);
            set(47, vanishIcon, v -> {
                setVanish(v, !vanished.contains(v.getUniqueId()), true);
                open(v);
            });

            boolean isSpy = commandSpy.contains(viewer.getUniqueId());
            ItemStack spyIcon = Items.icon(Material.OBSERVER,
                    m().item("staff.tools.spy-name"),
                    m().lore("staff.tools.spy-lore", Placeholder.component("state", stateComp(isSpy))));
            if (isSpy) Items.glow(spyIcon);
            set(48, spyIcon, v -> {
                toggleSpy(v);
                open(v);
            });

            boolean isNv = viewer.hasPotionEffect(PotionEffectType.NIGHT_VISION);
            ItemStack nvIcon = Items.icon(Material.GOLDEN_CARROT,
                    m().item("staff.tools.nv-name"),
                    m().lore("staff.tools.nv-lore", Placeholder.component("state", stateComp(isNv))));
            if (isNv) Items.glow(nvIcon);
            set(49, nvIcon, v -> {
                toggleNightVision(v);
                open(v);
            });

            boolean isSpec = viewer.getGameMode() == GameMode.SPECTATOR;
            ItemStack specIcon = Items.icon(Material.FEATHER,
                    m().item("staff.tools.spec-name"),
                    m().lore("staff.tools.spec-lore", Placeholder.component("state", stateComp(isSpec))));
            if (isSpec) Items.glow(specIcon);
            set(50, specIcon, v -> {
                toggleSpectator(v);
                open(v);
            });

            set(51, Items.icon(Material.BELL, m().item("staff.menu.order-name"), m().lore("staff.menu.order-lore")),
                    v -> new ServerOrderMenu().open(v));

            set(52, Items.icon(Material.WRITABLE_BOOK, m().item("staff.menu.log-name"), m().lore("staff.menu.log-lore")),
                    v -> new AuditLogMenu(0).open(v));

            if (start + perPage < players.size()) {
                set(53, Items.icon(Material.SPECTRAL_ARROW, m().item("ah.next.name"), List.of()), v -> new StaffMainMenu(page + 1).open(v));
            }
        }
    }

    private Component stateComp(boolean on) {
        return m().get(on ? "menu.on" : "menu.off");
    }

    public void searchPlayerDialog(Player staff) {
        if (!plugin.dialogs().enabled()) {
            m().send(staff, "staff.usage");
            return;
        }
        plugin.dialogs().confirm(staff,
                m().get("staff.dialog.search-title"),
                m().lore("staff.dialog.search-body"),
                List.of(Dialogs.text("nick", m().get("friends.dialog.nick"), "", 16)),
                m().get("staff.dialog.search-yes"),
                m().get("friends.dialog.no"),
                (v, view) -> {
                    String nick = Dialogs.textValue(view, "nick");
                    if (!nick.isEmpty()) openPlayerByName(v, nick);
                });
    }

    // =========================================================================
    // 2. КАРТОЧКА ИГРОКА: СТУПЕНИ НАКАЗАНИЙ, СЛЕЖКА И ОСОБЫЕ ПРИВИЛЕГИИ
    // =========================================================================

    private final class PlayerManageMenu extends Menu {
        private final UUID targetUuid;
        private final String targetName;
        private final int returnPage;

        PlayerManageMenu(UUID targetUuid, String targetName, int returnPage) {
            super(6, plugin.messages().get("staff.player.title", Placeholder.unparsed("name", targetName)));
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.returnPage = returnPage;
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 54; i++) set(i, bg, null);

            boolean owner = isOwner(viewer);
            Player online = Bukkit.getPlayer(targetUuid);
            int curStage = moderation.currentStage(targetUuid);
            int nextStage = moderation.nextStage(targetUuid);
            int warns = moderation.activeWarns(targetUuid);
            int total = moderation.totalViolations(targetUuid);
            Punishment activeBan = moderation.activeBan(targetUuid);
            Punishment activeMute = moderation.activeMute(targetUuid);

            String banState = activeBan == null
                    ? m().raw("menu.off")
                    : (activeBan.expires() <= 0
                    ? m().raw("staff.duration.permanent")
                    : formatDuration(activeBan.remainingMs(System.currentTimeMillis())));
            String muteState = activeMute == null
                    ? m().raw("menu.off")
                    : formatDuration(activeMute.remainingMs(System.currentTimeMillis()));

            // слот 0: памятка ступеней
            set(0, Items.icon(Material.BOOK, m().item("staff.menu.rules-name"), m().lore("staff.menu.rules-lore")), null);

            // слот 4: голова и досье игрока
            set(4, head(targetUuid, targetName,
                    m().item("staff.menu.player-name", Placeholder.unparsed("name", targetName)),
                    m().lore("staff.player.summary-lore",
                            Placeholder.component("online", m().get(online != null ? "friends.menu.online" : "friends.menu.offline")),
                            Placeholder.unparsed("stage", String.valueOf(curStage)),
                            Placeholder.unparsed("next_stage", String.valueOf(nextStage)),
                            Placeholder.unparsed("warns", String.valueOf(warns)),
                            Placeholder.unparsed("total", String.valueOf(total)),
                            Placeholder.unparsed("mute", muteState),
                            Placeholder.unparsed("ban", banState),
                            Money.tag(m(), "balance", eco().balance(targetUuid)),
                            AstraFeature.tag(m(), "astra", plugin.astras().balance(targetUuid)))), null);

            // слот 8: УМНАЯ СТУПЕНЬ — рекомендуемое по правилам наказание
            ItemStack smartIcon = Items.glow(Items.icon(Material.NETHER_STAR,
                    m().item("staff.player.smart-name", Placeholder.unparsed("next", String.valueOf(nextStage))),
                    m().lore("staff.player.smart-lore", Placeholder.unparsed("next", String.valueOf(nextStage)))));
            set(8, smartIcon, v -> {
                if (nextStage == 1) {
                    new ReasonMenu(targetUuid, targetName, Type.WARN, 1, 0, this).open(v);
                } else if (nextStage == 2) {
                    new ReasonMenu(targetUuid, targetName, Type.MUTE, 2, 60, this).open(v);
                } else {
                    if (isOwner(v)) {
                        new ReasonMenu(targetUuid, targetName, Type.BAN, 3, 0, this).open(v);
                    } else {
                        // Модератор не может выдать пермабан — выдаёт максимальный временный бан до решения Создателя
                        new ReasonMenu(targetUuid, targetName, Type.TEMPBAN, 3, modMaxBanHours(), this).open(v);
                    }
                }
            });

            // --- РЯД 2: ГРАДАЦИЯ САНКЦИЙ (слоты 10..16) ---

            // Ступень 1: Предупреждение
            ItemStack stage1 = Items.icon(Material.YELLOW_DYE,
                    m().item("staff.player.stage1-name"),
                    m().lore("staff.player.stage1-lore"));
            if (nextStage == 1) Items.glow(stage1);
            set(10, stage1, v -> new ReasonMenu(targetUuid, targetName, Type.WARN, 1, 0, this).open(v));

            // Ступень 2А: Мут в чате
            ItemStack stage2Mute = Items.icon(Material.ORANGE_DYE,
                    m().item("staff.player.stage2-mute-name"),
                    m().lore("staff.player.stage2-mute-lore",
                            Placeholder.unparsed("max", owner ? "∞" : formatDuration(modMaxMuteMinutes() * 60_000L))));
            if (nextStage == 2) Items.glow(stage2Mute);
            set(11, stage2Mute, v -> new ReasonMenu(targetUuid, targetName, Type.MUTE, 2, 60, this).open(v));

            // Ступень 2Б: Временный бан
            ItemStack stage2Ban = Items.icon(Material.COPPER_INGOT,
                    m().item("staff.player.stage2-ban-name"),
                    m().lore("staff.player.stage2-ban-lore",
                            Placeholder.unparsed("max", owner ? "∞" : modMaxBanHours() + " ч")));
            if (nextStage == 2) Items.glow(stage2Ban);
            set(12, stage2Ban, v -> new ReasonMenu(targetUuid, targetName, Type.TEMPBAN, 2, 24, this).open(v));

            // Ступень 3: Перманентный бан (только Создатель; модератору — макс. бан до прихода Создателя)
            ItemStack stage3 = Items.icon(Material.RED_DYE,
                    m().item("staff.player.stage3-name"),
                    m().lore(owner ? "staff.player.stage3-owner-lore" : "staff.player.stage3-mod-lore",
                            Placeholder.unparsed("max", String.valueOf(modMaxBanHours()))));
            if (nextStage == 3) Items.glow(stage3);
            set(13, stage3, v -> {
                if (isOwner(v)) {
                    new ReasonMenu(targetUuid, targetName, Type.BAN, 3, 0, this).open(v);
                } else {
                    new ReasonMenu(targetUuid, targetName, Type.TEMPBAN, 3, modMaxBanHours(), this).open(v);
                }
            });

            // Серьёзное нарушение (пропуск ступеней: читы, массовое гриферство, травля)
            set(14, Items.icon(Material.TNT,
                    m().item("staff.player.serious-name"),
                    m().lore(owner ? "staff.player.serious-owner-lore" : "staff.player.serious-mod-lore",
                            Placeholder.unparsed("max", String.valueOf(modMaxBanHours())))), v -> {
                if (isOwner(v)) {
                    new ReasonMenu(targetUuid, targetName, Type.BAN, 4, 0, this).open(v);
                } else {
                    new ReasonMenu(targetUuid, targetName, Type.TEMPBAN, 4, modMaxBanHours(), this).open(v);
                }
            });

            // Кик с сервера (с анти-спамом для модеров)
            set(16, Items.icon(Material.LEATHER_BOOTS,
                    m().item("staff.player.kick-name"),
                    m().lore("staff.player.kick-lore",
                            Placeholder.unparsed("cd", String.valueOf(modKickCooldownSeconds())),
                            Placeholder.unparsed("limit", String.valueOf(modMaxKicksPerTargetHour())))),
                    v -> new ReasonMenu(targetUuid, targetName, Type.KICK, 0, 0, this).open(v));

            // --- РЯД 3: ИСТОРИЯ И СНЯТИЕ НАКАЗАНИЙ (слоты 19..25) ---

            set(19, Items.icon(Material.KNOWLEDGE_BOOK,
                    m().item("staff.player.history-name"),
                    m().lore("staff.player.history-lore", Placeholder.unparsed("count", String.valueOf(total)))),
                    v -> new HistoryMenu(targetUuid, targetName, this).open(v));

            set(21, Items.icon(Material.MILK_BUCKET,
                    m().item("staff.player.unmute-name"),
                    m().lore("staff.player.unmute-lore", Placeholder.unparsed("state", muteState))), v -> {
                if (moderation.unmute(targetUuid, v.getName())) {
                    m().send(v, "staff.punish.unmuted", Placeholder.unparsed("target", targetName));
                    Player on = Bukkit.getPlayer(targetUuid);
                    if (on != null) m().send(on, "staff.screen.unmuted-target", Placeholder.unparsed("staff", v.getName()));
                } else {
                    m().send(v, "staff.punish.nothing-to-revoke");
                }
                open(v);
            });

            set(22, Items.icon(Material.TOTEM_OF_UNDYING,
                    m().item("staff.player.unban-name"),
                    m().lore("staff.player.unban-lore", Placeholder.unparsed("state", banState))), v -> {
                if (moderation.unban(targetUuid, v.getName())) {
                    m().send(v, "staff.punish.unbanned", Placeholder.unparsed("target", targetName));
                } else {
                    m().send(v, "staff.punish.nothing-to-revoke");
                }
                open(v);
            });

            set(23, Items.icon(Material.FEATHER,
                    m().item("staff.player.unwarn-name"),
                    m().lore("staff.player.unwarn-lore", Placeholder.unparsed("warns", String.valueOf(warns)))), v -> {
                if (moderation.unwarn(targetUuid, v.getName())) {
                    m().send(v, "staff.punish.unwarned", Placeholder.unparsed("target", targetName));
                } else {
                    m().send(v, "staff.punish.nothing-to-revoke");
                }
                open(v);
            });

            if (owner) {
                set(25, Items.icon(Material.HEART_OF_THE_SEA,
                        m().item("staff.player.pardon-all-name"),
                        m().lore("staff.player.pardon-all-lore")), v -> {
                    int n = moderation.pardonAll(targetUuid, v.getName());
                    m().send(v, "staff.punish.pardoned-all",
                            Placeholder.unparsed("target", targetName),
                            Placeholder.unparsed("count", String.valueOf(n)));
                    open(v);
                });
            }

            // --- РЯД 4: СЛЕЖКА И ПРОВЕРКА (слоты 28..34) ---

            set(28, Items.icon(Material.ENDER_PEARL,
                    m().item("staff.player.tp-to-name"),
                    m().lore("staff.player.tp-to-lore")), v -> {
                v.closeInventory();
                teleportToPlayer(v, Bukkit.getPlayer(targetUuid));
            });

            set(29, Items.icon(Material.FISHING_ROD,
                    m().item("staff.player.tp-here-name"),
                    m().lore(owner ? "staff.player.tp-here-owner-lore" : "staff.player.tp-here-mod-lore")), v -> {
                Player t = Bukkit.getPlayer(targetUuid);
                if (t == null) {
                    m().send(v, "friends.not-found");
                    return;
                }
                if (!isOwner(v) && !frozen.contains(targetUuid)) {
                    m().send(v, "staff.limits.tp-here-need-freeze");
                    return;
                }
                v.closeInventory();
                t.teleportAsync(v.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                m().send(v, "staff.tools.tp-here-done", Placeholder.unparsed("target", t.getName()));
            });

            boolean isFrz = frozen.contains(targetUuid);
            ItemStack freezeIcon = Items.icon(Material.PACKED_ICE,
                    m().item("staff.player.freeze-name"),
                    m().lore("staff.player.freeze-lore", Placeholder.component("state", stateComp(isFrz))));
            if (isFrz) Items.glow(freezeIcon);
            set(30, freezeIcon, v -> {
                toggleFreeze(v, targetUuid, targetName);
                open(v);
            });

            set(31, Items.icon(Material.CHEST,
                    m().item("staff.player.invsee-name"),
                    m().lore(owner ? "staff.player.invsee-owner-lore" : "staff.player.invsee-mod-lore")), v -> {
                Player t = Bukkit.getPlayer(targetUuid);
                if (t == null) m().send(v, "friends.not-found");
                else openInvSee(v, t, this);
            });

            set(32, Items.icon(Material.ENDER_CHEST,
                    m().item("staff.player.ec-name"),
                    m().lore(owner ? "staff.player.ec-owner-lore" : "staff.player.ec-mod-lore")), v -> {
                Player t = Bukkit.getPlayer(targetUuid);
                if (t == null) m().send(v, "friends.not-found");
                else openEnderChest(v, t, this);
            });

            List<HomesFeature.Home> homes = plugin.homes().homes(targetUuid);
            set(34, Items.icon(Material.RED_BED,
                    m().item("staff.player.homes-name"),
                    m().lore("staff.player.homes-lore", Placeholder.unparsed("count", String.valueOf(homes.size())))),
                    v -> new PlayerHomesMenu(targetUuid, targetName, this).open(v));

            // --- РЯД 6: НАЗАД И ОСОБЫЕ ПРИВИЛЕГИИ (ДЛЯ СОЗДАТЕЛЯ) ---

            set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> new StaffMainMenu(returnPage).open(v));

            ItemStack privIcon = Items.icon(Material.DRAGON_BREATH,
                    m().item("staff.player.privs-name"),
                    m().lore(owner ? "staff.player.privs-owner-lore" : "staff.player.privs-mod-lore"));
            if (owner) Items.glow(privIcon);
            set(49, privIcon, v -> {
                if (!isOwner(v)) {
                    m().send(v, "staff.limits.owner-only");
                    return;
                }
                new PrivilegesMenu(targetUuid, targetName, this).open(v);
            });
        }
    }

    // =========================================================================
    // 3. МЕНЮ ВЫБОРА ПРИЧИНЫ И СРОКА САНКЦИИ (+ ДИАЛОГ СВОЕЙ ПРИЧИНЫ)
    // =========================================================================

    private final class ReasonMenu extends Menu {
        private final UUID targetUuid;
        private final String targetName;
        private final Type type;
        private final int stage;
        private int duration; // минуты для MUTE, часы для TEMPBAN
        private final Menu back;

        ReasonMenu(UUID targetUuid, String targetName, Type type, int stage, int defaultDuration, Menu back) {
            super(4, plugin.messages().get("staff.reasons.title",
                    Placeholder.unparsed("type", typeLabel(type, stage)),
                    Placeholder.unparsed("target", targetName)));
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.type = type;
            this.stage = stage;
            this.duration = defaultDuration;
            this.back = back;
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 36; i++) set(i, bg, null);

            boolean owner = isOwner(viewer);

            // верхний ряд: выбор длительности (для мута и временного бана)
            if (type == Type.MUTE) {
                int[] presets = owner ? new int[]{15, 30, 120, 360, 720, 1440, 4320} : new int[]{15, 30, 60, 180, 360, 720};
                int slot = 1;
                for (int min : presets) {
                    if ( min > modMaxMuteMinutes() && !owner) continue;
                    boolean sel = duration == min;
                    String label = formatDuration(min * 60_000L);
                    ItemStack icon = Items.icon(sel ? Material.LIME_CONCRETE : Material.CLOCK,
                            m().item("staff.reasons.duration-item", Placeholder.unparsed("duration", label)),
                            m().lore("staff.reasons.duration-lore"));
                    if (sel) Items.glow(icon);
                    set(slot++, icon, v -> {
                        this.duration = min;
                        Fx.sound(v.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
                        open(v);
                    });
                }
            } else if (type == Type.TEMPBAN) {
                int[] presets = owner ? new int[]{2, 6, 24, 72, 168, 336, 720} : new int[]{2, 6, 12, 24, 48, 72};
                int slot = 1;
                for (int hrs : presets) {
                    if (hrs > modMaxBanHours() && !owner) continue;
                    boolean sel = duration == hrs;
                    String label = formatDuration(hrs * 3600_000L);
                    ItemStack icon = Items.icon(sel ? Material.LIME_CONCRETE : Material.CLOCK,
                            m().item("staff.reasons.duration-item", Placeholder.unparsed("duration", label)),
                            m().lore("staff.reasons.duration-lore"));
                    if (sel) Items.glow(icon);
                    set(slot++, icon, v -> {
                        this.duration = hrs;
                        Fx.sound(v.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
                        open(v);
                    });
                }
            }

            // средний ряд: готовые причины в 1 клик
            List<String> presets = stage == 4
                    ? m().rawList("staff.reasons.serious-presets")
                    : m().rawList("staff.reasons.standard-presets");
            int rSlot = 10;
            for (String reason : presets) {
                if (rSlot > 16) break;
                set(rSlot++, Items.icon(Material.PAPER,
                        m().item("staff.reasons.preset-name", Placeholder.unparsed("reason", reason)),
                        m().lore("staff.reasons.preset-lore")), v -> {
                    v.closeInventory();
                    execute(v, reason);
                });
            }

            // кнопка своей причины через диалоговое окно 1.21.6+
            set(22, Items.glow(Items.icon(Material.WRITABLE_BOOK,
                    m().item("staff.reasons.custom-name"),
                    m().lore("staff.reasons.custom-lore"))), v -> {
                v.closeInventory();
                openCustomDialog(v);
            });

            if (back != null) {
                set(27, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
            }
        }

        private void execute(Player staff, String reason) {
            switch (type) {
                case WARN -> issueWarn(staff, targetUuid, targetName, reason);
                case MUTE -> issueMute(staff, targetUuid, targetName, duration, reason);
                case TEMPBAN -> issueTempBan(staff, targetUuid, targetName, duration, stage >= 3 ? 3 : 2, reason);
                case BAN -> issuePermBan(staff, targetUuid, targetName, 3, reason);
                case KICK -> issueKick(staff, targetUuid, targetName, reason);
            }
        }

        private void openCustomDialog(Player staff) {
            if (!plugin.dialogs().enabled()) {
                execute(staff, m().raw("staff.reasons.default"));
                return;
            }
            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(Dialogs.text("reason", m().get("staff.dialog.reason-label"), "", 80));
            if (type == Type.MUTE) {
                int max = isOwner(staff) ? 10080 : modMaxMuteMinutes();
                inputs.add(Dialogs.number("duration", m().get("staff.dialog.mute-minutes-label"), 5, max, Math.min(duration, max)));
            } else if (type == Type.TEMPBAN) {
                int max = isOwner(staff) ? 720 : modMaxBanHours();
                inputs.add(Dialogs.number("duration", m().get("staff.dialog.ban-hours-label"), 1, max, Math.min(duration, max)));
            }
            plugin.dialogs().confirm(staff,
                    m().get("staff.reasons.title",
                            Placeholder.unparsed("type", typeLabel(type, stage)),
                            Placeholder.unparsed("target", targetName)),
                    m().lore("staff.dialog.punish-body", Placeholder.unparsed("target", targetName)),
                    inputs,
                    m().get("staff.dialog.punish-yes"),
                    m().get("friends.dialog.no"),
                    (v, view) -> {
                        String r = Dialogs.textValue(view, "reason");
                        Integer d = Dialogs.intValue(view, "duration");
                        if (d != null && d > 0) this.duration = d;
                        execute(v, r);
                    });
        }
    }

    // =========================================================================
    // 4. ИСТОРИЯ НАКАЗАНИЙ ИГРОКА И ЖУРНАЛ СЕРВЕРА
    // =========================================================================

    private final class HistoryMenu extends Menu {
        private final UUID targetUuid;
        private final String targetName;
        private final Menu back;

        HistoryMenu(UUID targetUuid, String targetName, Menu back) {
            super(6, plugin.messages().get("staff.history.title", Placeholder.unparsed("name", targetName)));
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.back = back;
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 54; i++) set(i, bg, null);

            List<Punishment> list = moderation.history(targetUuid, 45);
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.size() && i < 45; i++) {
                Punishment p = list.get(i);
                setPunishmentSlot(this, i, p, now, () -> open(viewer));
            }
            if (back != null) {
                set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
            } else {
                set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> openMain(v));
            }
        }
    }

    private final class AuditLogMenu extends Menu {
        private final int page;

        AuditLogMenu(int page) {
            super(6, plugin.messages().get("staff.history.audit-title"));
            this.page = Math.max(0, page);
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 54; i++) set(i, bg, null);

            List<Punishment> all = moderation.recent(180);
            int perPage = 45;
            int start = page * perPage;
            long now = System.currentTimeMillis();
            for (int i = 0; i < perPage && start + i < all.size(); i++) {
                Punishment p = all.get(start + i);
                setPunishmentSlot(this, i, p, now, () -> open(viewer));
            }
            set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> openMain(v));
            if (page > 0) {
                set(48, Items.icon(Material.SPECTRAL_ARROW, m().item("ah.prev.name"), List.of()), v -> new AuditLogMenu(page - 1).open(v));
            }
            if (start + perPage < all.size()) {
                set(50, Items.icon(Material.SPECTRAL_ARROW, m().item("ah.next.name"), List.of()), v -> new AuditLogMenu(page + 1).open(v));
            }
        }
    }

    private void setPunishmentSlot(Menu menu, int slot, Punishment p, long now, Runnable refresh) {
        Material mat = switch (p.type()) {
            case WARN -> Material.YELLOW_DYE;
            case MUTE -> Material.ORANGE_DYE;
            case TEMPBAN -> Material.COPPER_INGOT;
            case BAN -> Material.RED_DYE;
            case KICK -> Material.LEATHER_BOOTS;
        };
        String state;
        if (p.revokedBy() != null) {
            state = m().raw("staff.history.state-revoked").replace("<by>", p.revokedBy());
        } else if (p.isEffective(now)) {
            state = m().raw("staff.history.state-active");
        } else {
            state = m().raw("staff.history.state-expired");
        }
        String date = DATE_FMT.format(Instant.ofEpochMilli(p.created()));
        List<Component> lore = m().lore("staff.history.entry-lore",
                Placeholder.unparsed("id", String.valueOf(p.id())),
                Placeholder.unparsed("target", p.targetName()),
                Placeholder.unparsed("staff", p.staffName()),
                Placeholder.unparsed("type", typeLabel(p.type(), p.stage())),
                Placeholder.unparsed("reason", p.reason()),
                Placeholder.unparsed("date", date),
                Placeholder.parsed("state", state));
        ItemStack icon = Items.icon(mat,
                m().item("staff.history.entry-name",
                        Placeholder.unparsed("id", String.valueOf(p.id())),
                        Placeholder.unparsed("target", p.targetName()),
                        Placeholder.unparsed("type", typeLabel(p.type(), p.stage()))),
                lore);
        if (p.isEffective(now) && p.revokedBy() == null) Items.glow(icon);

        menu.setClick(slot, icon, (v, click) -> {
            if (click == ClickType.SHIFT_RIGHT) {
                if (p.type() == Type.BAN && !isOwner(v)) {
                    m().send(v, "staff.limits.owner-only");
                    return;
                }
                if (moderation.revokeById(p.id(), v.getName())) {
                    m().send(v, "staff.history.revoked-ok", Placeholder.unparsed("id", String.valueOf(p.id())));
                    refresh.run();
                }
            } else if (click == ClickType.LEFT) {
                new PlayerManageMenu(p.target(), p.targetName(), 0).open(v);
            }
        });
    }

    // =========================================================================
    // 5. ОСМОТР ИНВЕНТАРЯ, ЭНДЕР-СУНДУКА И ТОЧЕК ДОМА ИГРОКА
    // =========================================================================

    private void openInvSee(Player staff, Player target, Menu back) {
        if (isOwner(staff) && !staff.getUniqueId().equals(target.getUniqueId())) {
            // Создатель может открыть и как красивую карточку со всей бронёй, и редактировать
            new InvSeeMenu(target, back, true).open(staff);
        } else {
            new InvSeeMenu(target, back, false).open(staff);
        }
    }

    private void openEnderChest(Player staff, Player target, Menu back) {
        if (isOwner(staff)) {
            staff.openInventory(target.getEnderChest());
        } else {
            new ReadOnlyEnderMenu(target, back).open(staff);
        }
    }

    private final class InvSeeMenu extends Menu {
        private final Player target;
        private final Menu back;
        private final boolean canTake;

        InvSeeMenu(Player target, Menu back, boolean canTake) {
            super(6, plugin.messages().get("staff.invsee.title", Placeholder.unparsed("name", target.getName())));
            this.target = target;
            this.back = back;
            this.canTake = canTake;
        }

        @Override
        protected void render(Player viewer) {
            if (!target.isOnline()) {
                viewer.closeInventory();
                return;
            }
            PlayerInventory inv = target.getInventory();
            ItemStack[] contents = inv.getStorageContents();
            for (int i = 0; i < contents.length && i < 36; i++) {
                final int idx = i;
                ItemStack it = contents[i];
                if (it != null && !it.getType().isAir()) {
                    setClick(i, it.clone(), (v, click) -> {
                        if (canTake && click == ClickType.SHIFT_RIGHT) {
                            ItemStack cur = inv.getItem(idx);
                            if (cur != null && !cur.getType().isAir()) {
                                Items.give(v, cur.clone());
                                inv.setItem(idx, null);
                                open(v);
                            }
                        }
                    });
                }
            }
            ItemStack sep = pane(Material.GRAY_STAINED_GLASS_PANE);
            for (int i = 36; i < 54; i++) set(i, sep, null);

            // броня и левая рука (слоты 45..49)
            ItemStack[] armor = inv.getArmorContents();
            for (int i = 0; i < armor.length && i < 4; i++) {
                ItemStack a = armor[i];
                if (a != null && !a.getType().isAir()) set(45 + i, a.clone(), null);
            }
            ItemStack off = inv.getItemInOffHand();
            if (!off.getType().isAir()) set(49, off.clone(), null);

            if (back != null) {
                set(53, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
            }
        }
    }

    private final class ReadOnlyEnderMenu extends Menu {
        private final Player target;
        private final Menu back;

        ReadOnlyEnderMenu(Player target, Menu back) {
            super(4, plugin.messages().get("staff.invsee.ec-title", Placeholder.unparsed("name", target.getName())));
            this.target = target;
            this.back = back;
        }

        @Override
        protected void render(Player viewer) {
            if (!target.isOnline()) {
                viewer.closeInventory();
                return;
            }
            ItemStack[] contents = target.getEnderChest().getContents();
            for (int i = 0; i < contents.length && i < 27; i++) {
                if (contents[i] != null && !contents[i].getType().isAir()) {
                    set(i, contents[i].clone(), null);
                }
            }
            ItemStack sep = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 27; i < 36; i++) set(i, sep, null);
            if (back != null) {
                set(31, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
            }
        }
    }

    private final class ReadOnlyContainerMenu extends Menu {
        private final Inventory source;

        ReadOnlyContainerMenu(Inventory source) {
            super(Math.max(1, Math.min(6, (source.getSize() + 8) / 9)),
                    plugin.messages().get("staff.invsee.container-title"));
            this.source = source;
        }

        @Override
        protected void render(Player viewer) {
            ItemStack[] contents = source.getContents();
            for (int i = 0; i < contents.length && i < getInventory().getSize(); i++) {
                if (contents[i] != null && !contents[i].getType().isAir()) {
                    set(i, contents[i].clone(), null);
                }
            }
        }
    }

    private final class PlayerHomesMenu extends Menu {
        private final UUID targetUuid;
        private final String targetName;
        private final Menu back;

        PlayerHomesMenu(UUID targetUuid, String targetName, Menu back) {
            super(4, plugin.messages().get("staff.homes.title", Placeholder.unparsed("name", targetName)));
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.back = back;
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 36; i++) set(i, bg, null);

            List<HomesFeature.Home> list = plugin.homes().homes(targetUuid);
            for (int i = 0; i < list.size() && i < 27; i++) {
                HomesFeature.Home h = list.get(i);
                set(i, Items.icon(Material.RED_BED,
                        m().item("homes.icon-name", Placeholder.unparsed("name", h.name())),
                        m().lore("staff.homes.icon-lore",
                                Placeholder.unparsed("world", h.world()),
                                Placeholder.unparsed("x", String.valueOf((int) Math.floor(h.x()))),
                                Placeholder.unparsed("y", String.valueOf((int) Math.floor(h.y()))),
                                Placeholder.unparsed("z", String.valueOf((int) Math.floor(h.z()))))), v -> {
                    Location loc = h.location();
                    if (loc == null) {
                        m().send(v, "homes.no-world", Placeholder.unparsed("name", h.name()));
                        return;
                    }
                    v.closeInventory();
                    v.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    m().actionBar(v, "homes.arrived", Placeholder.unparsed("name", targetName + ":" + h.name()));
                });
            }
            if (back != null) {
                set(27, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
            }
        }
    }

    // =========================================================================
    // 6. ПОРЯДОК НА СЕРВЕРЕ (ЧАТ, ОЧИСТКА ЛАГОВ, ОБЪЯВЛЕНИЯ, ПОГОДА, СТАТУС)
    // =========================================================================

    private final class ServerOrderMenu extends Menu {
        ServerOrderMenu() {
            super(4, plugin.messages().get("staff.order.title"));
        }

        @Override
        protected void render(Player viewer) {
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 36; i++) set(i, bg, null);

            boolean owner = isOwner(viewer);

            // сводка TPS, RAM, сущностей и экономики (слот 4)
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            int entities = 0;
            int chunks = 0;
            for (World w : Bukkit.getWorlds()) {
                entities += w.getEntityCount();
                chunks += w.getLoadedChunks().length;
            }
            set(4, Items.icon(Material.COMPARATOR,
                    m().item("staff.order.stats-name"),
                    m().lore("staff.order.stats-lore",
                            Placeholder.unparsed("tps", String.format(Locale.US, "%.1f", tps)),
                            Placeholder.unparsed("ram_used", String.valueOf(usedMb)),
                            Placeholder.unparsed("ram_max", String.valueOf(maxMb)),
                            Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())),
                            Placeholder.unparsed("entities", String.valueOf(entities)),
                            Placeholder.unparsed("chunks", String.valueOf(chunks)),
                            Money.tag(m(), "supply", eco().supply()))), v -> open(v));

            // 1. Медленный режим чата (слот 10)
            String slowText = chatSlowSeconds <= 0 ? m().raw("menu.off") : chatSlowSeconds + " с.";
            ItemStack slowIcon = Items.icon(Material.CLOCK,
                    m().item("staff.order.slowmode-name"),
                    m().lore("staff.order.slowmode-lore", Placeholder.unparsed("state", slowText)));
            if (chatSlowSeconds > 0) Items.glow(slowIcon);
            set(10, slowIcon, v -> {
                chatSlowSeconds = switch (chatSlowSeconds) {
                    case 0 -> 3;
                    case 3 -> 5;
                    case 5 -> 10;
                    case 10 -> 30;
                    default -> 0;
                };
                Component msg = chatSlowSeconds > 0
                        ? m().get("staff.order.slowmode-on", Placeholder.unparsed("seconds", String.valueOf(chatSlowSeconds)))
                        : m().get("staff.order.slowmode-off");
                for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
                open(v);
            });

            // 2. Тишина в чате (слот 11)
            ItemStack muteChatIcon = Items.icon(Material.SCULK_SHRIEKER,
                    m().item("staff.order.mutechat-name"),
                    m().lore("staff.order.mutechat-lore", Placeholder.component("state", stateComp(chatMuted))));
            if (chatMuted) Items.glow(muteChatIcon);
            set(11, muteChatIcon, v -> {
                chatMuted = !chatMuted;
                Component msg = m().get(chatMuted ? "staff.order.mutechat-on" : "staff.order.mutechat-off",
                        Placeholder.unparsed("staff", v.getName()));
                for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
                open(v);
            });

            // 3. Очистка чата от спама/рекламы (слот 12)
            set(12, Items.icon(Material.BRUSH,
                    m().item("staff.order.clearchat-name"),
                    m().lore("staff.order.clearchat-lore")), v -> {
                Component blank = Component.empty();
                Component cleared = m().get("staff.order.clearchat-broadcast", Placeholder.unparsed("staff", v.getName()));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (int i = 0; i < 80; i++) p.sendMessage(blank);
                    p.sendMessage(cleared);
                }
                open(v);
            });

            // 4. Уборка валяющихся предметов и лишних враждебных мобов (слот 13)
            set(13, Items.icon(Material.HOPPER,
                    m().item("staff.order.cleanup-name"),
                    m().lore("staff.order.cleanup-lore")), v -> {
                int removedItems = 0;
                int removedMobs = 0;
                for (World w : Bukkit.getWorlds()) {
                    for (Entity e : w.getEntities()) {
                        if (e instanceof Item item && item.getTicksLived() > 200) {
                            item.remove();
                            removedItems++;
                        } else if (e instanceof Monster mob && mob.customName() == null && !mob.isLeashed()
                                && !(mob instanceof Tameable t && t.isTamed()) && mob.getEquipment().getHelmet().getType().isAir()) {
                            mob.remove();
                            removedMobs++;
                        }
                    }
                }
                m().send(v, "staff.order.cleanup-done",
                        Placeholder.unparsed("items", String.valueOf(removedItems)),
                        Placeholder.unparsed("mobs", String.valueOf(removedMobs)));
                open(v);
            });

            // 5. Объявление на весь сервер и в Telegram (слот 14)
            set(14, Items.icon(Material.GOAT_HORN,
                    m().item("staff.order.announce-name"),
                    m().lore("staff.order.announce-lore")), v -> {
                v.closeInventory();
                openAnnounceDialog(v);
            });

            // 6. Ясная погода и утро (только Создатель, слот 15)
            set(15, Items.icon(Material.SUNFLOWER,
                    m().item("staff.order.sun-name"),
                    m().lore("staff.order.sun-lore")), v -> {
                if (!isOwner(v)) {
                    m().send(v, "staff.limits.owner-only");
                    return;
                }
                for (World w : Bukkit.getWorlds()) {
                    if (w.getEnvironment() == World.Environment.NORMAL) {
                        w.setStorm(false);
                        w.setThundering(false);
                        w.setTime(1000L);
                    }
                }
                m().send(v, "staff.order.sun-done");
                open(v);
            });

            // 7. Перезагрузка конфигов /pawsocks reload (только Создатель, слот 16)
            if (owner) {
                set(16, Items.icon(Material.COMMAND_BLOCK,
                        m().item("staff.order.reload-name"),
                        m().lore("staff.order.reload-lore")), v -> {
                    v.performCommand("pawsocks reload");
                    open(v);
                });
            }

            set(27, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), v -> openMain(v));
        }
    }

    private void openAnnounceDialog(Player staff) {
        if (!plugin.dialogs().enabled()) return;
        plugin.dialogs().confirm(staff,
                m().get("staff.dialog.announce-title"),
                m().lore("staff.dialog.announce-body"),
                List.of(Dialogs.text("text", m().get("staff.dialog.announce-label"), "", 160)),
                m().get("staff.dialog.announce-yes"),
                m().get("friends.dialog.no"),
                (v, view) -> {
                    String text = Dialogs.textValue(view, "text");
                    if (text.isEmpty()) return;
                    Component chatMsg = m().get("staff.order.announce-chat",
                            Placeholder.unparsed("staff", v.getName()),
                            Placeholder.unparsed("text", text));
                    Title title = Title.title(
                            m().get("staff.order.announce-title"),
                            m().get("staff.order.announce-subtitle", Placeholder.unparsed("text", text)),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(700))
                    );
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendMessage(chatMsg);
                        p.showTitle(title);
                        Fx.sound(p.getLocation(), "minecraft:block.bell.use", 0.8f, 1.2f);
                    }
                    plugin.telegram().sendHtml("📢 <b>Объявление от " + escapeHtml(v.getName()) + ":</b> " + escapeHtml(text));
                });
    }

    // =========================================================================
    // 7. ОСОБЫЕ ПРИВИЛЕГИИ, РОЛИ И ВАЛЮТА (ТОЛЬКО ДЛЯ СОЗДАТЕЛЯ)
    // =========================================================================

    private final class PrivilegesMenu extends Menu {
        private final UUID targetUuid;
        private final String targetName;
        private final Menu back;

        PrivilegesMenu(UUID targetUuid, String targetName, Menu back) {
            super(6, plugin.messages().get("staff.privs.title", Placeholder.unparsed("name", targetName)));
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.back = back;
        }

        @Override
        protected void render(Player viewer) {
            if (!isOwner(viewer)) {
                viewer.closeInventory();
                return;
            }
            ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < 54; i++) set(i, bg, null);

            // --- РЯД 1: РОЛИ LUCKPERMS (слоты 2..6) ---
            setRoleButton(2, Material.STONE, "default", m().raw("staff.role.player"));
            setRoleButton(3, Material.COPPER_INGOT, "active", m().raw("staff.role.active"));
            setRoleButton(4, Material.AMETHYST_SHARD, "supporter", m().raw("staff.role.supporter"));
            setRoleButton(5, Material.DIAMOND, "moderator", m().raw("staff.role.mod"));
            setRoleButton(6, Material.NETHER_STAR, "owner", m().raw("staff.role.owner"));

            // --- РЯД 2: ВАЛЮТА — ЛАПКИ И АСТРЫ (слоты 10..16) ---
            long bal = eco().balance(targetUuid);
            int ast = plugin.astras().balance(targetUuid);

            set(10, Items.icon(Material.GOLD_NUGGET,
                    m().item("staff.privs.lapki-add-100"),
                    m().lore("staff.privs.lapki-lore", Money.tag(m(), "balance", bal))), v -> {
                eco().deposit(targetUuid, 100_00L, "admin_give", "panel by " + v.getName());
                Fx.sound(v.getLocation(), "minecraft:entity.experience_orb.pickup", 0.6f, 1.4f);
                open(v);
            });
            set(11, Items.icon(Material.GOLD_INGOT,
                    m().item("staff.privs.lapki-add-1000"),
                    m().lore("staff.privs.lapki-lore", Money.tag(m(), "balance", bal))), v -> {
                eco().deposit(targetUuid, 1000_00L, "admin_give", "panel by " + v.getName());
                Fx.sound(v.getLocation(), "minecraft:entity.experience_orb.pickup", 0.6f, 1.4f);
                open(v);
            });
            set(12, Items.icon(Material.GOLD_BLOCK,
                    m().item("staff.privs.lapki-custom"),
                    m().lore("staff.privs.lapki-lore", Money.tag(m(), "balance", bal))), v -> {
                v.closeInventory();
                openCurrencyDialog(v, true);
            });

            set(14, Items.icon(Material.AMETHYST_CLUSTER,
                    m().item("staff.privs.astra-add-5"),
                    m().lore("staff.privs.astra-lore", AstraFeature.tag(m(), "astra", ast))), v -> {
                plugin.astras().give(targetUuid, 5, "astra_admin_give", "panel by " + v.getName());
                Fx.sound(v.getLocation(), "minecraft:block.amethyst_block.chime", 0.8f, 1.4f);
                open(v);
            });
            set(15, Items.icon(Material.NETHER_STAR,
                    m().item("staff.privs.astra-add-25"),
                    m().lore("staff.privs.astra-lore", AstraFeature.tag(m(), "astra", ast))), v -> {
                plugin.astras().give(targetUuid, 25, "astra_admin_give", "panel by " + v.getName());
                Fx.sound(v.getLocation(), "minecraft:block.amethyst_block.chime", 0.8f, 1.4f);
                open(v);
            });
            set(16, Items.icon(Material.BEACON,
                    m().item("staff.privs.astra-custom"),
                    m().lore("staff.privs.astra-lore", AstraFeature.tag(m(), "astra", ast))), v -> {
                v.closeInventory();
                openCurrencyDialog(v, false);
            });

            // --- РЯД 3: ОСОБЫЕ ПРИВИЛЕГИИ НАВСЕГДА (слоты 19..25) ---
            boolean hasNick = plugin.perks().owns(targetUuid, StarFeature.NICK);
            ItemStack nickIcon = Items.icon(Material.NAME_TAG,
                    m().item("astra.star-nick.name"),
                    m().lore("staff.privs.toggle-perk-lore", Placeholder.component("state", stateComp(hasNick))));
            if (hasNick) Items.glow(nickIcon);
            set(19, nickIcon, v -> {
                if (hasNick) plugin.perks().revoke(targetUuid, StarFeature.NICK);
                else plugin.perks().grant(targetUuid, StarFeature.NICK);
                open(v);
            });

            boolean hasJoin = plugin.perks().owns(targetUuid, StarFeature.JOIN);
            ItemStack joinIcon = Items.icon(Material.FIREWORK_ROCKET,
                    m().item("astra.star-join.name"),
                    m().lore("staff.privs.toggle-perk-lore", Placeholder.component("state", stateComp(hasJoin))));
            if (hasJoin) Items.glow(joinIcon);
            set(20, joinIcon, v -> {
                if (hasJoin) {
                    plugin.perks().revoke(targetUuid, StarFeature.JOIN);
                    plugin.stars().revokeJoin(targetUuid);
                } else {
                    plugin.perks().grant(targetUuid, StarFeature.JOIN);
                    plugin.stars().ownedJoin(targetUuid);
                }
                open(v);
            });

            // Выдать всю косметику (следы, ауры, символы)
            set(21, Items.icon(Material.PINK_PETALS,
                    m().item("staff.privs.all-cosmetics-name"),
                    m().lore("staff.privs.all-cosmetics-lore")), v -> {
                for (Trail t : Trail.values()) {
                    if (t.buyable) plugin.perks().grant(targetUuid, "trail:" + t.id);
                }
                for (CosmeticsFeature.Aura a : CosmeticsFeature.Aura.values()) {
                    plugin.perks().grant(targetUuid, "aura:" + a.id);
                }
                for (CosmeticsFeature.Suffix s : CosmeticsFeature.Suffix.values()) {
                    plugin.perks().grant(targetUuid, "suffix:" + s.id);
                }
                plugin.cosmetics().invalidateCache(targetUuid);
                m().send(v, "staff.privs.all-cosmetics-done", Placeholder.unparsed("target", targetName));
                Fx.sound(v.getLocation(), "minecraft:entity.player.levelup", 0.7f, 1.4f);
                open(v);
            });

            // +1 слот точки дома
            int extraHomes = plugin.perks().counter(targetUuid, "home_slots");
            set(22, Items.icon(Material.RED_BED,
                    m().item("staff.privs.add-home-name"),
                    m().lore("staff.privs.add-home-lore", Placeholder.unparsed("count", String.valueOf(extraHomes)))), v -> {
                plugin.perks().add(targetUuid, "home_slots", 1);
                Fx.sound(v.getLocation(), "minecraft:block.note_block.bell", 0.7f, 1.4f);
                open(v);
            });

            // Выдать Звёздный фонарь
            set(24, plugin.astraItems().lamp(), v -> giveTargetOrSelf(v, plugin.astraItems().lamp()));

            // Выдать Носки
            set(25, plugin.socks().build(org.bukkit.DyeColor.PINK, org.bukkit.DyeColor.WHITE),
                    v -> giveTargetOrSelf(v, plugin.socks().build(org.bukkit.DyeColor.PINK, org.bukkit.DyeColor.WHITE)));

            // --- РЯД 4: ОСОБЫЕ ИНСТРУМЕНТЫ ЗА АСТРЫ (слоты 29..33) ---
            set(29, Items.glow(Items.icon(Material.NETHERITE_PICKAXE,
                    m().item("staff.privs.drill-pickaxe-name"),
                    m().lore("staff.privs.give-item-lore"))), v -> {
                ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
                plugin.astraItems().apply(pick, club.zeroiq.pawsocks.astra.AstraItems.Ench.DRILL);
                giveTargetOrSelf(v, pick);
            });

            set(31, Items.glow(Items.icon(Material.NETHERITE_AXE,
                    m().item("staff.privs.timber-axe-name"),
                    m().lore("staff.privs.give-item-lore"))), v -> {
                ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
                plugin.astraItems().apply(axe, club.zeroiq.pawsocks.astra.AstraItems.Ench.TIMBER);
                giveTargetOrSelf(v, axe);
            });

            set(33, Items.glow(Items.icon(Material.DIAMOND_PICKAXE,
                    m().item("staff.privs.spawner-pickaxe-name"),
                    m().lore("staff.privs.give-item-lore"))), v -> {
                ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
                plugin.astraItems().apply(pick, club.zeroiq.pawsocks.astra.AstraItems.Ench.SPAWNER);
                giveTargetOrSelf(v, pick);
            });

            // исцелить и покормить игрока + починить предмет в руке
            set(40, Items.icon(Material.ENCHANTED_GOLDEN_APPLE,
                    m().item("staff.privs.heal-repair-name"),
                    m().lore("staff.privs.heal-repair-lore")), v -> {
                Player t = Bukkit.getPlayer(targetUuid);
                if (t == null) {
                    m().send(v, "friends.not-found");
                    return;
                }
                t.setHealth(20.0);
                t.setFoodLevel(20);
                t.setSaturation(20f);
                t.setFireTicks(0);
                ItemStack hand = t.getInventory().getItemInMainHand();
                if (hand.getItemMeta() instanceof Damageable dmg && dmg.hasDamage()) {
                    dmg.setDamage(0);
                    hand.setItemMeta(dmg);
                }
                m().send(v, "staff.privs.heal-repair-done", Placeholder.unparsed("target", t.getName()));
                Fx.hearts(t, 8);
            });

            if (back != null) {
                set(45, Items.icon(Material.ARROW, m().item("menu.back"), List.of()), back::open);
            }
        }

        private void setRoleButton(int slot, Material mat, String group, String roleDisplay) {
            set(slot, Items.icon(mat,
                    m().item("staff.privs.role-item", Placeholder.parsed("role", roleDisplay)),
                    m().lore("staff.privs.role-lore", Placeholder.unparsed("group", group))), v -> {
                if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                    m().send(v, "staff.privs.no-luckperms");
                    return;
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + targetUuid + " parent set " + group);
                m().send(v, "staff.privs.role-set",
                        Placeholder.unparsed("target", targetName),
                        Placeholder.parsed("role", roleDisplay));
                Fx.sound(v.getLocation(), "minecraft:entity.player.levelup", 0.7f, 1.3f);
                open(v);
            });
        }

        private void giveTargetOrSelf(Player staff, ItemStack item) {
            Player target = Bukkit.getPlayer(targetUuid);
            Player receiver = target != null ? target : staff;
            Items.give(receiver, item);
            m().send(staff, "staff.privs.item-given", Placeholder.unparsed("target", receiver.getName()));
            Fx.sound(staff.getLocation(), "minecraft:entity.item.pickup", 0.7f, 1.4f);
        }

        private void openCurrencyDialog(Player staff, boolean lapki) {
            if (!plugin.dialogs().enabled()) return;
            plugin.dialogs().confirm(staff,
                    m().get(lapki ? "staff.dialog.lapki-title" : "staff.dialog.astra-title",
                            Placeholder.unparsed("target", targetName)),
                    m().lore("staff.dialog.currency-body"),
                    List.of(Dialogs.text("amount", m().get("staff.dialog.amount-label"), "100", 12)),
                    m().get("staff.dialog.currency-yes"),
                    m().get("friends.dialog.no"),
                    (v, view) -> {
                        String raw = Dialogs.textValue(view, "amount");
                        if (lapki) {
                            long cents = Money.parse(raw);
                            if (cents >= 0) {
                                eco().set(targetUuid, cents, "panel by " + v.getName());
                                m().send(v, "eco.admin-done",
                                        Placeholder.unparsed("name", targetName),
                                        Money.tag(m(), "balance", eco().balance(targetUuid)));
                            }
                        } else {
                            int n = parseInt(raw, -1);
                            if (n >= 0) {
                                plugin.astras().set(targetUuid, n, "panel by " + v.getName());
                                m().send(v, "astra.admin-done",
                                        Placeholder.unparsed("name", targetName),
                                        AstraFeature.tag(m(), "astra", plugin.astras().balance(targetUuid)));
                            }
                        }
                        open(v);
                    });
        }
    }
}
