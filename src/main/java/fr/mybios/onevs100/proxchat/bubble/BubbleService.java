package fr.mybios.onevs100.proxchat.bubble;

import fr.mybios.onevs100.proxchat.ProxChatConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The bubble pipeline: spawn/mount (pc-005: MOUNT is the production render path), stack shifts,
 * expiry, the 0.5 s reconciler, and server-authoritative per-viewer admission.
 *
 * THREAD MODEL (the part Folia makes load-bearing):
 * <ul>
 *   <li>All state for speaker P ({@link PlayerBubbles}) is confined to P's region thread —
 *       publish/heartbeat/clears all ride P's entity scheduler, so plain collections are safe.</li>
 *   <li>Admission math reads only {@link PlayerSnapshot} plain data (published by each player's
 *       own heartbeat) — never a live entity from a foreign region.</li>
 *   <li>showEntity/hideEntity ride the VIEWER's entity scheduler: verified against this exact
 *       Folia build's bytecode — CraftPlayer#showEntity mutates viewer-side bookkeeping (a plain
 *       HashMap) and TrackedEntity#updatePlayer silently downgrades to removePlayer when run off
 *       the viewer's thread. The entity tracker then maintains actual pairing from that
 *       bookkeeping on its own ticks (canSee), which self-heals any transient miss.</li>
 *   <li>Display REMOVAL always rides the display's own scheduler: correct from any thread, and a
 *       schedule refusal is the proven thread-safe dead signal (pc-004/pc-005).</li>
 * </ul>
 *
 * Failure containment (pc-001 §3.5): scheduled expiry is the fast path, the heartbeat reconciler
 * is the backstop, and any maintenance failure fails toward "remove this player's bubbles" —
 * never toward frozen text. Off-server nothing can linger by construction: displays are
 * non-persistent (byte-level proven in pc-005).
 */
public final class BubbleService {

    /** Identification tag for defensive sweeps/debugging (pc-001 §3.1). */
    public static final String BUBBLE_TAG = "proxchat.bubble";

    /** 0.5 s heartbeat = snapshot publish + reconcile + admission re-evaluation (pc-001 §3.3/§3.5). */
    private static final long HEARTBEAT_PERIOD_TICKS = 10L;

    private final Plugin plugin;
    private final Supplier<ProxChatConfig> config;
    /** Live mode-renders check — publish gate + heartbeat backstop (leave-ON race closure). */
    private final BooleanSupplier renders;

    private final ConcurrentHashMap<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    /** Values are thread-confined to their owner's region thread; the map itself is not. */
    private final ConcurrentHashMap<UUID, PlayerBubbles> bubbles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledTask> heartbeats = new ConcurrentHashMap<>();

    public BubbleService(Plugin plugin, Supplier<ProxChatConfig> config, BooleanSupplier renders) {
        this.plugin = plugin;
        this.config = config;
        this.renders = renders;
    }

    // ------------------------------------------------------------------ lifecycle wiring

    /** Any thread. Starts the per-player heartbeat; idempotent (join + enable sweep may overlap). */
    public void track(Player player) {
        UUID id = player.getUniqueId();
        if (heartbeats.containsKey(id)) {
            return;
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin,
                t -> heartbeat(player),
                () -> {
                    heartbeats.remove(id);
                    snapshots.remove(id);
                },
                1L, HEARTBEAT_PERIOD_TICKS);
        if (task == null) {
            return; // player already removed — quit path owns the cleanup
        }
        ScheduledTask previous = heartbeats.putIfAbsent(id, task);
        if (previous != null) {
            task.cancel();
        }
    }

    /** Quit handler — MUST run on the quitter's region thread. Despawns everything, drops traces. */
    public void untrack(Player player) {
        clearStack(player);
        UUID id = player.getUniqueId();
        snapshots.remove(id);
        bubbles.remove(id);
        ScheduledTask heartbeat = heartbeats.remove(id);
        if (heartbeat != null) {
            heartbeat.cancel();
        }
    }

