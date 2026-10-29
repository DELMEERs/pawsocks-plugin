package club.zeroiq.pawsocks.util;

import club.zeroiq.pawsocks.PawsocksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Связь с телеграм-чатом через плагин tgbridge (наш форк). Отдельной зависимости нет:
 * сообщения уходят консольной командой «tgbridge send», а стандартное сообщение о входе
 * можно заменить своим через события tgbridge (по рефлексии, чтобы pawsocks работал и без него).
 */
public final class Telegram {

    private final PawsocksPlugin plugin;
    private volatile boolean active = true;

    public Telegram(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return plugin.getConfig().getBoolean("telegram.enabled", true)
                && Bukkit.getPluginManager().isPluginEnabled("tgbridge");
    }

    /** Отправить сообщение в общий чат. html — разметка телеграма (&lt;b&gt;, &lt;i&gt;), в одну строку. */
    public void sendHtml(String html) {
        if (!available() || html == null || html.isBlank()) return;
        String chat = plugin.getConfig().getString("telegram.chat", "default");
        String line = html.replace('\n', ' ');
        Runnable run = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tgbridge send html " + chat + " " + line);
        if (Bukkit.isPrimaryThread()) run.run();
        else Bukkit.getScheduler().runTask(plugin, run);
    }

    /**
     * Не отправлять стандартное «заходит на сервер» для тех, у кого своё сообщение о входе.
     * Возвращает false, если tgbridge нет или его api поменялся.
     */
    public boolean suppressJoin(Predicate<UUID> replaced) {
        if (!available()) return false;
        try {
            Plugin tg = Bukkit.getPluginManager().getPlugin("tgbridge");
            ClassLoader cl = tg.getClass().getClassLoader();
            Class<?> events = Class.forName("dev.vanutp.tgbridge.common.TgbridgeEvents", true, cl);
            Object instance = events.getField("INSTANCE").get(null);
            Object handler = events.getMethod("getJOIN").invoke(instance);
            Class<?> joinEvent = Class.forName("dev.vanutp.tgbridge.common.models.TgbridgeJoinEvent", true, cl);
            Class<?> playerIface = Class.forName("dev.vanutp.tgbridge.common.models.ITgbridgePlayer", true, cl);
            Class<?> cancellable = Class.forName("dev.vanutp.tgbridge.common.models.Cancellable", true, cl);
            Method getPlayer = joinEvent.getMethod("getPlayer");
            Method getUuid = playerIface.getMethod("getUuid");
            Method setCancelled = cancellable.getMethod("setCancelled", boolean.class);
            Method add = handler.getClass().getMethod("addListener", Consumer.class);
            Consumer<Object> listener = e -> {
                if (!active) return;
                try {
                    UUID uuid = (UUID) getUuid.invoke(getPlayer.invoke(e));
                    if (uuid != null && replaced.test(uuid)) setCancelled.invoke(e, true);
                } catch (Exception ex) {
                    plugin.getLogger().fine("tgbridge: " + ex);
                }
            };
            add.invoke(handler, listener);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("tgbridge: не удалось подключиться к событиям входа, особое сообщение не заменит обычное: " + t);
            return false;
        }
    }

    /** Слушатель в tgbridge снять нельзя — просто выключаем его при выгрузке плагина. */
    public void shutdown() {
        active = false;
    }
}
