package fr.mybios.onevs100.proxchat;

import fr.mybios.onevs100.proxchat.api.ProxChatService;
import fr.mybios.onevs100.proxchat.bubble.BubbleService;
import fr.mybios.onevs100.proxchat.command.ProxChatCommand;
import fr.mybios.onevs100.proxchat.listener.ChatListener;
import fr.mybios.onevs100.proxchat.listener.PlayerLifecycleListener;
import fr.mybios.onevs100.proxchat.rate.RateGuard;
import java.io.IOException;
import java.util.function.Consumer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Proximity text chat for the 1vs100 V2 event (roadmap P4, ADR 0024): chat becomes speech
 * bubbles above the speaker — radius-limited, wall-occluded, anonymous by construction (no
 * sender label exists anywhere). Slice 2 shipped the render pipeline; slice 3 adds the game
 * integration surface: the frozen {@link ProxChatService}, crash-safe mode persistence and
 * config reload.
 */
public final class ProxChat extends JavaPlugin {

    private volatile ProxChatConfig config = ProxChatConfig.DEFAULTS;
    private ModeMachine modes;
    private BubbleService bubbles;
    private ModeStore modeStore;

    @Override
    public void onEnable() {
        // Writes the bundled config.yml ONLY if absent — a deployed config is never regenerated;
        // per-key defaults in ProxChatConfig.from are the splice mechanism for later-added keys.
        saveDefaultConfig();
        this.config = ProxChatConfig.from(getConfig(), w -> getLogger().warning("config: " + w));
        this.modes = new ModeMachine();
        this.bubbles = new BubbleService(this, () -> config, () -> modes.current().renders());
        this.modeStore = new ModeStore(getDataFolder().toPath().resolve("mode.dat"));

        // Crash-safe restore BEFORE listeners/commands exist, so nothing can observe a
        // pre-restore mode. First boot / unreadable file = OFF (pc-002 Q8; loud when unreadable).
        ModeStore.Restore restore = modeStore.load();
        modes.set(restore.mode());
        switch (restore.outcome()) {
            case FIRST_BOOT, RESTORED -> getLogger().info("mode: " + restore.mode() + " (" + restore.detail() + ")");
            case UNREADABLE -> getLogger().severe("mode: OFF — " + restore.detail());
        }

        // The single transition path for BOTH levers (admin command + EventCore's drive).
        ModeController controller =
                new ModeController(modes, bubbles::clearAll, mode -> persistModeAsync());
        getServer().getServicesManager()
                .register(ProxChatService.class, controller, this, ServicePriority.Normal);

        // Suppliers, not snapshots: a config reload retunes the guard and listeners live.
        RateGuard rateGuard = new RateGuard(() -> config.minMessageIntervalMs(), System::nanoTime);

        getServer().getPluginManager().registerEvents(
                new ChatListener(this, modes, bubbles, rateGuard, () -> config), this);
        getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(bubbles, rateGuard), this);

        PluginCommand command = getCommand("proxchat");
        ProxChatCommand executor = new ProxChatCommand(this, controller, bubbles, () -> config);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // Mid-game (re)enable: players already online never fired PlayerJoinEvent for us.
        for (Player online : getServer().getOnlinePlayers()) {
            bubbles.track(online);
        }

        getLogger().info("ProxChat enabled: mode=" + modes.current()
                + " radius=" + config.radiusBlocks()
                + " lifetime=" + config.lifetimeSeconds() + "s"
                + " max-per-player=" + config.maxPerPlayer()
                + " max-length=" + config.maxMessageLength()
                + " (bubbles render only in mode ON; service registered for EventCore)");
    }

    /**
     * Re-reads config.yml and swaps the live snapshot (all consumers hold suppliers). Clamp
     * warnings go to the console log AND the sink (the admin who ran /proxchat reload). The
     * deployed file is NEVER written back — no saveConfig anywhere: regenerating a deployed
     * config wholesale is banned (house rule), and per-key defaults already splice missing keys.
     */
    public ProxChatConfig reloadConfiguration(Consumer<String> warnSink) {
        reloadConfig();
        ProxChatConfig next = ProxChatConfig.from(getConfig(), w -> {
            getLogger().warning("config: " + w);
            warnSink.accept(w);
        });
        this.config = next;
        getLogger().info("config reloaded: radius=" + next.radiusBlocks()
                + " lifetime=" + next.lifetimeSeconds() + "s"
                + " max-per-player=" + next.maxPerPlayer()
                + " max-length=" + next.maxMessageLength()
                + " min-interval=" + next.minMessageIntervalMs() + "ms");
        return next;
    }

    /**
     * Persists the mode off-thread: setMode is callable from any region thread and file I/O
     * belongs on neither a region tick nor the async-chat thread. persistCurrent re-reads the
     * live mode inside its lock, so concurrent flips converge to the latest (see ModeStore).
     */
    private void persistModeAsync() {
        getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                modeStore.persistCurrent(modes::current);
            } catch (IOException e) {
                getLogger().severe("mode persist failed (" + e.getMessage()
                        + ") — a restart would boot from the previous file state");
            }
        });
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (bubbles != null) {
            // Best-effort: schedulers may refuse during shutdown, but displays are non-persistent
            // (byte-level proven, pc-005) so nothing can reach disk either way.
            bubbles.clearAll();
        }
        getLogger().info("ProxChat disabled.");
    }
}
