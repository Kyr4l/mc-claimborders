package io.github.kyr4l.claimborders.protect;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.claim.ChunkKey;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.TrustLevel;
import io.github.kyr4l.claimborders.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Action-bar messages around claims: members and visitors are welcomed once they are a few blocks inside,
 * outsiders are warned once they come within a few blocks of the border. Each message fires once per
 * entry / approach and re-arms when the player leaves again. Owners can switch it off per claim.
 */
public final class ZoneMessages {

    private static final class State {
        UUID inside;          // claim the player is currently in (owner id), or null
        boolean welcomed;     // welcome already sent for this stay
        UUID approached;      // claim the approach warning was sent for, until the player backs off
    }

    private final ClaimBordersPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask task;

    public ZoneMessages(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        states.clear();
    }

    public void forget(Player player) {
        states.remove(player.getUniqueId());
    }

    private void tick() {
        ClaimConfig cfg = plugin.settings();
        if (!cfg.zoneMessagesEnabled()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Players.isFake(p)) {
                continue;
            }
            State state = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            Location loc = p.getLocation();
            Claim here = plugin.claims().getClaimAt(loc);
            if (here != null) {
                state.approached = null;
                if (!here.getOwner().equals(state.inside)) {
                    state.inside = here.getOwner();
                    state.welcomed = false;
                }
                TrustLevel level = here.levelOf(p);
                if (!state.welcomed && level.canEnter() && here.isMessagesEnabled()
                        && depthInside(loc, here) >= cfg.welcomeDepth()) {
                    String key = here.isOwner(p.getUniqueId()) ? "welcome-owner"
                            : level == TrustLevel.VISITOR ? "welcome-visitor" : "welcome";
                    Text.actionBar(p, cfg.zoneMsg(key, here, p));
                    state.welcomed = true;
                }
                continue;
            }
            state.inside = null;
            state.welcomed = false;

            Claim nearest = null;
            double nearestDist = Double.MAX_VALUE;
            ChunkKey center = ChunkKey.of(loc);
            int ring = (int) Math.ceil(cfg.approachDistance() / 16.0) + 1;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    ChunkKey key = center.offset(dx, dz);
                    Claim claim = plugin.claims().getClaimAt(key);
                    if (claim == null || claim.canEnter(p) || !claim.isMessagesEnabled() || p.hasPermission("claimborders.bypass")) {
                        continue;
                    }
                    double d = distanceToChunk(loc, key);
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearest = claim;
                    }
                }
            }
            if (nearest != null && nearestDist <= cfg.approachDistance()) {
                if (!nearest.getOwner().equals(state.approached)) {
                    Text.actionBar(p, cfg.zoneMsg("approach", nearest, p));
                    state.approached = nearest.getOwner();
                }
            } else if (nearest == null || nearestDist > cfg.approachDistance() + 8) {
                state.approached = null;  // backed off far enough: warn again on the next approach
            }
        }
        states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Horizontal distance from a point inside the claim to its nearest border. */
    static double depthInside(Location loc, Claim claim) {
        ChunkKey key = ChunkKey.of(loc);
        double px = loc.getX();
        double pz = loc.getZ();
        double minX = key.minBlockX();
        double minZ = key.minBlockZ();
        double depth = Double.MAX_VALUE;
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            if (claim.contains(key.offset(side.dx, side.dz))) {
                continue;
            }
            double d = switch (side) {
                case WEST -> px - minX;
                case EAST -> minX + 16 - px;
                case NORTH -> pz - minZ;
                case SOUTH -> minZ + 16 - pz;
            };
            depth = Math.min(depth, d);
        }
        // Corners: a missing diagonal chunk exposes the corner point even if both side chunks are owned.
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (!claim.contains(key.offset(dx, dz))) {
                    double cx = dx < 0 ? minX : minX + 16;
                    double cz = dz < 0 ? minZ : minZ + 16;
                    depth = Math.min(depth, Math.hypot(px - cx, pz - cz));
                }
            }
        }
        return depth;
    }

    /** Horizontal distance from a point to a chunk's footprint (0 when inside). */
    static double distanceToChunk(Location loc, ChunkKey key) {
        double nx = Math.max(key.minBlockX(), Math.min(key.minBlockX() + 16, loc.getX()));
        double nz = Math.max(key.minBlockZ(), Math.min(key.minBlockZ() + 16, loc.getZ()));
        return Math.hypot(loc.getX() - nx, loc.getZ() - nz);
    }
}
