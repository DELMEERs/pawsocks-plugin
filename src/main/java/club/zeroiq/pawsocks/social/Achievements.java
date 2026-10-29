package club.zeroiq.pawsocks.social;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.PlayerData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Свои достижения сервера — отдельная вкладка «pawsocks» в обычном меню достижений (L).
 * Загружаются в сервер при запуске, выдаются плагином. Как и ванильные, объявляются в чате
 * и (через tgbridge) в телеграме.
 */
public final class Achievements implements Listener {

    private record Def(String id, String parent, Material icon, String frame) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("root", null, Material.PINK_WOOL, "task"),
            new Def("socks", "root", Material.LEATHER_BOOTS, "task"),
            new Def("socks_wear", "socks", Material.LEATHER_BOOTS, "task"),
            new Def("socks_rainbow", "socks_wear", Material.MAGENTA_WOOL, "goal"),
            new Def("cozy", "root", Material.CAMPFIRE, "task"),
            new Def("pet", "root", Material.STRING, "task"),
            new Def("pet_100", "pet", Material.COD, "goal"),
            new Def("carrier", "pet", Material.LEAD, "task"),
            new Def("hug", "root", Material.PINK_DYE, "task"),
            new Def("hug_25", "hug", Material.PINK_BED, "goal"),
            new Def("emote", "root", Material.NOTE_BLOCK, "task"),
            new Def("boop", "emote", Material.FEATHER, "task"),
            new Def("friend", "root", Material.CAKE, "task"),
            new Def("couple", "friend", Material.POPPY, "goal"),
            new Def("mentor", "friend", Material.WRITABLE_BOOK, "challenge"),
            new Def("trail", "root", Material.BONE, "task"),
            new Def("home", "root", Material.RED_BED, "task"),
            new Def("sell", "root", Material.GOLD_NUGGET, "task"),
            new Def("shop", "sell", Material.EMERALD, "task"),
            new Def("ah_buy", "sell", Material.GOLD_INGOT, "task"),
            new Def("ah_sell", "sell", Material.CHEST, "task"),
            new Def("order", "sell", Material.PAPER, "task"),
            new Def("smith", "sell", Material.ANVIL, "task"),
            new Def("rich", "sell", Material.GOLD_BLOCK, "goal"),
            new Def("cosmetic", "sell", Material.AMETHYST_CLUSTER, "task"),
            new Def("astra", "root", Material.AMETHYST_SHARD, "task"),
            new Def("astra_shop", "astra", Material.NETHER_STAR, "goal"),
            new Def("daily", "astra", Material.CLOCK, "goal"),
            new Def("veteran", "root", Material.CAKE, "challenge")
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final PawsocksPlugin plugin;
    private BukkitTask task;
    private boolean loaded;

    public Achievements(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("achievements.enabled", true);
    }

    private NamespacedKey key(String id) {
        return new NamespacedKey(plugin, id);
    }

    // --- загрузка в сервер ---

