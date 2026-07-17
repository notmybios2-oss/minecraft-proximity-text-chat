package fr.mybios.onevs100.proxchat.bubble;

import java.util.UUID;

/**
 * Cross-thread-readable position sample, published by each player's heartbeat on their OWNING
 * region thread and read by every speaker's admission pass. Plain immutable data — the whole
 * point is that admission math never reads a live entity from a foreign region (the
 * known-issues landmine family; WorldWatcher poll precedent).
 */
public record PlayerSnapshot(UUID worldId, double x, double y, double z) {

    public double distanceSquaredTo(PlayerSnapshot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
