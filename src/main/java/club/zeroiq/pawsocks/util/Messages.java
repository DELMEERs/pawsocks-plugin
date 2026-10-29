package club.zeroiq.pawsocks.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Тексты из messages.yml. Чего нет в файле на сервере — берётся из встроенного файла. */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private YamlConfiguration config = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("не удалось прочитать встроенный messages.yml: " + e.getMessage());
        }
    }

    public String raw(String key) {
        return config.getString(key, key);
    }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    /** Название предмета: без курсива, который майнкрафт ставит по умолчанию. */
    public Component item(String key, TagResolver... resolvers) {
        return get(key, resolvers).decoration(TextDecoration.ITALIC, false);
    }

    public List<Component> lore(String key, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>();
        for (String line : config.getStringList(key)) {
            out.add(MM.deserialize(line, resolvers).decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        String text = raw(key);
        if (text.isEmpty()) return;
        to.sendMessage(MM.deserialize(text, resolvers));
    }

    public void actionBar(Audience to, String key, TagResolver... resolvers) {
        String text = raw(key);
        if (text.isEmpty()) return;
        to.sendActionBar(MM.deserialize(text, resolvers));
    }
}
