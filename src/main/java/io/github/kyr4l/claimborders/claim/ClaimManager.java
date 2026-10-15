package io.github.kyr4l.claimborders.claim;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** In-memory claim index plus debounced persistence. All calls happen on the main thread. */
public final class ClaimManager {

    public enum ClaimResult { OK, ALREADY_OWN, TAKEN, LIMIT, NOT_ADJACENT, WORLD_DISABLED }

    /** Fired after a chunk was added to or removed from a claim. */
    public record ClaimChange(Claim claim, ChunkKey chunk, boolean added) {
    }

    private final ClaimBordersPlugin plugin;
    private final ClaimStorage storage;
    private final Map<UUID, Claim> byOwner = new HashMap<>();
    private final Map<ChunkKey, Claim> byChunk = new HashMap<>();
    private final List<Consumer<ClaimChange>> listeners = new ArrayList<>();
    private boolean dirty;
    private boolean saveDisabled;
    private int saveTaskId = -1;

    public ClaimManager(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
        this.storage = new ClaimStorage(plugin.getDataFolder().toPath().resolve("claims.json"), plugin.getLogger());
    }

    // ---- lifecycle -------------------------------------------------------

    public void load() {
        byOwner.clear();
        byChunk.clear();
        List<Claim> loaded = storage.load();
        if (loaded == null) {
            saveDisabled = true;
            plugin.getLogger().severe("claims.json is unreadable. Saving is DISABLED until it is fixed or removed, so nothing is overwritten.");
            return;
        }
        for (Claim claim : loaded) {
            Claim existing = byOwner.get(claim.getOwner());
            if (existing != null) {
                plugin.getLogger().warning("Owner " + claim.getOwnerName() + " is listed twice; merging.");
                claim.getTrusted().forEach((id, t) -> existing.setTrusted(id, t.name(), t.level()));
                for (ChunkKey key : new ArrayList<>(claim.getChunks())) {
                    existing.addChunk(key);
                }
                claim = existing;
            } else {
                byOwner.put(claim.getOwner(), claim);
            }
            for (ChunkKey key : new ArrayList<>(claim.getChunks())) {
                Claim previous = byChunk.get(key);
                if (previous != null && previous != claim) {
                    plugin.getLogger().warning("Chunk " + key + " is listed for two owners; keeping " + previous.getOwnerName());
                    claim.removeChunk(key);
                } else {
                    byChunk.put(key, claim);
                }
            }
        }
        plugin.getLogger().info("Loaded " + byChunk.size() + " claimed chunk(s) for " + byOwner.size() + " player(s).");
    }

    /** Blocking save, used on disable. */
    public void saveNow() {
        if (saveDisabled) {
            return;
        }
        if (saveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(saveTaskId);
            saveTaskId = -1;
        }
        dirty = false;
        storage.write(storage.serialize(byOwner.values()));
    }

