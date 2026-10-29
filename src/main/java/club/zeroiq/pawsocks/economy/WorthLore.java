package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Messages;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Цена при наведении: перед отправкой предметов игроку дописывает в описание строку с ценой.
 * Меняется только то, что видит игрок, — настоящие предметы на сервере не трогаются,
 * поэтому они складываются в стопки как обычно и не «запоминают» цену.
 *
 * В творческом режиме цена не показывается: там клиент сам присылает предметы серверу,
 * и дописанная строка попала бы в настоящий предмет.
 */
public final class WorthLore implements PacketListener, Listener {

    private final PawsocksPlugin plugin;
    private PacketListenerCommon registered;

    public WorthLore(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        registered = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        if (registered != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registered);
            registered = null;
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!plugin.getConfig().getBoolean("economy.worth-lore", true)) return;
        Object who = event.getPlayer();
        if (!(who instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) return;

        if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            packet.setItem(decorate(packet.getItem()));
            event.markForReEncode(true);
        } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            List<com.github.retrooper.packetevents.protocol.item.ItemStack> items = new ArrayList<>(packet.getItems());
            items.replaceAll(this::decorate);
            packet.setItems(items);
            event.markForReEncode(true);
        }
    }

    private com.github.retrooper.packetevents.protocol.item.ItemStack decorate(
            com.github.retrooper.packetevents.protocol.item.ItemStack packetItem) {
        if (packetItem == null || packetItem.isEmpty()) return packetItem;
        ItemStack item = SpigotConversionUtil.toBukkitItemStack(packetItem);
        if (!plugin.prices().sellable(item)) return packetItem;

        Messages m = plugin.messages();
        long unit = plugin.prices().quote(item.getType(), plugin.prices().unit(item), 1);
        List<Component> extra = new ArrayList<>();
        if (item.getAmount() > 1) {
            long stack = plugin.prices().quote(item);
            extra.add(m.item("worth.stack", Money.tag(m, "unit", unit), Money.tag(m, "stack", stack),
                    Placeholder.unparsed("count", String.valueOf(item.getAmount()))));
        } else {
            extra.add(m.item("worth.unit", Money.tag(m, "unit", unit)));
        }
        if (plugin.prices().multiplier(item.getType()) < 0.95) {
            extra.add(m.item("worth.saturated"));
        }

        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(extra);
        meta.lore(lore);
        item.setItemMeta(meta);
        return SpigotConversionUtil.fromBukkitItemStack(item);
    }

    /** В творческий режим и обратно — переслать инвентарь, чтобы цена исчезла или появилась. */
    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}
