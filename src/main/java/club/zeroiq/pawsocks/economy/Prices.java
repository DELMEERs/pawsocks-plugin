package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Цены скупки. Откуда берётся цена предмета:
 * 1. prices.yml — явная цена (руда, урожай, добыча с мобов);
 * 2. рецепт — сумма цен ингредиентов самого дешёвого рецепта (крафт никогда не выгоден);
 * 3. всё остальное — economy.fallback-price (минимальная).
 * Плюс насыщение рынка (MarketMath). Читать можно с любого потока, менять — только с основного.
 */
public final class Prices {

    private record Pressure(double value, long updated) {
    }

    private record Ingredients(List<RecipeChoice> choices, int amount) {
    }

    private final JavaPlugin plugin;
    private final Database db;
    private final Guard guard;
    private volatile Map<Material, Long> base = new EnumMap<>(Material.class);
    private volatile Map<Material, Character> source = new EnumMap<>(Material.class);
    private final Map<Material, Pressure> pressure = new ConcurrentHashMap<>();

    private volatile double saturation;
    private volatile double floor;
    private volatile long halfLife;

    public Prices(JavaPlugin plugin, Database db, Guard guard) {
        this.plugin = plugin;
        this.db = db;
        this.guard = guard;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("prices.yml")) {
            if (in != null) {
                yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("не удалось прочитать встроенный prices.yml: " + e.getMessage());
        }

        // 1. явные цены
        Map<Material, Double> explicit = new EnumMap<>(Material.class);
        ConfigurationSection section = yml.getConfigurationSection("prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null || !material.isItem()) {
                    plugin.getLogger().warning("prices.yml: нет такого предмета " + key);
                    continue;
                }
                long cents = Money.parse(section.getString(key, ""));
                if (cents <= 0) {
                    plugin.getLogger().warning("prices.yml: неверная цена у " + key);
                    continue;
                }
                explicit.put(material, cents / 100.0);
            }
        }

        // 2. цены из рецептов: минимум по всем рецептам, повторяем, пока что-то меняется
        List<Map.Entry<Material, Ingredients>> recipes = collectRecipes();
        Map<Material, Double> value = new EnumMap<>(Material.class);
        value.putAll(explicit);
        Map<Material, Character> src = new EnumMap<>(Material.class);
        for (Material m : explicit.keySet()) src.put(m, 'p');
        for (int round = 0; round < 60; round++) {
            boolean changed = false;
            for (Map.Entry<Material, Ingredients> r : recipes) {
                Material out = r.getKey();
                if (explicit.containsKey(out)) continue;
                double cost = cost(r.getValue().choices(), value);
                if (cost <= 0) continue;
                double each = cost / r.getValue().amount();
                Double old = value.get(out);
                if (old == null || each < old - 1e-9) {
                    value.put(out, each);
                    src.put(out, 'r');
                    changed = true;
                }
            }
            if (!changed) break;
        }

        // 3. всё остальное — по минимальной цене
        double fallback = plugin.getConfig().getDouble("economy.fallback-price", 0.02);
        Map<Material, Long> next = new EnumMap<>(Material.class);
        int fromRecipes = 0, fromFallback = 0;
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir() || Guard.neverSold(m)) continue;
            Double v = value.get(m);
            if (v == null) {
                v = fallback;
                src.put(m, 'f');
                fromFallback++;
            } else if (src.get(m) == 'r') {
                fromRecipes++;
            }
            long cents = Math.max(1, Math.round(v * 100));
            next.put(m, cents);
        }
        base = next;
        source = src;

        // проверка prices.yml: явная цена дороже ингредиентов = можно разбогатеть крафтом
        int risky = 0;
        for (Map.Entry<Material, Ingredients> r : recipes) {
            Double p = explicit.get(r.getKey());
            if (p == null || r.getValue().choices().size() == 1) continue; // переплавка — нормально
            double cost = cost(r.getValue().choices(), value) / r.getValue().amount();
            if (cost > 0 && p > cost * 1.15 + 0.01) {
                risky++;
                plugin.getLogger().warning(String.format("prices.yml: %s (%.2f) дороже своего рецепта (%.2f) — выгодно крафтить и продавать",
                        r.getKey().name().toLowerCase(), p, cost));
            }
        }

        saturation = plugin.getConfig().getDouble("economy.market.saturation", 500);
        floor = plugin.getConfig().getDouble("economy.market.floor", 0.25);
        halfLife = (long) (plugin.getConfig().getDouble("economy.market.half-life-hours", 12) * 3_600_000L);

        pressure.clear();
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT material, pressure, updated FROM market");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Material m = Material.matchMaterial(rs.getString(1));
                    if (m != null) pressure.put(m, new Pressure(rs.getDouble(2), rs.getLong(3)));
                }
            }
            return null;
        });
        plugin.getLogger().info("цены: " + explicit.size() + " из prices.yml, " + fromRecipes + " из рецептов, "
                + fromFallback + " по минимальной" + (risky > 0 ? ", подозрительных: " + risky : ""));
    }

    /** Все рецепты как «результат ← ингредиенты». Особые рецепты (окраска, копирование карт) пропускаются. */
    private List<Map.Entry<Material, Ingredients>> collectRecipes() {
        List<Map.Entry<Material, Ingredients>> out = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe;
            try {
                recipe = it.next();
            } catch (RuntimeException e) {
                continue;
            }
            if (recipe instanceof Keyed k && k.getKey().getNamespace().equals(plugin.getName().toLowerCase())) continue;
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir() || result.getAmount() <= 0) continue;
            List<RecipeChoice> choices = new ArrayList<>();
            if (recipe instanceof ShapedRecipe shaped) {
                Map<Character, RecipeChoice> map = shaped.getChoiceMap();
                for (String row : shaped.getShape()) {
                    for (char ch : row.toCharArray()) {
                        RecipeChoice c = map.get(ch);
                        if (c != null) choices.add(c);
                    }
                }
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                choices.addAll(shapeless.getChoiceList());
            } else if (recipe instanceof CookingRecipe<?> cooking) {
                choices.add(cooking.getInputChoice());
            } else if (recipe instanceof StonecuttingRecipe cutting) {
                choices.add(cutting.getInputChoice());
            } else if (recipe instanceof SmithingTransformRecipe smithing) {
                choices.add(smithing.getTemplate());
                choices.add(smithing.getBase());
                choices.add(smithing.getAddition());
            } else {
                continue;
            }
            choices.removeIf(c -> c == null);
            if (choices.isEmpty()) continue;
            out.add(Map.entry(result.getType(), new Ingredients(choices, result.getAmount())));
        }
        return out;
    }

    /** Цена набора ингредиентов: для каждого — самый дешёвый подходящий предмет. -1, если что-то без цены. */
    private static double cost(List<RecipeChoice> choices, Map<Material, Double> value) {
        double sum = 0;
        for (RecipeChoice choice : choices) {
            double best = Double.MAX_VALUE;
            for (Material m : materials(choice)) {
                Double v = value.get(m);
                if (v != null && v < best) best = v;
            }
            if (best == Double.MAX_VALUE) return -1;
            sum += best;
        }
        return sum;
    }

    private static List<Material> materials(RecipeChoice choice) {
        List<Material> out = new ArrayList<>();
        if (choice instanceof RecipeChoice.MaterialChoice mc) {
            out.addAll(mc.getChoices());
        } else if (choice instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack s : ec.getChoices()) out.add(s.getType());
        }
        return out;
    }

    // --- цены предметов ---

    /** Базовая цена за штуку в сотых, 0 — сервер не покупает. */
    public long base(Material material) {
        return base.getOrDefault(material, 0L);
    }

    /** Откуда цена: p — prices.yml, r — рецепт, f — минимальная. */
    public char source(Material material) {
        return source.getOrDefault(material, '?');
    }

    /** Цена одной штуки этого предмета с учётом поломки (чары не учитываются). */
    public long unit(ItemStack item) {
        long b = base(item.getType());
        if (b <= 0) return 0;
        ItemMeta meta = item.getItemMeta();
        int max = item.getType().getMaxDurability();
        if (meta instanceof Damageable d && d.hasMaxDamage()) max = d.getMaxDamage();
        if (max > 0 && meta instanceof Damageable d && d.hasDamage()) {
            double left = Math.max(0, 1 - (double) d.getDamage() / max);
            b = (long) Math.floor(b * left);
        }
        return Math.max(b > 0 ? 1 : 0, b);
    }

    /** Покупает ли сервер эту вещь. */
    public boolean sellable(ItemStack item) {
        if (guard.blockedForSell(item)) return false;
        return unit(item) > 0;
    }

    /** Снаряжение и особые вещи, которые /sell all не трогает. */
    public static boolean gear(ItemStack item) {
        if (item.getType().getMaxDurability() > 0) return true;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasEnchants() || meta.hasDisplayName()
                || meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta;
    }

    private double currentPressure(Material m, long now) {
        Pressure p = pressure.get(m);
        if (p == null) return 0;
        return MarketMath.decay(p.value(), now - p.updated(), halfLife);
    }

    /** Сколько сервер даст за эту стопку прямо сейчас (в сотых). Ничего не меняет. */
    public long quote(ItemStack item) {
        return quote(item.getType(), unit(item), item.getAmount());
    }

    public long quote(Material material, long unit, int amount) {
        if (unit <= 0 || amount <= 0) return 0;
        double value = unit / 100.0 * amount;
        double paid = MarketMath.payout(currentPressure(material, System.currentTimeMillis()), value, saturation, floor);
        return (long) Math.floor(paid * 100 + 1e-6);
    }

    public double multiplier(Material material) {
        return MarketMath.multiplier(currentPressure(material, System.currentTimeMillis()), saturation, floor);
    }

    /** Продажа партии одного типа на общую стоимость valueCents: выплата + рост давления. Только с основного потока. */
    public long sell(Material material, long valueCents) {
        if (valueCents <= 0) return 0;
        long now = System.currentTimeMillis();
        double before = currentPressure(material, now);
        double value = valueCents / 100.0;
        long paid = (long) Math.floor(MarketMath.payout(before, value, saturation, floor) * 100 + 1e-6);
        double after = before + value;
        pressure.put(material, new Pressure(after, now));
        db.read(c -> {
            try (PreparedStatement ps = Database.prepare(c,
                    "INSERT INTO market (material, pressure, updated) VALUES (?, ?, ?) " +
                            "ON CONFLICT(material) DO UPDATE SET pressure = excluded.pressure, updated = excluded.updated",
                    material.name(), after, now)) {
                ps.executeUpdate();
            }
            return null;
        });
        return paid;
    }
}
