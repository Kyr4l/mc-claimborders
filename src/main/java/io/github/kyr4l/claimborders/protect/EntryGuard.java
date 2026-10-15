package io.github.kyr4l.claimborders.protect;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.ChunkKey;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * The force field: keeps non-trusted players out of claimed chunks.
 * Only reacts to player and vanilla-vehicle movement; it never touches mod entities,
 * contraptions or block updates, so Create machinery crossing a border is unaffected.
 */
public final class EntryGuard implements Listener {

    private final ClaimBordersPlugin plugin;

    public EntryGuard(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
        plugin.claims().addListener(this::onClaimChange);
    }

    private ClaimConfig cfg() {
        return plugin.settings();
    }

    private boolean exempt(Player p) {
        return p.hasPermission("claimborders.bypass") || (cfg().ignoreFakePlayers() && Players.isFake(p));
    }

    // ---- walking ---------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || !cfg().forceFieldEnabled()) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        Player p = e.getPlayer();
        if (exempt(p)) {
            return;
        }
        boolean crossed = (from.getBlockX() >> 4) != (to.getBlockX() >> 4)
                || (from.getBlockZ() >> 4) != (to.getBlockZ() >> 4)
                || from.getWorld() != to.getWorld();
        if (crossed) {
            Claim target = plugin.claims().getClaimAt(to);
            if (target != null && !target.isTrusted(p) && plugin.claims().getClaimAt(from) != target) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                // Riders of client-controlled mounts (horses, boats) arrive here too; drop them off the mount.
                Entity mount = p.getVehicle();
                if (mount != null) {
                    p.leaveVehicle();
                    Vector shove = from.toVector().subtract(to.toVector());
                    if (shove.lengthSquared() > 1e-6) {
                        mount.setVelocity(shove.normalize().multiply(0.4));
                    }
                }
                e.setTo(back);
                if (cfg().forceFieldMode() != ClaimConfig.ForceFieldMode.HARD) {
                    knockBack(p, ChunkKey.of(to), from);
                }
                plugin.sendCooled(p, "entry", cfg().msg("entry-denied", "owner", target.getOwnerName()));
                return;
            }
        }
        boolean moved = from.getX() != to.getX() || from.getZ() != to.getZ();
        if (moved && !p.isInsideVehicle() && cfg().forceFieldMode() == ClaimConfig.ForceFieldMode.PUSH) {
            softPush(p, to);
        }
    }

    /** Horizontal shove away from the chunk the player tried to enter. */
    private void knockBack(Player p, ChunkKey target, Location from) {
        double cx = target.minBlockX() + 8.0;
        double cz = target.minBlockZ() + 8.0;
        double dx = from.getX() - cx;
        double dz = from.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            return;
        }
        double k = cfg().knockback();
        Vector v = new Vector(dx / len * k, 0.15, dz / len * k);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                p.setVelocity(v);
            }
        });
    }

    /** PUSH mode: gentle repulsion that grows as the player approaches a forbidden chunk. */
    private void softPush(Player p, Location at) {
        ChunkKey here = ChunkKey.of(at);
        Claim current = plugin.claims().getClaimAt(here);
        if (current != null && !current.isTrusted(p)) {
            return; // already inside (should not happen); let them walk out
        }
        double radius = cfg().pushRadius();
        double strength = cfg().pushStrength();
        double px = at.getX();
        double pz = at.getZ();
        double vx = 0;
        double vz = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkKey key = here.offset(dx, dz);
                Claim claim = plugin.claims().getClaimAt(key);
                if (claim == null || claim.isTrusted(p)) {
                    continue;
                }
                double nx = Math.max(key.minBlockX(), Math.min(key.minBlockX() + 16, px));
                double nz = Math.max(key.minBlockZ(), Math.min(key.minBlockZ() + 16, pz));
                double ddx = px - nx;
                double ddz = pz - nz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d >= radius || d < 1e-6) {
                    continue;
                }
                double f = strength * (1.0 - d / radius);
                vx += ddx / d * f;
                vz += ddz / d * f;
            }
        }
        if (vx != 0 || vz != 0) {
            Vector v = p.getVelocity();
            v.setX(vx);
            v.setZ(vz);
            p.setVelocity(v);
        }
    }

    // ---- teleporting -----------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!cfg().forceFieldEnabled() || !cfg().blockTeleports() || e.getTo() == null) {
            return;
        }
        Player p = e.getPlayer();
        if (exempt(p)) {
            return;
        }
        Claim target = plugin.claims().getClaimAt(e.getTo());
        // Same rule as walking: a teleport that stays inside the claim the player is already in (including the
        // server's own rubber-band after a denied move) is not an entry.
        if (target != null && !target.isTrusted(p) && plugin.claims().getClaimAt(e.getFrom()) != target) {
            e.setCancelled(true);
            plugin.sendCooled(p, "entry", cfg().msg("entry-denied", "owner", target.getOwnerName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!cfg().forceFieldEnabled() || !cfg().ejectOnJoin()) {
            return;
        }
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || exempt(p)) {
                return;
            }
            Claim claim = plugin.claims().getClaimAt(p.getLocation());
            if (claim != null && !claim.isTrusted(p)) {
                eject(p, claim);
            }
        }, 2L);
    }

    // ---- vanilla vehicles (boats, minecarts, horses...) -------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!cfg().forceFieldEnabled() || !cfg().blockVehicles()) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        boolean crossed = (from.getBlockX() >> 4) != (to.getBlockX() >> 4)
                || (from.getBlockZ() >> 4) != (to.getBlockZ() >> 4);
        if (!crossed) {
            return;
        }
        Claim target = plugin.claims().getClaimAt(to);
        if (target == null || plugin.claims().getClaimAt(from) == target) {
            return;
        }
        Vehicle vehicle = e.getVehicle();
        List<Player> offenders = new ArrayList<>();
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player p && !target.isTrusted(p) && !exempt(p)) {
                offenders.add(p);
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        for (Player p : offenders) {
            vehicle.removePassenger(p);
            Location back = from.clone();
            back.setYaw(p.getLocation().getYaw());
            back.setPitch(p.getLocation().getPitch());
            p.teleport(back);
            knockBack(p, ChunkKey.of(to), from);
            plugin.sendCooled(p, "entry", cfg().msg("entry-denied", "owner", target.getOwnerName()));
        }
        Vector v = from.toVector().subtract(to.toVector());
        if (v.lengthSquared() > 1e-6) {
            vehicle.setVelocity(v.normalize().multiply(0.4));
        }
        if (vehicle.getPassengers().isEmpty()) {
            vehicle.teleport(from);
        }
    }

    // ---- ejecting players who end up inside -------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg().forceFieldEnabled() || !cfg().ejectOnJoin()) {
            return;
        }
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || exempt(p)) {
                return;
            }
            Claim claim = plugin.claims().getClaimAt(p.getLocation());
            if (claim != null && !claim.isTrusted(p)) {
                eject(p, claim);
            }
        }, 10L);
    }

    private void onClaimChange(ClaimManager.ClaimChange change) {
        if (!change.added() || !cfg().forceFieldEnabled() || !cfg().ejectOnClaim()) {
            return;
        }
        World world = change.chunk().bukkitWorld();
        if (world == null) {
            return;
        }
        for (Player p : world.getPlayers()) {
            if (ChunkKey.of(p.getLocation()).equals(change.chunk()) && !change.claim().isTrusted(p) && !exempt(p)) {
                eject(p, change.claim());
            }
        }
    }

    public void eject(Player p, Claim claim) {
        Location safe = findSafeOutside(p);
        if (safe == null) {
            return;
        }
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        p.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.sendMessage(cfg().msg("ejected", "owner", claim.getOwnerName()));
    }

    /**
     * A standable spot in the nearest accessible chunk, searched around the player's own height so it also
     * works underground and in the Nether. Only loaded chunks are considered (never forces a chunk load).
     * Falls back to the world spawn.
     */
    private Location findSafeOutside(Player p) {
        Location origin = p.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        ChunkKey center = ChunkKey.of(origin);
        for (int ring = 1; ring <= 10; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    ChunkKey key = center.offset(dx, dz);
                    if (!world.isChunkLoaded(key.x(), key.z()) || !plugin.claims().canAccess(p, key)) {
                        continue;
                    }
                    Location spot = standable(world, key.minBlockX() + 8, key.minBlockZ() + 8, origin.getBlockY());
                    if (spot != null) {
                        spot.setYaw(origin.getYaw());
                        spot.setPitch(origin.getPitch());
                        return spot;
                    }
                }
            }
        }
        Location spawn = world.getSpawnLocation();
        return plugin.claims().canAccess(p, spawn) ? spawn : null;
    }

    /** Nearest y to {@code aroundY} in this column with a solid floor and two passable, non-liquid blocks. */
    private static Location standable(World world, int x, int z, int aroundY) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        for (int d = 0; d < 96; d++) {
            for (int y : new int[] {aroundY - d, aroundY + d}) {
                if (y < min || y > max) {
                    continue;
                }
                Block floor = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (floor.getType().isSolid() && floor.getType() != Material.MAGMA_BLOCK
                        && feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
                if (d == 0) {
                    break;
                }
            }
        }
        return null;
    }
}
