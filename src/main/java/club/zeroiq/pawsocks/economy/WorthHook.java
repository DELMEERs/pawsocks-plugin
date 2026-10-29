package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.PawsocksPlugin;

/**
 * Переходник к packetevents. Этот класс (и WorthLore) загружается только если плагин
 * packetevents есть на сервере — иначе java не найдёт его классы и pawsocks упал бы целиком.
 */
public final class WorthHook {

    private WorthHook() {
    }

    /** Подключает цену при наведении. Возвращает, как её отключить. */
    public static Runnable enable(PawsocksPlugin plugin) {
        WorthLore lore = new WorthLore(plugin);
        lore.register();
        return lore::unregister;
    }
}
