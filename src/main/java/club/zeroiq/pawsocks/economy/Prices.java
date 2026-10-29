package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.data.Database;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Цены скупки из prices.yml и состояние рынка (насыщение) из базы.
 * Читать цены можно с любого потока (нужно для цены при наведении), менять — только с основного.
 */
public final class Prices {

    private record Pressure(double value, long updated) {
    }

    private final JavaPlugin plugin;
    private final Database db;
    private volatile Map<Material, Long> base = new EnumMap<>(Material.class);
    private final Map<Material, Pressure> pressure = new ConcurrentHashMap<>();

    private volatile double saturation;
    private volatile double floor;
    private volatile long halfLife;

    public Prices(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("prices.yml")) {
            if (in != null) {
                // новые предметы из обновлений плагина подхватываются, правки на сервере сохраняются
                yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("не удалось прочитать встроенный prices.yml: " + e.getMessage());
        }

        Map<Material, Long> next = new EnumMap<>(Material.class);
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
                next.put(material, cents);
            }
        }
        base = next;

        saturation = plugin.getConfig().getDouble("economy.market.saturation", 300);
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
        plugin.getLogger().info("цены: " + next.size() + " предметов");
    }

    /** Базовая цена за штуку в сотых, 0 — сервер не покупает. */
    public long base(Material material) {
        return base.getOrDefault(material, 0L);
    }

    /** Покупает ли сервер эту вещь: есть цена и это обычный предмет без имени, чар и т.п. */
    public boolean sellable(ItemStack item) {
        if (item == null || item.getType().isAir() || base(item.getType()) <= 0) return false;
        return item.isSimilar(ItemStack.of(item.getType()));
    }

    private double currentPressure(Material m, long now) {
        Pressure p = pressure.get(m);
        if (p == null) return 0;
        return MarketMath.decay(p.value(), now - p.updated(), halfLife);
    }

    /** Сколько сервер даст за amount штук прямо сейчас (в сотых). Ничего не меняет. */
    public long quote(Material material, int amount) {
        long unit = base(material);
        if (unit <= 0 || amount <= 0) return 0;
        double value = unit / 100.0 * amount;
        double paid = MarketMath.payout(currentPressure(material, System.currentTimeMillis()), value, saturation, floor);
        return (long) Math.floor(paid * 100 + 1e-6);
    }

    /** Текущий множитель цены (для подсказки «рынок насыщен»). */
    public double multiplier(Material material) {
        return MarketMath.multiplier(currentPressure(material, System.currentTimeMillis()), saturation, floor);
    }

    /** Продажа: считает выплату и повышает давление. Только с основного потока. */
    public long sell(Material material, int amount) {
        long unit = base(material);
        if (unit <= 0 || amount <= 0) return 0;
        long now = System.currentTimeMillis();
        double before = currentPressure(material, now);
        double value = unit / 100.0 * amount;
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
