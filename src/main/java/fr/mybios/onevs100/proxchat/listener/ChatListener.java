package fr.mybios.onevs100.proxchat.listener;

import fr.mybios.onevs100.proxchat.ModeMachine;
import fr.mybios.onevs100.proxchat.api.Mode;
import fr.mybios.onevs100.proxchat.ProxChatConfig;
import fr.mybios.onevs100.proxchat.bubble.BubbleService;
import fr.mybios.onevs100.proxchat.rate.RateGuard;
import fr.mybios.onevs100.proxchat.text.MessageSanitizer;
import fr.mybios.onevs100.proxchat.text.SanitizeResult;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The chat seam. A host plugin locking chat down may cancel EVERY AsyncChatEvent at LOWEST —
 * the vanilla broadcast is then dead before we run. We listen at HIGH with
 * {@code ignoreCancelled = false}, READ the cancelled event, and NEVER un-cancel: the broadcast
 * stays dead no matter what this plugin does or fails to do (fail-closed anonymity). Standalone
 * (no host plugin) we cancel ourselves whenever we intercept — under a lockdown that cancel is
 * an idempotent no-op on an already-cancelled event.
 *
 * Handler thread: async (network chat). Nothing here reads a live entity: text extraction and
 * the sanitizer are pure, the reject line rides the thread-safe adventure audience, the rate
 * guard is CAS-based, and rendering hops to the speaker's region thread. The mode is re-checked
 * inside the hop so a flip to OFF/SUPPRESSED mid-flight drops the bubble (fail-closed).
 */
public final class ChatListener implements Listener {

    /** The overlong-reject line — the plugin's ONLY player-facing string, French by deployment
     *  rule. The number tracks max-message-length so a config retune never makes it lie. */
    private static final String REJECT_FR_PREFIX = "Pas de messages au dessus de ";
    private static final String REJECT_FR_SUFFIX = " caractères";

    private final Plugin plugin;
    private final ModeMachine modes;
    private final BubbleService bubbles;
    private final RateGuard rateGuard;
    private final Supplier<ProxChatConfig> config;

    public ChatListener(Plugin plugin, ModeMachine modes, BubbleService bubbles,
                        RateGuard rateGuard, Supplier<ProxChatConfig> config) {
        this.plugin = plugin;
        this.modes = modes;
        this.bubbles = bubbles;
        this.rateGuard = rateGuard;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Mode mode = modes.current();
        if (!mode.intercepts()) {
            return; // OFF: untouched (a host lockdown keeps the broadcast dead regardless)
        }
        event.setCancelled(true); // never the reverse — see class javadoc
        if (!mode.renders()) {
            return; // SUPPRESSED: swallow — no bubble, no feedback, no presence leak
        }
        Player speaker = event.getPlayer();
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        ProxChatConfig cfg = config.get();
        SanitizeResult result = MessageSanitizer.sanitize(plain, cfg.maxMessageLength());
        switch (result.verdict()) {
            case EMPTY -> { /* nothing renderable — silent */ }
            case TOO_LONG -> speaker.sendMessage(Component.text(
                    REJECT_FR_PREFIX + cfg.maxMessageLength() + REJECT_FR_SUFFIX, NamedTextColor.RED));
            case OK -> {
                if (!rateGuard.tryAcquire(speaker.getUniqueId())) {
                    return; // Q10: silent drop
                }
                speaker.getScheduler().run(plugin, task -> {
                    if (modes.current().renders()) {
                        bubbles.publish(speaker, result.text());
                    }
                }, null); // refusal = speaker gone before the hop landed: nothing to render
            }
        }
    }
}
