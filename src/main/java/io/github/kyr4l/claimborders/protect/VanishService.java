package io.github.kyr4l.claimborders.protect;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.Claim;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Vanish mode: a claim owner who turned it on is hidden from everyone while inside their own claim.
 * Uses Player#hidePlayer, which removes the entity and the tab-list entry for each viewer, and sets the
 * "vanished" metadata key that tab-list and map plugins check. Join/quit messages are suppressed when
 * the owner joins or leaves inside the claim. Players with claimborders.vanish.see still see them.
 */
public final class VanishService implements Listener {

    public static final String SEE_PERMISSION = "claimborders.vanish.see";
    private static final String METADATA_KEY = "vanished";

    private final ClaimBordersPlugin plugin;
    private final Set<UUID> vanished = new HashSet<>();
    private BukkitTask task;

    public VanishService(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new HashSet<>(vanished)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                unvanish(p);
            }
        }
        vanished.clear();
    }

    private ClaimConfig cfg() {
        return plugin.settings();
    }

    public boolean isVanished(Player p) {
        return vanished.contains(p.getUniqueId());
    }

    /** True when the player has vanish on and stands inside their own claim. */
    private boolean shouldVanish(Player p) {
        if (!cfg().vanishEnabled()) {
            return false;
        }
        Claim own = plugin.claims().getClaimOf(p.getUniqueId());
        return own != null && own.isVanishEnabled() && plugin.claims().getClaimAt(p.getLocation()) == own;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean should = shouldVanish(p);
            boolean is = vanished.contains(p.getUniqueId());
            if (should && !is) {
                vanish(p);
            } else if (!should && is) {
                unvanish(p);
            }
        }
        vanished.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void vanish(Player p) {
        vanished.add(p.getUniqueId());
        p.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(p) && !viewer.hasPermission(SEE_PERMISSION)) {
                viewer.hidePlayer(plugin, p);
            }
        }
        p.sendMessage(cfg().msg("vanish-now-hidden"));
    }

    private void unvanish(Player p) {
        vanished.remove(p.getUniqueId());
        p.removeMetadata(METADATA_KEY, plugin);
        if (!plugin.isEnabled()) {
            return;     // Bukkit refuses show/hide calls from a disabling plugin; hidden state dies with it
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(p)) {
                viewer.showPlayer(plugin, p);
            }
        }
        if (p.isOnline()) {
            p.sendMessage(cfg().msg("vanish-now-visible"));
        }
    }

    /** Hide already-vanished owners from the newcomer; vanish the newcomer if they log in inside their claim. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player joiner = e.getPlayer();
        if (!joiner.hasPermission(SEE_PERMISSION)) {
            for (UUID id : vanished) {
                Player hidden = Bukkit.getPlayer(id);
                if (hidden != null && !hidden.equals(joiner)) {
                    joiner.hidePlayer(plugin, hidden);
                }
            }
        }
        if (shouldVanish(joiner)) {
            vanish(joiner);
            if (cfg().vanishHideJoinQuit()) {
                e.setJoinMessage(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (vanished.remove(p.getUniqueId())) {
            p.removeMetadata(METADATA_KEY, plugin);
            if (cfg().vanishHideJoinQuit()) {
                e.setQuitMessage(null);
            }
        }
    }
}
