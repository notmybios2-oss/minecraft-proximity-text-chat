package fr.mybios.onevs100.proxchat.bubble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The stack machine (pc-003 slice-2 unit list): newest bottom, oldest off the top. */
class BubbleStackTest {

    private static final int MAX = 3; // production default

    @Test
    void newestSitsAtSlotZero() {
        BubbleStack<String> stack = new BubbleStack<>();
        stack.push("first", MAX);
        stack.push("second", MAX);
        assertEquals(List.of("second", "first"), stack.elements());
    }

    @Test
    void overflowEvictsTheOldest() {
        BubbleStack<String> stack = new BubbleStack<>();
        assertEquals(List.of(), stack.push("a", MAX));
        assertEquals(List.of(), stack.push("b", MAX));
        assertEquals(List.of(), stack.push("c", MAX));
        assertEquals(List.of("a"), stack.push("d", MAX)); // oldest falls off the top
        assertEquals(List.of("d", "c", "b"), stack.elements());
    }

    @Test
    void shrinkingMaxEvictsEverythingPastIt() {
        BubbleStack<String> stack = new BubbleStack<>();
        stack.push("a", 3);
        stack.push("b", 3);
        stack.push("c", 3);
        // A config retune (slice 3 reload) can lower the cap mid-flight: one push settles it.
        assertEquals(List.of("b", "a"), stack.push("d", 2));
        assertEquals(List.of("d", "c"), stack.elements());
    }

    @Test
    void removalIsIdempotentAndReslots() {
        BubbleStack<String> stack = new BubbleStack<>();
        stack.push("a", MAX);
        stack.push("b", MAX);
        stack.push("c", MAX);
        assertTrue(stack.remove("b")); // expiry fast path
        assertFalse(stack.remove("b")); // reconciler racing the same bubble: benign no-op
        assertEquals(List.of("c", "a"), stack.elements()); // "a" moved down a slot
    }

    @Test
    void clearDrainsEverything() {
        BubbleStack<String> stack = new BubbleStack<>();
        stack.push("a", MAX);
        stack.push("b", MAX);
        assertEquals(List.of("b", "a"), stack.clear()); // caller despawns each
        assertTrue(stack.isEmpty());
        assertEquals(0, stack.size());
        assertEquals(List.of(), stack.clear());
    }

    @Test
    void elementsIsASnapshot() {
        BubbleStack<String> stack = new BubbleStack<>();
        stack.push("a", MAX);
        List<String> snapshot = stack.elements();
        stack.push("b", MAX);
        assertEquals(List.of("a"), snapshot); // reconciler iterates while removing
    }
}
