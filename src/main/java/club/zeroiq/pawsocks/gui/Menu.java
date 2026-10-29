package club.zeroiq.pawsocks.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Простое меню-инвентарь: предметы в слотах и действие на клик. Забрать предметы нельзя. */
public abstract class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    protected void set(int slot, ItemStack item, Consumer<Player> onClick) {
        inventory.setItem(slot, item);
        if (onClick != null) {
            actions.put(slot, onClick);
        } else {
            actions.remove(slot);
        }
    }

    protected void clear() {
        inventory.clear();
        actions.clear();
    }

    /** Заполняет меню. Вызывается при открытии и после кликов, чтобы показать новое состояние. */
    protected abstract void render(Player viewer);

    public void open(Player player) {
        clear();
        render(player);
        // если меню уже открыто — просто перерисовали, без мигания экрана
        if (player.getOpenInventory().getTopInventory() != inventory) {
            player.openInventory(inventory);
        }
    }

    void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            action.accept(player);
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
