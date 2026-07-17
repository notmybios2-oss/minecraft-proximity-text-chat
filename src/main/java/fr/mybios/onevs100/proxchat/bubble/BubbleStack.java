package fr.mybios.onevs100.proxchat.bubble;

import java.util.ArrayList;
import java.util.List;

/**
 * Newest-first bubble ordering with bounded depth (owner spec: the newest message enters at the
 * bottom slot, older text shifts up, the oldest falls off the top). The index in
 * {@link #elements()} IS the render slot: 0 = bottom, closest to the head.
 *
 * Pure collection logic — thread confinement is the caller's job (each player's stack only ever
 * mutates on that player's region thread, see BubbleService).
 */
public final class BubbleStack<T> {

    private final ArrayList<T> newestFirst = new ArrayList<>();

    /**
     * Inserts at slot 0. Returns the elements pushed past {@code max} (for the caller to
     * despawn), oldest last; empty list when nothing overflowed.
     */
    public List<T> push(T element, int max) {
        newestFirst.add(0, element);
        if (newestFirst.size() <= max) {
            return List.of();
        }
        List<T> overflow = newestFirst.subList(max, newestFirst.size());
        List<T> evicted = List.copyOf(overflow);
        overflow.clear();
        return evicted;
    }

    /** True if the element was present (idempotent removal — expiry and reconciler may race benignly). */
    public boolean remove(T element) {
        return newestFirst.remove(element);
    }

    /** Snapshot in slot order (index = slot). Safe to iterate while mutating the stack. */
    public List<T> elements() {
        return List.copyOf(newestFirst);
    }

    /** Drains the stack, returning everything that was in it (newest first). */
    public List<T> clear() {
        List<T> drained = List.copyOf(newestFirst);
        newestFirst.clear();
        return drained;
    }

    public boolean isEmpty() {
        return newestFirst.isEmpty();
    }

    public int size() {
        return newestFirst.size();
    }
}
