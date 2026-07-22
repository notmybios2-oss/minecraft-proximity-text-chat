package fr.mybios.onevs100.proxchat.command;

import fr.mybios.onevs100.proxchat.ProxChat;
import fr.mybios.onevs100.proxchat.ProxChatConfig;
import fr.mybios.onevs100.proxchat.api.Mode;
import fr.mybios.onevs100.proxchat.api.ProxChatService;
import fr.mybios.onevs100.proxchat.bubble.BubbleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Op-only admin lever — English replies (admin surface): status, the three mode flips,
 * clear-all and reload. Mode flips go through the same {@link ProxChatService} controller as a
 * host plugin's drive, so leaving-ON-clears and persistence hold no matter which lever moved
 * the mode. Safe from console: flips are atomic and clears fan out via entity schedulers.
 */
public final class ProxChatCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("status", "on", "off", "suppress", "clear", "reload");

    private final ProxChat plugin;
    private final ProxChatService service;
    private final BubbleService bubbles;
    private final Supplier<ProxChatConfig> config;

    public ProxChatCommand(ProxChat plugin, ProxChatService service, BubbleService bubbles,
                           Supplier<ProxChatConfig> config) {
        this.plugin = plugin;
        this.service = service;
        this.bubbles = bubbles;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return usage(sender);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> reply(sender, "mode=" + service.mode()
                    + " " + bubbles.describe()
                    + " " + summarize(config.get()));
            case "on" -> switchMode(sender, Mode.ON);
            case "off" -> switchMode(sender, Mode.OFF);
            case "suppress" -> switchMode(sender, Mode.SUPPRESSED);
            case "clear" -> {
                service.clearAll();
                reply(sender, "clear-all dispatched to every speaker's region thread.");
            }
            case "reload" -> reload(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void switchMode(CommandSender sender, Mode next) {
        Mode previous = service.setMode(next);
        String note = previous == next ? " (no change)"
                : previous == Mode.ON ? " (live bubbles cleared)" : "";
        reply(sender, "mode " + previous + " -> " + next + note);
    }

    /**
     * Re-reads config.yml from disk in place. A one-off ~1 KB read on the sender's region thread
     * (the enable-time load runs the same way on the global thread); the swap itself is one
     * volatile write. The deployed file is never written back — see reloadConfiguration.
     */
    private void reload(CommandSender sender) {
        List<String> warnings = new ArrayList<>();
        ProxChatConfig cfg = plugin.reloadConfiguration(warnings::add);
        reply(sender, "config reloaded: " + summarize(cfg)
                + (warnings.isEmpty() ? "" : " — " + warnings.size() + " value(s) clamped:"));
        for (String w : warnings) {
            reply(sender, "  " + w);
        }
    }

    private static String summarize(ProxChatConfig cfg) {
        return "radius=" + cfg.radiusBlocks()
                + " lifetime=" + cfg.lifetimeSeconds() + "s"
                + " max-per-player=" + cfg.maxPerPlayer()
                + " max-length=" + cfg.maxMessageLength()
                + " min-interval=" + cfg.minMessageIntervalMs() + "ms";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private boolean usage(CommandSender sender) {
        reply(sender, "usage: /proxchat <status|on|off|suppress|clear|reload>");
        return true;
    }

    private void reply(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[proxchat] " + message, NamedTextColor.GRAY));
    }
}