    private void markDirty() {
        if (saveDisabled) {
            return;
        }
        dirty = true;
        if (saveTaskId != -1) {
            return;
        }
        // Debounce: serialise on the main thread 2 s later, write on an async thread.
        saveTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveTaskId = -1;
            if (!dirty) {
                return;
            }
            dirty = false;
            String json = storage.serialize(byOwner.values());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.write(json));
        }, 40L).getTaskId();
    }

    public void addListener(Consumer<ClaimChange> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ClaimChange> listener) {
        listeners.remove(listener);
    }

    private void fire(ClaimChange change) {
        // Copy: a listener may unregister itself (renderer restart) while we iterate.
        for (Consumer<ClaimChange> l : new ArrayList<>(listeners)) {
            try {
                l.accept(change);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Claim listener failed for " + change.chunk(), ex);
            }
        }
    }

    // ---- queries ---------------------------------------------------------

    public Claim getClaimAt(ChunkKey key) {
        return byChunk.get(key);
    }

    public Claim getClaimAt(Location location) {
        return location.getWorld() == null ? null : byChunk.get(ChunkKey.of(location));
    }

    public Claim getClaimAt(World world, int chunkX, int chunkZ) {
        return byChunk.get(ChunkKey.of(world, chunkX, chunkZ));
    }

    public Claim getClaimOf(UUID owner) {
        return byOwner.get(owner);
    }

    public Collection<Claim> getClaims() {
        return Collections.unmodifiableCollection(byOwner.values());
    }

    public int totalChunks() {
        return byChunk.size();
    }

    /** True if the player may be inside / act in the chunk: unclaimed, trusted, or bypass permission. */
    public boolean canAccess(Player player, ChunkKey key) {
        Claim claim = byChunk.get(key);
        return claim == null || claim.canEnter(player) || player.hasPermission("claimborders.bypass");
    }

    public boolean canAccess(Player player, Location location) {
        return location.getWorld() == null || canAccess(player, ChunkKey.of(location));
    }

    public boolean isWorldAllowed(World world) {
        List<String> allowed = plugin.settings().allowedWorlds();
        List<String> blocked = plugin.settings().blockedWorlds();
        if (blocked.contains(world.getName())) {
            return false;
        }
        return allowed.isEmpty() || allowed.contains(world.getName());
    }

    /** Chunk limit from claimborders.limit.<n> / .unlimited, else the config default. */
    public int limitFor(Player player) {
        if (player.hasPermission("claimborders.limit.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int best = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("claimborders.limit.")) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring("claimborders.limit.".length())));
                } catch (NumberFormatException ignored) {
                    // not a numeric limit node
                }
            }
        }
        return best >= 0 ? best : plugin.settings().maxChunks();
    }

    // ---- mutations -------------------------------------------------------

    public ClaimResult claim(Player player, ChunkKey key) {
        World world = key.bukkitWorld();
        if (world == null || !isWorldAllowed(world)) {
            return ClaimResult.WORLD_DISABLED;
        }
        Claim existing = byChunk.get(key);
        if (existing != null) {
            return existing.isOwner(player.getUniqueId()) ? ClaimResult.ALREADY_OWN : ClaimResult.TAKEN;
        }
        Claim claim = byOwner.get(player.getUniqueId());
        int owned = claim == null ? 0 : claim.size();
        if (owned >= limitFor(player)) {
            return ClaimResult.LIMIT;
        }
        if (claim != null && !claim.isEmpty() && plugin.settings().requireAdjacent() && !claim.isAdjacent(key)) {
            return ClaimResult.NOT_ADJACENT;
        }
        if (claim == null) {
            claim = new Claim(player.getUniqueId(), player.getName());
            byOwner.put(player.getUniqueId(), claim);
        }
        claim.setOwnerName(player.getName());
        claim.addChunk(key);
        byChunk.put(key, claim);
        markDirty();
        fire(new ClaimChange(claim, key, true));
        return ClaimResult.OK;
    }

    /** Removes the chunk from whatever claim owns it. Returns the former claim or null. */
    public Claim unclaim(ChunkKey key) {
        Claim claim = byChunk.remove(key);
        if (claim == null) {
            return null;
        }
        claim.removeChunk(key);
        if (claim.isEmpty()) {
            byOwner.remove(claim.getOwner());
        }
        markDirty();
        fire(new ClaimChange(claim, key, false));
        return claim;
    }

    public int unclaimAll(UUID owner) {
        Claim claim = byOwner.get(owner);
        if (claim == null) {
            return 0;
        }
        List<ChunkKey> keys = new ArrayList<>(claim.getChunks());
        for (ChunkKey key : keys) {
            unclaim(key);
        }
        return keys.size();
    }

    public boolean trust(Claim claim, UUID target, String name, TrustLevel level) {
        if (claim.setTrusted(target, name, level)) {
            markDirty();
            return true;
        }
        return false;
    }

    public boolean untrust(Claim claim, UUID target) {
        if (claim.removeTrusted(target)) {
            markDirty();
            return true;
        }
        return false;
    }
}
