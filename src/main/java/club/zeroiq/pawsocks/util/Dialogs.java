package club.zeroiq.pawsocks.util;

import club.zeroiq.pawsocks.PawsocksPlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Окна (диалоги) из 1.21.6+: поля ввода и кнопки прямо в игре вместо длинных команд.
 * Ответ приходит в обработчик на основном потоке сервера.
 */
public final class Dialogs {

    public static final int WIDTH = 300;

    private final PawsocksPlugin plugin;

    public Dialogs(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("dialogs.enabled", true);
    }

    public static DialogInput text(String key, Component label, String initial, int maxLength) {
        return DialogInput.text(key, WIDTH, label, true, initial == null ? "" : initial, maxLength, null);
    }

    public static DialogInput number(String key, Component label, float min, float max, float initial) {
        return DialogInput.numberRange(key, WIDTH, label, "options.generic_value", min, max, initial, 1f);
    }

    public static List<DialogBody> body(List<Component> lines) {
        List<DialogBody> out = new ArrayList<>();
        for (Component line : lines) out.add(DialogBody.plainMessage(line, WIDTH));
        return out;
    }

    private DialogAction callback(BiConsumer<Player, DialogResponseView> handler) {
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(15))
                .build();
        return DialogAction.customClick((view, audience) -> {
            Player player = player(audience);
            if (player == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) handler.accept(player, view);
            });
        }, options);
    }

    private static Player player(Audience audience) {
        if (audience instanceof Player p) return p;
        UUID uuid = audience.get(Identity.UUID).orElse(null);
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    /** Окно с полями и двумя кнопками: «да» вызывает обработчик, «нет» просто закрывает. */
    public void confirm(Player player, Component title, List<Component> lines, List<DialogInput> inputs,
                        Component yes, Component no, BiConsumer<Player, DialogResponseView> onYes) {
        DialogBase base = DialogBase.builder(title)
                .body(body(lines))
                .inputs(inputs)
                .canCloseWithEscape(true)
                .build();
        ActionButton yesButton = ActionButton.create(yes, Component.empty(), 140, callback(onYes));
        ActionButton noButton = ActionButton.create(no, Component.empty(), 140, null);
        Dialog dialog = Dialog.create(b -> b.empty().base(base).type(DialogType.confirmation(yesButton, noButton)));
        player.showDialog(dialog);
    }

    /** Окно с одной кнопкой. */
    public void notice(Player player, Component title, List<Component> lines, List<DialogInput> inputs,
                       Component button, BiConsumer<Player, DialogResponseView> onClose) {
        DialogBase base = DialogBase.builder(title)
                .body(body(lines))
                .inputs(inputs)
                .canCloseWithEscape(onClose == null)
                .build();
        ActionButton ok = ActionButton.create(button, Component.empty(), 200, onClose == null ? null : callback(onClose));
        Dialog dialog = Dialog.create(b -> b.empty().base(base).type(DialogType.notice(ok)));
        player.showDialog(dialog);
    }

    /** Число из поля ввода (null — если поля нет). */
    public static Integer intValue(DialogResponseView view, String key) {
        Float f = view.getFloat(key);
        return f == null ? null : Math.round(f);
    }

    public static String textValue(DialogResponseView view, String key) {
        String s = view.getText(key);
        return s == null ? "" : s.trim();
    }
}
