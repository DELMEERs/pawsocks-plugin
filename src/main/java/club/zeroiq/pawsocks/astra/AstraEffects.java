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
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.EntityType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Что делают вещи из магазина астр. */
public final class AstraEffects implements Listener {

    private static final int TIMBER_LIMIT = 192;

    private final PawsocksPlugin plugin;
    /** Игроки, за которых сейчас ломаем «лишние» блоки — чтобы бур не запускал сам себя. */
    private final Set<UUID> busy = new HashSet<>();

    public AstraEffects(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private AstraItems items() {
        return plugin.astraItems();
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

    // --- кирка для спавнеров ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) return;
        Player p = event.getPlayer();
        if (!usable(p) || !items().has(p.getInventory().getItemInMainHand(), Ench.SPAWNER)) return;
        EntityType type = block.getState(false) instanceof CreatureSpawner cs ? cs.getSpawnedType() : null;
        event.setExpToDrop(0); // опыт за спавнер — только если он разбит, а не забран
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        Bukkit.getScheduler().runTask(plugin, () -> {
            at.getWorld().dropItemNaturally(at, items().spawner(type));
            at.getWorld().spawnParticle(Particle.END_ROD, at, 20, 0.3, 0.3, 0.3, 0.05);
            Fx.sound(at, "minecraft:block.amethyst_block.resonate", 1f, 1.3f);
        });
    }

    /** Поставленный спавнер получает моба из предмета (игре мы это не доверяем — у неё только для операторов). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.SPAWNER) return;
        EntityType type = items().spawnerType(event.getItemInHand());
        if (type == null) return;
        if (event.getBlockPlaced().getState(false) instanceof CreatureSpawner cs) {
            cs.setSpawnedType(type);
            cs.update(true, false);
        }
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
