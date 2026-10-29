package club.zeroiq.pawsocks.astra;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.astra.AstraItems.Ench;
import club.zeroiq.pawsocks.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Что делают вещи из магазина астр. */
public final class AstraEffects implements Listener {

    private static final Map<Material, Material> SMELT = new EnumMap<>(Material.class);

    static {
        SMELT.put(Material.RAW_IRON, Material.IRON_INGOT);
        SMELT.put(Material.RAW_GOLD, Material.GOLD_INGOT);
        SMELT.put(Material.RAW_COPPER, Material.COPPER_INGOT);
        SMELT.put(Material.IRON_ORE, Material.IRON_INGOT);
        SMELT.put(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT);
        SMELT.put(Material.GOLD_ORE, Material.GOLD_INGOT);
        SMELT.put(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT);
        SMELT.put(Material.COPPER_ORE, Material.COPPER_INGOT);
        SMELT.put(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT);
        SMELT.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
        SMELT.put(Material.RAW_IRON_BLOCK, Material.IRON_BLOCK);
        SMELT.put(Material.RAW_GOLD_BLOCK, Material.GOLD_BLOCK);
        SMELT.put(Material.RAW_COPPER_BLOCK, Material.COPPER_BLOCK);
    }

    private static final int TIMBER_LIMIT = 192;

    private final PawsocksPlugin plugin;
    /** Игроки, за которых сейчас ломаем «лишние» блоки — чтобы бур не запускал сам себя. */
    private final Set<UUID> busy = new HashSet<>();
    private BukkitTask sightTask;

