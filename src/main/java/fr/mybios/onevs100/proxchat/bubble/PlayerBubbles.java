package fr.mybios.onevs100.proxchat.bubble;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-speaker bubble state. THREAD-CONFINED: every read and write happens on the speaker's
 * region thread (publish, heartbeat, event handlers all ride the speaker's entity scheduler),
 * which is why plain collections are correct here.
 *
 * Admission invariant that makes delta fan-out sound: every UUID in {@code admitted} has been
 * shown EVERY live display in {@code stack}. It holds because displays only enter via publish
 * (which shows the new display to all admitted viewers) and viewers only enter admitted via a
 * whole-stack show; despawned displays need no hide (server-side removal broadcasts to every
 * tracking client), and the sneak hide empties {@code admitted} entirely.
 */
final class PlayerBubbles {

    final BubbleStack<Bubble> stack = new BubbleStack<>();

    /** Viewers currently shown this speaker's bubbles (see class invariant). */
    final Set<UUID> admitted = new HashSet<>();

    /** Q1: while true, desired admission is the empty set; lifetime keeps running. */
    boolean sneakHidden;
}
