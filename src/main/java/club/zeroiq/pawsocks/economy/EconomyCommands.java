package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.PawsocksPlugin;
import club.zeroiq.pawsocks.util.Fx;
import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /bal, /pay, /baltop и админская /eco. */
public final class EconomyCommands implements CommandExecutor, TabCompleter, Listener {

    private final PawsocksPlugin plugin;
    private final Map<UUID, Long> payCooldown = new HashMap<>();

    public EconomyCommands(PawsocksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Economy eco() {
        return plugin.economy();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        eco().touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> top(sender);
            case "eco" -> admin(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void balance(CommandSender sender, String[] args) {
        if (args.length >= 1 && sender.hasPermission("pawsocks.balance.others")) {
            Economy.Account a = eco().find(args[0]);
            if (a == null) {
                m().send(sender, "eco.no-account");
                return;
            }
            m().send(sender, "eco.balance-other", Placeholder.unparsed("name", a.name()), Money.tag(m(), "balance", a.balance()));
            return;
        }
        if (!(sender instanceof Player player)) {
            m().send(sender, "players-only");
            return;
        }
        m().send(player, "eco.balance", Money.tag(m(), "balance", eco().balance(player.getUniqueId())));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            m().send(sender, "players-only");
            return;
        }
        if (args.length < 2) {
            m().send(player, "eco.pay-usage");
            return;
        }
        Economy.Account to = eco().find(args[0]);
        if (to == null) {
            m().send(player, "eco.no-account");
            return;
        }
        if (to.uuid().equals(player.getUniqueId())) {
            m().send(player, "eco.pay-self");
            return;
        }
        long amount = Money.parse(args[1]);
        if (amount <= 0) {
            m().send(player, "eco.bad-amount");
            return;
        }
        // защита от спама переводами
        long now = System.currentTimeMillis();
        Long last = payCooldown.get(player.getUniqueId());
        if (last != null && now - last < 1000) return;
        payCooldown.put(player.getUniqueId(), now);

        if (!eco().transfer(player.getUniqueId(), to.uuid(), amount, "pay", null)) {
            m().send(player, "eco.not-enough", Money.tag(m(), "balance", eco().balance(player.getUniqueId())));
            return;
        }
        m().send(player, "eco.paid", Placeholder.unparsed("name", to.name()), Money.tag(m(), "money", amount));
        Player target = Bukkit.getPlayer(to.uuid());
        if (target != null) {
            m().send(target, "eco.received", Placeholder.unparsed("name", player.getName()), Money.tag(m(), "money", amount));
            Fx.sound(target.getLocation(), "minecraft:entity.experience_orb.pickup", 0.6f, 1.3f);
        }
    }

    private void top(CommandSender sender) {
        List<Economy.Account> top = eco().top(10);
        m().send(sender, "eco.top-header");
        int i = 1;
        for (Economy.Account a : top) {
            m().send(sender, "eco.top-line",
                    Placeholder.unparsed("place", String.valueOf(i++)),
                    Placeholder.unparsed("name", a.name()),
                    Money.tag(m(), "balance", a.balance()));
        }
        if (top.isEmpty()) m().send(sender, "eco.top-empty");
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pawsocks.eco.admin")) {
            m().send(sender, "no-permission");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
            m().send(sender, "eco.info", Money.tag(m(), "supply", eco().supply()));
            return;
        }
        if (args.length < 3) {
            m().send(sender, "eco.admin-usage");
            return;
        }
        Economy.Account a = eco().find(args[1]);
        if (a == null) {
            m().send(sender, "eco.no-account");
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        long amount = action.equals("set") && args[2].equals("0") ? 0 : Money.parse(args[2]);
        if (amount < 0) {
            m().send(sender, "eco.bad-amount");
            return;
        }
        String note = "by " + sender.getName();
        switch (action) {
            case "give" -> eco().deposit(a.uuid(), amount, "admin_give", note);
            case "take" -> {
                long have = eco().balance(a.uuid());
                eco().set(a.uuid(), Math.max(0, have - amount), note);
            }
            case "set" -> eco().set(a.uuid(), amount, note);
            default -> {
                m().send(sender, "eco.admin-usage");
                return;
            }
        }
        m().send(sender, "eco.admin-done", Placeholder.unparsed("name", a.name()),
                Money.tag(m(), "balance", eco().balance(a.uuid())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("eco") && args.length == 1) {
            for (String s : List.of("give", "take", "set", "info")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        boolean nameArg = (name.equals("pay") || name.equals("balance")) && args.length == 1
                || name.equals("eco") && args.length == 2;
        if (nameArg) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