    @SuppressWarnings("deprecation")
    public void load() {
        if (!enabled()) return;
        int added = 0;
        int changed = 0;
        for (Def d : DEFS) {
            NamespacedKey key = key(d.id());
            String json = json(d);
            try {
                if (Bukkit.getAdvancement(key) == null) {
                    if (Bukkit.getUnsafe().loadAdvancement(key, json) != null) added++;
                } else if (rewrite(d.id(), json)) {
                    changed++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("достижение " + d.id() + " не загрузилось: " + e.getMessage());
            }
        }
        loaded = true;
        plugin.getLogger().info("достижения: " + DEFS.size() + " (новых " + added + ")"
                + (changed > 0 ? ", изменённые тексты (" + changed + ") появятся после перезапуска" : ""));
    }

    /** Достижение уже есть в датапаке сервера — обновить файл, если текст поменялся. */
    private boolean rewrite(String id, String json) {
        File worlds = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getWorldFolder();
        if (worlds == null) return false;
        for (String dir : List.of("advancement", "advancements")) {
            File f = new File(worlds, "datapacks/bukkit/data/" + plugin.getName().toLowerCase(Locale.ROOT) + "/" + dir + "/" + id + ".json");
            if (!f.isFile()) continue;
            try {
                String old = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                if (old.trim().equals(json.trim())) return false;
                Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private String json(Def d) {
        JsonObject root = new JsonObject();
        if (d.parent() != null) root.addProperty("parent", key(d.parent()).toString());

        JsonObject display = new JsonObject();
        JsonObject icon = new JsonObject();
        icon.addProperty("id", d.icon().getKey().toString());
        icon.addProperty("count", 1);
        display.add("icon", icon);
        display.add("title", component(plugin.messages().get("achievements." + d.id() + ".title")));
        display.add("description", component(plugin.messages().get("achievements." + d.id() + ".description")));
        display.addProperty("frame", d.frame());
        boolean isRoot = d.parent() == null;
        display.addProperty("show_toast", !isRoot);
        display.addProperty("announce_to_chat", !isRoot);
        display.addProperty("hidden", false);
        if (isRoot) display.addProperty("background", "minecraft:block/pink_wool");
        root.add("display", display);

        JsonObject criteria = new JsonObject();
        JsonObject done = new JsonObject();
        done.addProperty("trigger", "minecraft:impossible");
        criteria.add("done", done);
        root.add("criteria", criteria);
        JsonArray req = new JsonArray();
        JsonArray inner = new JsonArray();
        inner.add("done");
        req.add(inner);
        root.add("requirements", req);
        return GSON.toJson(root);
    }

    private static com.google.gson.JsonElement component(Component c) {
        return GsonComponentSerializer.gson().serializeToTree(c);
    }

    // --- выдача ---

    public void grant(Player p, String id) {
        if (!loaded || !enabled() || p == null || !p.isOnline()) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> grant(p, id));
            return;
        }
        if (!"root".equals(id)) grant(p, "root");
        Advancement adv = Bukkit.getAdvancement(key(id));
        if (adv == null) return;
        AdvancementProgress progress = p.getAdvancementProgress(adv);
        if (!progress.isDone()) progress.awardCriteria("done");
    }

    public boolean has(Player p, String id) {
        Advancement adv = Bukkit.getAdvancement(key(id));
        return adv != null && p.getAdvancementProgress(adv).isDone();
    }

    /** Счётчик для достижений вида «сделай N раз». */
    public int count(Player p, String stat) {
        return plugin.perks().add(p.getUniqueId(), "stat:" + stat, 1);
    }

    // --- проверки по таймеру: то, что копится постепенно ---

    public void start() {
        stop();
        if (!enabled()) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L * 20, 20L * 30);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void check() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                checkOne(p);
            } catch (Exception e) {
                plugin.getLogger().warning("достижения: " + e.getMessage());
            }
        }
    }

    private void checkOne(Player p) {
        if (PlayerData.cozy(p) >= 100) grant(p, "cozy");
        if (plugin.socks().wearsSocks(p)) grant(p, "socks_wear");
        if (PlayerData.trail(p) != null) grant(p, "trail");
        int hugs = PlayerData.hugs(p);
        if (hugs >= 1) grant(p, "hug");
        if (hugs >= plugin.getConfig().getInt("achievements.hugs", 25)) grant(p, "hug_25");
        if (plugin.economy().balance(p.getUniqueId()) >= plugin.getConfig().getLong("achievements.rich-lapki", 1000) * 100L) {
            grant(p, "rich");
        }
        long hours = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 3600L;
        if (hours >= plugin.getConfig().getInt("achievements.veteran-hours", 50)) grant(p, "veteran");
        if (!has(p, "daily") && plugin.astras().claimedDays(p.getUniqueId()) >= plugin.getConfig().getInt("achievements.daily-days", 7)) {
            grant(p, "daily");
        }
        if (!has(p, "mentor") && plugin.mentors().rewardedCount(p.getUniqueId()) > 0) grant(p, "mentor");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> grant(p, "root"), 40L);
    }

    /** Носки: первые и коллекция цветов. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack result = event.getInventory().getResult();
        if (!plugin.socks().isSock(result)) return;
        grant(p, "socks");
        if (result.getItemMeta() instanceof LeatherArmorMeta meta) {
            for (DyeColor c : DyeColor.values()) {
                if (c.getColor().equals(meta.getColor())) {
                    plugin.perks().mark(p.getUniqueId(), "sock_color:" + c.name().toLowerCase(Locale.ROOT));
                    break;
                }
            }
            if (plugin.perks().countPrefix(p.getUniqueId(), "sock_color:") >= plugin.getConfig().getInt("achievements.sock-colors", 8)) {
                grant(p, "socks_rainbow");
            }
        }
    }

    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Def d : DEFS) out.add(d.id());
        return out;
    }
}
