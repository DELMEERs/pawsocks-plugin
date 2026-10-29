package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Items;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Быстрая продажа серверу.
 * /sell — меню: складываешь вещи, закрываешь — сервер покупает всё, что берёт, остальное возвращается.
 * /sell hand — продать то, что в руке. /sell all — все подходящие вещи из инвентаря (кроме брони и левой руки).
 */
public final class SellFeature implements CommandExecutor, TabCompleter, Listener {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 49;

    private final PawsocksPlugin plugin;

    public SellFeature(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Prices prices() {
        return plugin.prices();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("economy.sell.enabled", true);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            m().send(sender, "players-only");
            return true;
        }
        if (!enabled()) {
            m().send(player, "no-permission");
            return true;
        }
        String mode = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "hand" -> sellHand(player);
            case "all" -> sellAll(player);
            default -> openMenu(player);
        }
        return true;
    }

    // --- продажа ---

    /** Продаёт стопки, возвращает непроданное. Деньги зачисляются одной операцией. */
    private Result sell(Player player, List<ItemStack> items) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            if (prices().sellable(item)) {
                counts.merge(item.getType(), item.getAmount(), Integer::sum);
            } else {
                rest.add(item);
            }
        }
        long total = 0;
        int sold = 0;
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            total += prices().sell(e.getKey(), e.getValue());
            sold += e.getValue();
        }
        if (total > 0) {
            plugin.economy().deposit(player.getUniqueId(), total, "sell", sold + " шт.");
        }
        return new Result(sold, total, rest);
    }

    private record Result(int sold, long total, List<ItemStack> rest) {
    }

    private void report(Player player, Result r) {
        if (r.sold() == 0) {
            m().send(player, "sell.nothing");
            return;
        }
        m().send(player, "sell.done",
                Placeholder.unparsed("count", String.valueOf(r.sold())),
                Money.tag(m(), "money", r.total()),
                Money.tag(m(), "balance", plugin.economy().balance(player.getUniqueId())));
        Fx.sound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 0.7f, 1.2f);
    }

    private void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!prices().sellable(hand)) {
            m().send(player, "sell.not-sellable");
            return;
        }
        Result r = sell(player, List.of(hand.clone()));
        player.getInventory().setItemInMainHand(null);
        report(player, r);
    }

    private void sellAll(Player player) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> taken = new ArrayList<>();
        // только основной инвентарь и хотбар: броня и левая рука не трогаются
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inv.getItem(slot);
            if (prices().sellable(item)) {
                taken.add(item.clone());
                inv.setItem(slot, null);
            }
        }
        report(player, sell(player, taken));
    }

    // --- меню продажи ---

    private final class SellMenu implements InventoryHolder {
        private final Inventory inventory = Bukkit.createInventory(this, SIZE, m().get("sell.menu-title"));
        private boolean done;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private void openMenu(Player player) {
        SellMenu menu = new SellMenu();
        renderInfo(menu);
        player.openInventory(menu.getInventory());
    }

    private void renderInfo(SellMenu menu) {
        Inventory inv = menu.getInventory();
        long total = 0;
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (int i = 0; i < SIZE; i++) {
            if (i == INFO_SLOT) continue;
            ItemStack item = inv.getItem(i);
            if (prices().sellable(item)) counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            total += prices().quote(e.getKey(), e.getValue());
        }
        inv.setItem(INFO_SLOT, Items.icon(Material.GOLD_NUGGET, m().item("sell.menu-info"),
                m().lore("sell.menu-info-lore", Money.tag(m(), "money", total))));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)) return;
        if (event.getRawSlot() == INFO_SLOT) {
            event.setCancelled(true);
            return;
        }
        // после клика пересчитываем сумму
        Bukkit.getScheduler().runTask(plugin, () -> renderInfo(menu));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)) return;
        if (event.getRawSlots().contains(INFO_SLOT)) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> renderInfo(menu));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof SellMenu menu) || menu.done) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        menu.done = true;
        Inventory inv = menu.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            if (i == INFO_SLOT) continue;
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) items.add(item.clone());
        }
        inv.clear();
        if (items.isEmpty()) return;
        Result r = sell(player, items);
        // что сервер не купил — обратно в инвентарь, а не влезло — под ноги
        for (ItemStack left : player.getInventory().addItem(r.rest().toArray(ItemStack[]::new)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        if (!r.rest().isEmpty()) m().send(player, "sell.returned");
        if (r.sold() > 0) report(player, r);
    }

    /** На случай выключения сервера с открытым меню — вернуть вещи. */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof SellMenu) {
                p.closeInventory();
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : List.of("hand", "all")) {
            if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
