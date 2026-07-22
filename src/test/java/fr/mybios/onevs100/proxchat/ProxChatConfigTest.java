package fr.mybios.onevs100.proxchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Config parsing against in-memory sections (MemoryConfiguration is pure Bukkit API — no server,
 * no YAML parser needed; the YAML file itself is deserialized by Bukkit's own loader at runtime).
 */
class ProxChatConfigTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void emptyConfigYieldsDefaultsWithAWarning() {
        ProxChatConfig cfg = ProxChatConfig.from(new MemoryConfiguration(), warnings::add);
        assertEquals(ProxChatConfig.DEFAULTS, cfg);
        assertEquals(1, warnings.size()); // missing section is worth telling the console about
    }

    @Test
    void defaultsMatchTheApprovedSpec() {
        // The approved spec values; height-above-head is the in-game-tuned ride-anchor 1.2.
        ProxChatConfig d = ProxChatConfig.DEFAULTS;
        assertEquals(24.0, d.radiusBlocks());
        assertEquals(8, d.lifetimeSeconds());
        assertEquals(3, d.maxPerPlayer());
        assertEquals(1.2, d.heightAboveHead());
        assertEquals(0.30, d.stackSpacing());
        assertTrue(d.hideOnSneak());
        assertEquals(96, d.maxMessageLength());
        assertEquals(200, d.lineWidth());
        assertEquals(0.5f, d.viewRange());
        assertEquals(750, d.minMessageIntervalMs());
        // Conversation log ships OFF, keep-forever, admits recorded.
        assertFalse(d.conversationLogEnabled());
        assertEquals(0, d.conversationRetentionDays());
        assertTrue(d.conversationLogAdmits());
    }

    @Test
    void explicitValuesParse() {
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", 32.0);
        s.set("lifetime-seconds", 10);
        s.set("max-per-player", 2);
        s.set("height-above-head", 1.4);
        s.set("stack-spacing", 0.25);
        s.set("hide-on-sneak", false);
        s.set("max-message-length", 120);
        s.set("line-width", 180);
        s.set("view-range", 0.6);
        s.set("min-message-interval-ms", 1000);
        var log = root.createSection("conversation-log");
        log.set("enabled", true);
        log.set("retention-days", 45);
        log.set("log-admits", false);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(new ProxChatConfig(32.0, 10, 2, 1.4, 0.25, false, 120, 180, 0.6f, 1000,
                true, 45, false), cfg);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingConversationLogSectionSplicesDefaultsSilently() {
        // A deployed config predating the feature must parse warning-free with the log OFF.
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles");
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertFalse(cfg.conversationLogEnabled());
        assertEquals(0, cfg.conversationRetentionDays());
        assertTrue(cfg.conversationLogAdmits());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void negativeRetentionClampsToKeepForever() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles");
        root.createSection("conversation-log").set("retention-days", -7);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(0, cfg.conversationRetentionDays()); // 0 = never prune
        assertEquals(1, warnings.size());
    }

    @Test
    void missingKeysFallBackPerKey() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles").set("radius-blocks", 48.0);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(48.0, cfg.radiusBlocks());
        assertEquals(8, cfg.lifetimeSeconds()); // untouched keys keep their defaults
        assertTrue(warnings.isEmpty());
    }

    @Test
    void outOfRangeValuesClampWithWarnings() {
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", -5.0);          // typo must not brick the enable mid-event
        s.set("lifetime-seconds", 100000);
        s.set("max-per-player", 0);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(1.0, cfg.radiusBlocks());
        assertEquals(120, cfg.lifetimeSeconds());
        assertEquals(1, cfg.maxPerPlayer());
        assertEquals(3, warnings.size());
        assertFalse(warnings.get(0).isEmpty());
    }

    @Test
    void derivedValuesAreConsistent() {
        ProxChatConfig d = ProxChatConfig.DEFAULTS;
        assertEquals(576.0, d.radiusSquared());
        assertEquals(8_000_000_000L, d.lifetimeNanos());
        assertEquals(160L, d.lifetimeTicks());
    }
}