    public AstraEffects(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private AstraItems items() {
        return plugin.astraItems();
    }

    public void start() {
        stop();
        // кошачье зрение: обновляем ночное зрение, пока надет шлем
        sightTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (items().has(p.getInventory().getHelmet(), Ench.CATSIGHT)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 20, 0, true, false, true));
                }
            }
        }, 40L, 100L);
    }

    public void stop() {
        if (sightTask != null) {
            sightTask.cancel();
            sightTask = null;
        }
    }

    private boolean usable(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    // --- бур 3×3 и лесоруб ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!usable(p)) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        Set<Ench> ench = items().enchants(tool);
        if (ench.isEmpty()) return;

        // магнит работает и для блоков, которые ломает бур
        if (ench.contains(Ench.MAGNET) && event.getExpToDrop() > 0) {
            p.giveExp(event.getExpToDrop());
            event.setExpToDrop(0);
        }
        if (busy.contains(p.getUniqueId()) || p.isSneaking()) return;
        if (ench.contains(Ench.DRILL)) {
            Block center = event.getBlock();
            if (center.getType().getHardness() <= 0 || !center.isPreferredTool(tool)) return;
            List<Block> extra = plane(p, center);
            Bukkit.getScheduler().runTask(plugin, () -> breakAll(p, tool.getType(), extra));
        } else if (ench.contains(Ench.TIMBER) && isLog(event.getBlock().getType())) {
            List<Block> logs = tree(event.getBlock());
            if (!logs.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> breakAll(p, tool.getType(), logs));
        }
    }

    /** Ломает блоки «рукой» игрока: как будто он копает сам — прочность, дроп, coreprotect. */
    private void breakAll(Player p, Material toolType, List<Block> blocks) {
        if (!p.isOnline()) return;
        busy.add(p.getUniqueId());
        try {
            for (Block b : blocks) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() != toolType) break; // инструмент сломался или игрок его убрал
                if (b.getType().isAir()) continue;
                p.breakBlock(b);
            }
        } finally {
            busy.remove(p.getUniqueId());
        }
    }

    private List<Block> plane(Player p, Block center) {
        BlockFace face = null;
        RayTraceResult hit = p.rayTraceBlocks(6);
        if (hit != null && center.equals(hit.getHitBlock())) face = hit.getHitBlockFace();
        if (face == null) face = Math.abs(p.getLocation().getPitch()) > 45 ? BlockFace.UP : p.getFacing();
        List<Block> out = new ArrayList<>();
        float hardness = center.getType().getHardness();
        ItemStack tool = p.getInventory().getItemInMainHand();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block blk = switch (face) {
                    case UP, DOWN -> center.getRelative(a, 0, b);
                    case NORTH, SOUTH -> center.getRelative(a, b, 0);
                    default -> center.getRelative(0, a, b);
                };
                Material t = blk.getType();
                if (t.isAir() || blk.isLiquid()) continue;
                float h = t.getHardness();
                if (h < 0 || h > hardness + 3) continue; // не ломаем обсидиан вокруг камня и т.п.
                if (!blk.isPreferredTool(tool)) continue;
                if (blk.getState(false) instanceof TileState) continue; // сундуки, печки, спавнеры — не трогаем
                out.add(blk);
            }
        }
        return out;
    }

    private static boolean isLog(Material m) {
        return Tag.LOGS.isTagged(m) && !m.name().startsWith("STRIPPED_");
    }

    private static boolean isLeaves(Material m) {
        return Tag.LEAVES.isTagged(m) || m == Material.NETHER_WART_BLOCK || m == Material.WARPED_WART_BLOCK
                || m == Material.SHROOMLIGHT;
    }

    /** Все брёвна дерева над срубленным. Если рядом нет листвы — это постройка, не трогаем. */
    private List<Block> tree(Block start) {
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        List<Block> logs = new ArrayList<>();
        queue.add(start);
        seen.add(start);
        boolean leaves = false;
        int baseY = start.getY();
        while (!queue.isEmpty() && logs.size() < TIMBER_LIMIT) {
            Block b = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (n.getY() < baseY || !seen.add(n)) continue;
                        Material t = n.getType();
                        if (isLeaves(t)) {
                            leaves = true;
                        } else if (isLog(t)) {
                            logs.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
        }
        if (!leaves) return List.of();
        logs.sort(Comparator.comparingInt(Block::getY));
        return logs;
    }

    // --- автоплавка и магнит ---

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDropSmelt(BlockDropItemEvent event) {
        Player p = event.getPlayer();
        if (!items().has(p.getInventory().getItemInMainHand(), Ench.SMELT)) return;
        boolean any = false;
        for (Item drop : event.getItems()) {
            ItemStack s = drop.getItemStack();
            Material to = SMELT.get(s.getType());
            if (to == null) continue;
            drop.setItemStack(new ItemStack(to, s.getAmount()));
            any = true;
        }
        if (any) {
            Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
            at.getWorld().spawnParticle(Particle.FLAME, at, 6, 0.25, 0.25, 0.25, 0.01);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropMagnet(BlockDropItemEvent event) {
        Player p = event.getPlayer();
        if (!items().has(p.getInventory().getItemInMainHand(), Ench.MAGNET)) return;
        pull(p, event.getItems().iterator());
    }

    private static void pull(Player p, Iterator<Item> drops) {
        PlayerInventory inv = p.getInventory();
        while (drops.hasNext()) {
            Item drop = drops.next();
            Map<Integer, ItemStack> left = inv.addItem(drop.getItemStack());
            if (left.isEmpty()) {
                drops.remove();
            } else {
                drop.setItemStack(left.values().iterator().next());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null || !items().has(killer.getInventory().getItemInMainHand(), Ench.MAGNET)) return;
        List<ItemStack> drops = event.getDrops();
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack s = it.next();
            Map<Integer, ItemStack> left = killer.getInventory().addItem(s);
            if (left.isEmpty()) it.remove();
            else s.setAmount(left.values().iterator().next().getAmount());
        }
        if (event.getDroppedExp() > 0) {
            killer.giveExp(event.getDroppedExp());
            event.setDroppedExp(0);
        }
    }

    // --- талисман «девять жизней» ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        if (event.getKeepInventory()) return;
        PlayerInventory inv = p.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (items().isTalisman(inv.getItem(i))) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return;
        ItemStack t = inv.getItem(slot);
        if (t.getAmount() > 1) t.setAmount(t.getAmount() - 1);
        else inv.setItem(slot, null);
        // талисман сгорел — всё остальное остаётся с игроком
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        plugin.messages().send(p, "astra.talisman.used");
        Fx.sound(p.getLocation(), "minecraft:item.totem.use", 0.8f, 1.2f);
    }

    /** Вещи за астры нельзя пускать на крафт (иначе чары или талисман пропадут). */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack s : event.getInventory().getMatrix()) {
            if (plugin.guard().isAstra(s)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
