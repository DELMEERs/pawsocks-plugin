package club.zeroiq.pawsocks.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

/**
 * Не даёт ничего утащить из меню и передаёт клики самому меню.
 * Действие выполняется на следующем тике: открывать и закрывать инвентари прямо внутри
 * события клика bukkit не советует — бывают «призрачные» предметы и рассинхрон.
 */
public final class MenuListener implements Listener {

    private final Plugin plugin;

    public MenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        ClickType type = event.getClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            // за тик игрок мог закрыть меню
            if (player.isOnline() && player.getOpenInventory().getTopInventory() == top) {
                menu.click(player, slot, type);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
