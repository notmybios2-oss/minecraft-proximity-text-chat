package fr.mybios.onevs100.proxchat;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable view of config.yml (pc-001 §3.7 keys; height default is the owner-tuned pc-005
 * value). Parsed once at enable — slice 3 adds /proxchat reload, which re-parses and swaps the
 * whole record (readers hold a Supplier, never a stale field). Out-of-range values are clamped
 * with an English warning rather than refused: a typo in a deployed config must never keep the
 * plugin from enabling mid-event.
 */
public record ProxChatConfig(
        double radiusBlocks,
        int lifetimeSeconds,
        int maxPerPlayer,
        double heightAboveHead,
        double stackSpacing,
        boolean hideOnSneak,
        int maxMessageLength,
        int lineWidth,
        float viewRange,
        long minMessageIntervalMs,
        boolean conversationLogEnabled,
        int conversationRetentionDays,
        boolean conversationLogAdmits) {

    public static final ProxChatConfig DEFAULTS = new ProxChatConfig(
            24.0, 8, 3, 1.2, 0.30, true, 96, 200, 0.5f, 750,
            // Conversation log ships OFF, keep-forever (retention 0 = never prune — owner
            // ruling: footage editing can happen up to a year later), admits recorded.
            false, 0, true);

    public static ProxChatConfig from(ConfigurationSection root, Consumer<String> warn) {
        ConfigurationSection s = root.getConfigurationSection("bubbles");
        if (s == null) {
            warn.accept("missing 'bubbles' section - using defaults");
            return DEFAULTS;
        }
        ProxChatConfig d = DEFAULTS;
        // Missing section = all defaults: the splice mechanism for deployments predating the key.
        ConfigurationSection log = root.getConfigurationSection("conversation-log");
        return new ProxChatConfig(
                clamp(s.getDouble("radius-blocks", d.radiusBlocks), 1.0, 128.0, "radius-blocks", warn),
                (int) clamp(s.getInt("lifetime-seconds", d.lifetimeSeconds), 1, 120, "lifetime-seconds", warn),
                (int) clamp(s.getInt("max-per-player", d.maxPerPlayer), 1, 10, "max-per-player", warn),
                clamp(s.getDouble("height-above-head", d.heightAboveHead), 0.0, 10.0, "height-above-head", warn),
                clamp(s.getDouble("stack-spacing", d.stackSpacing), 0.05, 2.0, "stack-spacing", warn),
                s.getBoolean("hide-on-sneak", d.hideOnSneak),
                (int) clamp(s.getInt("max-message-length", d.maxMessageLength), 1, 512, "max-message-length", warn),
                (int) clamp(s.getInt("line-width", d.lineWidth), 10, 1000, "line-width", warn),
                (float) clamp(s.getDouble("view-range", d.viewRange), 0.05, 2.0, "view-range", warn),
                (long) clamp(s.getLong("min-message-interval-ms", d.minMessageIntervalMs), 0, 60_000, "min-message-interval-ms", warn),
                log != null && log.getBoolean("enabled", d.conversationLogEnabled),
                log == null ? d.conversationRetentionDays
                        : (int) clamp(log.getInt("retention-days", d.conversationRetentionDays),
                                0, 3650, "conversation-log.retention-days", warn),
                log == null ? d.conversationLogAdmits
                        : log.getBoolean("log-admits", d.conversationLogAdmits));
    }

    private static double clamp(double value, double min, double max, String key, Consumer<String> warn) {
        if (value < min || value > max) {
            double clamped = Math.max(min, Math.min(max, value));
            warn.accept(key + "=" + value + " out of range [" + min + ", " + max + "] - clamped to " + clamped);
            return clamped;
        }
        return value;
    }

    public double radiusSquared() {
        return radiusBlocks * radiusBlocks;
    }

    public long lifetimeNanos() {
        return lifetimeSeconds * 1_000_000_000L;
    }

    public long lifetimeTicks() {
        return lifetimeSeconds * 20L;
    }
}
