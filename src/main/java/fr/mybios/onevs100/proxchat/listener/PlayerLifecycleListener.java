package fr.mybios.onevs100.proxchat.listener;

import fr.mybios.onevs100.proxchat.bubble.BubbleService;
import fr.mybios.onevs100.proxchat.rate.RateGuard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * Player lifecycle → bubble state. These sync events all fire on the player's owning region
 * thread, which is exactly where BubbleService wants them (state confinement). World switches
 * have NO event here on purpose: Folia's PlayerChangedWorldEvent is dead for portals
 * (known-issues), so the Q3 clear-on-switch rides the heartbeat reconciler instead (riding-link
 * break + world-id comparison).
 */
public final class PlayerLifecycleListener implements Listener {

    private final BubbleService bubbles;
    private final RateGuard rateGuard;

    public PlayerLifecycleListener(BubbleService bubbles, RateGuard rateGuard) {
        this.bubbles = bubbles;
        this.rateGuard = rateGuard;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bubbles.track(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bubbles.untrack(event.getPlayer()); // nothing lingers on a disconnect (owner spec)
        rateGuard.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        bubbles.clearStack(event.getEntity()); // nothing lingers on a corpse (owner spec)
    }

    @EventHandler
    public void onSneakToggle(PlayerToggleSneakEvent event) {
        bubbles.onSneakToggle(event.getPlayer(), event.isSneaking()); // Q1, instant response
    }
}
