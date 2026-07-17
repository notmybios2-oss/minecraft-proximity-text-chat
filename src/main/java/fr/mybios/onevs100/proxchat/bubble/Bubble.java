package fr.mybios.onevs100.proxchat.bubble;

import java.util.UUID;
import org.bukkit.entity.TextDisplay;

/**
 * One live speech bubble: the display handle plus the plain data the reconciler compares
 * without touching the entity (spawn world for the Q3 world-switch clear, monotonic expiry
 * deadline). Owned by the speaker's region thread like the stack that holds it.
 */
public record Bubble(TextDisplay display, UUID worldId, long expiresAtNanos) {
}