    // ------------------------------------------------------------------ the pipeline

    /**
     * Renders one sanitized message — MUST run on the speaker's region thread (the chat listener
     * hops here via the speaker's entity scheduler).
     */
    public void publish(Player speaker, String text) {
        if (!speaker.isValid() || speaker.isDead() || speaker.getGameMode() == GameMode.SPECTATOR) {
            return; // no bubbles over corpses or invisible spectators
        }
        ProxChatConfig cfg = config.get();
        PlayerBubbles pb = bubbles.computeIfAbsent(speaker.getUniqueId(), id -> new PlayerBubbles());
        // Mode re-check AFTER the map insertion — the entry is the synchronization point with a
        // concurrent leave-ON clearAll (first-bubble strand race, pc-008 review): a flip landing
        // before the insert is seen by this read; a flip landing after it finds the entry in
        // keySet and queues a clearStack behind this very task on the same entity scheduler.
        // (The listener's own re-check happens before the insert, so it cannot close this.)
        if (!renders.getAsBoolean()) {
            clearStack(speaker); // belt: idempotent, same thread; the flip's fan-out may have run already
            return;
        }
        pb.sneakHidden = cfg.hideOnSneak() && speaker.isSneaking(); // Q1: typed-while-crouched spawns hidden
        // Never mount/restack against a stale stack: a display dismounted by a portal hop since
        // the last heartbeat may live in a foreign region, where direct mutation is fatal.
        reconcile(speaker, pb);

        Location at = speaker.getLocation();
        TextDisplay display;
        try {
            // Consumer configures BEFORE the entity is added to the world (flash-dome precedent):
            // no client can ever receive it in a default-visible state.
            display = speaker.getWorld().spawn(at, TextDisplay.class, d -> {
                d.text(Component.text(text)); // literal only — NEVER parsed as markup
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(false);       // client occludes text behind blocks (owner: not through walls)
                d.setShadowed(false);
                d.setLineWidth(cfg.lineWidth());
                d.setPersistent(false);       // never written to disk — byte-level proven (pc-005)
                d.setVisibleByDefault(false); // per-viewer admission only, no first-tick flash
                d.setViewRange(cfg.viewRange()); // client cull belt; the hard guarantee is admission
                d.setTransformation(transformFor(0, cfg));
                d.addScoreboardTag(BUBBLE_TAG);
            });
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "bubble spawn failed for " + speaker.getName(), t);
            return;
        }
        if (!speaker.addPassenger(display)) {
            plugin.getLogger().warning("addPassenger refused a bubble mount for " + speaker.getName()
                    + " - bubble dropped");
            display.remove(); // same thread, same region: the display was just spawned at the speaker
            return;
        }
        Bubble bubble = new Bubble(display, speaker.getWorld().getUID(),
                System.nanoTime() + cfg.lifetimeNanos());
        for (Bubble evicted : pb.stack.push(bubble, cfg.maxPerPlayer())) {
            despawn(evicted);
        }
        restack(pb, cfg);
        scheduleExpiry(speaker, bubble, cfg);
        syncAdmission(speaker, pb, cfg, display);
    }

    /** Death/quit/mode clears — MUST run on the owner's region thread. */
    public void clearStack(Player player) {
        PlayerBubbles pb = bubbles.get(player.getUniqueId());
        if (pb == null) {
            return;
        }
        for (Bubble bubble : pb.stack.clear()) {
            despawn(bubble);
        }
        pb.admitted.clear();
    }

    /** Any thread: fans the clear out to every owner's region thread. */
    public void clearAll() {
        for (UUID id : bubbles.keySet()) {
            Player owner = plugin.getServer().getPlayer(id);
            if (owner != null) {
                owner.getScheduler().run(plugin, t -> clearStack(owner), null);
            }
        }
    }

    /** Sneak toggle — runs on the player's region thread (sync event). Q1 semantics. */
    public void onSneakToggle(Player player, boolean sneaking) {
        ProxChatConfig cfg = config.get();
        if (!cfg.hideOnSneak()) {
            return;
        }
        PlayerBubbles pb = bubbles.get(player.getUniqueId());
        if (pb == null || pb.stack.isEmpty()) {
            return;
        }
        pb.sneakHidden = sneaking;
        syncAdmission(player, pb, cfg, null); // instant hide/reveal; the heartbeat re-asserts
    }

    /** Admin/status surface: live counters from the concurrent maps (safe from any thread). */
    public String describe() {
        return "tracked-players=" + heartbeats.size() + " speakers-with-state=" + bubbles.size();
    }

    // ------------------------------------------------------------------ heartbeat internals

    private void heartbeat(Player player) {
        try {
            Location at = player.getLocation();
            snapshots.put(player.getUniqueId(),
                    new PlayerSnapshot(player.getWorld().getUID(), at.getX(), at.getY(), at.getZ()));
            PlayerBubbles pb = bubbles.get(player.getUniqueId());
            if (pb == null || pb.stack.isEmpty()) {
                return;
            }
            if (!renders.getAsBoolean()) {
                // Mode backstop (pc-008 review): OFF/SUPPRESSED render nothing, so any bubble
                // that slipped past a leave-ON clear dies within one heartbeat period.
                clearStack(player);
                return;
            }
            ProxChatConfig cfg = config.get();
            pb.sneakHidden = cfg.hideOnSneak() && player.isSneaking(); // self-heals a missed toggle event
            reconcile(player, pb);
            if (!pb.stack.isEmpty()) {
                syncAdmission(player, pb, cfg, null);
            }
        } catch (Throwable t) {
            // §3.5: fail toward removal, never toward frozen text — and keep the heartbeat alive.
            plugin.getLogger().log(Level.WARNING,
                    "bubble heartbeat failed for " + player.getName() + " - clearing their bubbles", t);
            try {
                clearStack(player);
            } catch (Throwable suppressed) {
                plugin.getLogger().log(Level.WARNING, "bubble clear also failed", suppressed);
            }
        }
    }

    /**
     * Enforces the §3.5 invariant on the speaker's thread: a bubble exists ⇔ speaker alive, same
     * world (Q3), riding link intact (portals dismount passengers — the world-switch signal that
     * replaces Folia's dead PlayerChangedWorldEvent), lifetime remaining. Violation ⇒ despawn.
     */
    private void reconcile(Player speaker, PlayerBubbles pb) {
        long now = System.nanoTime();
        UUID worldId = speaker.getWorld().getUID();
        List<Entity> passengers = speaker.getPassengers();
        boolean dead = speaker.isDead();
        List<Bubble> drop = null;
        for (Bubble bubble : pb.stack.elements()) {
            boolean broken = dead
                    || now >= bubble.expiresAtNanos()
                    || !worldId.equals(bubble.worldId())
                    || !passengers.contains(bubble.display());
            if (broken) {
                if (drop == null) {
                    drop = new ArrayList<>(3);
                }
                drop.add(bubble);
            }
        }
        if (drop == null) {
            return;
        }
        for (Bubble bubble : drop) {
            pb.stack.remove(bubble);
            despawn(bubble);
        }
        restack(pb, config.get());
    }

    private void scheduleExpiry(Player speaker, Bubble bubble, ProxChatConfig cfg) {
        // Fast path on the speaker's scheduler; a refusal-at-fire (speaker gone) is fine because
        // the quit clear already despawned everything. No hide fan-out on expiry: server-side
        // removal broadcasts the despawn to every tracking client.
        speaker.getScheduler().runDelayed(plugin, t -> {
            PlayerBubbles pb = bubbles.get(speaker.getUniqueId());
            if (pb == null || !pb.stack.remove(bubble)) {
                return; // already evicted/cleared — removal is idempotent
            }
            despawn(bubble);
            restack(pb, config.get());
        }, null, cfg.lifetimeTicks());
    }

    /** Safe from any thread; a schedule refusal means the display is already gone (pc-004). */
    private void despawn(Bubble bubble) {
        TextDisplay display = bubble.display();
        display.getScheduler().run(plugin, t -> display.remove(), null);
    }

    /** Re-applies slot translations. Only called right after reconcile, so every display in the
     *  stack is a live passenger of the speaker — same region by construction. */
    private void restack(PlayerBubbles pb, ProxChatConfig cfg) {
        List<Bubble> elements = pb.stack.elements();
        for (int slot = 0; slot < elements.size(); slot++) {
            Bubble bubble = elements.get(slot);
            try {
                bubble.display().setTransformation(transformFor(slot, cfg));
            } catch (Throwable t) {
                // Contain rather than kill the pass; the next reconcile drops the offender.
                pb.stack.remove(bubble);
                despawn(bubble);
            }
        }
    }

    private static Transformation transformFor(int slot, ProxChatConfig cfg) {
        return new Transformation(
                new Vector3f(0f, (float) (cfg.heightAboveHead() + slot * cfg.stackSpacing()), 0f),
                new Quaternionf(), new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    // ------------------------------------------------------------------ admission

    /**
     * Diffs desired-vs-admitted on the speaker's thread and fans show/hide out to viewer
     * threads. {@code newest} non-null = a publish: viewers already admitted only need the new
     * display (see the PlayerBubbles invariant); newly admitted viewers get the whole stack.
     */
    private void syncAdmission(Player speaker, PlayerBubbles pb, ProxChatConfig cfg, TextDisplay newest) {
        Set<UUID> desired = pb.sneakHidden ? Set.of() : computeDesired(speaker.getUniqueId(), cfg);
        List<TextDisplay> all = new ArrayList<>(pb.stack.size());
        for (Bubble bubble : pb.stack.elements()) {
            all.add(bubble.display());
        }
        for (UUID viewerId : desired) {
            if (pb.admitted.add(viewerId)) {
                scheduleVisibility(viewerId, all, true);
            } else if (newest != null) {
                scheduleVisibility(viewerId, List.of(newest), true);
            }
        }
        pb.admitted.removeIf(viewerId -> {
            if (desired.contains(viewerId)) {
                return false;
            }
            scheduleVisibility(viewerId, all, false);
            return true;
        });
    }

    /** Pure snapshot math: same world, squared distance, includes the speaker (Q2 self-view). */
    private Set<UUID> computeDesired(UUID speakerId, ProxChatConfig cfg) {
        PlayerSnapshot me = snapshots.get(speakerId);
        if (me == null) {
            return Set.of();
        }
        double r2 = cfg.radiusSquared();
        Set<UUID> desired = new HashSet<>();
        for (Map.Entry<UUID, PlayerSnapshot> entry : snapshots.entrySet()) {
            PlayerSnapshot other = entry.getValue();
            if (other.worldId().equals(me.worldId()) && other.distanceSquaredTo(me) <= r2) {
                desired.add(entry.getKey());
            }
        }
        return desired;
    }

    private void scheduleVisibility(UUID viewerId, List<TextDisplay> displays, boolean show) {
        Player viewer = plugin.getServer().getPlayer(viewerId);
        if (viewer == null) {
            return; // quit since the snapshot — the admitted set was already updated by the caller
        }
        viewer.getScheduler().run(plugin, t -> {
            for (TextDisplay display : displays) {
                try {
                    if (show) {
                        viewer.showEntity(plugin, display);
                    } else {
                        viewer.hideEntity(plugin, display);
                    }
                } catch (Throwable ignored) {
                    // Display died between capture and execution — the tracker broadcast its
                    // removal already; stale bookkeeping on the viewer is harmless.
                }
            }
        }, null);
    }
}
