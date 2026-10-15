package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.BoundaryCalculator;
import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.claim.ChunkKey;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One scaled, translucent BlockDisplay panel per boundary edge: a real "force field" wall that
 * everyone sees, with no per-player packet work. Entities are non-persistent and tagged, so a
 * crash or a PlugManX unload never leaves garbage behind (we also sweep on start).
 * Needs Minecraft 1.19.4 or newer.
 */
public final class DisplayEntityRenderer implements BorderRenderer {

    private final ClaimBordersPlugin plugin;
    private final BorderService service;
    private final NamespacedKey tag;
    private final Map<BoundaryEdge, UUID> entities = new HashMap<>();
    private final Map<UUID, Set<UUID>> shownNear = new HashMap<>();

    public DisplayEntityRenderer(ClaimBordersPlugin plugin, BorderService service) {
        this.plugin = plugin;
        this.service = service;
        this.tag = new NamespacedKey(plugin, "border-edge");
    }

    @Override
    public void start() {
        sweepLeftovers();
        for (Claim claim : plugin.claims().getClaims()) {
            for (ChunkKey key : claim.getChunks()) {
                if (key.isLoaded()) {
                    syncChunk(key);
                }
            }
        }
    }

    @Override
    public boolean needsTick() {
        return plugin.settings().displayReveal() == ClaimConfig.RevealMode.NEAR;
    }

    @Override
    public int radius() {
        return plugin.settings().displayRevealRadius();
    }

    // ---- NEAR reveal: per-player show/hide ---------------------------------

    @Override
    public void render(Player viewer, List<EdgeView> edges) {
        Set<UUID> wanted = new HashSet<>();
        for (EdgeView view : edges) {
            UUID id = entities.get(view.edge());
            if (id != null) {
                wanted.add(id);
            }
        }
        Set<UUID> shown = shownNear.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
        for (UUID id : new ArrayList<>(shown)) {
            if (!wanted.contains(id)) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    viewer.hideEntity(plugin, e);
                }
                shown.remove(id);
            }
        }
        for (UUID id : wanted) {
            if (shown.add(id)) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    viewer.showEntity(plugin, e);
                }
            }
        }
    }

    @Override
    public void clear(Player viewer) {
        Set<UUID> shown = shownNear.remove(viewer.getUniqueId());
        if (shown == null || !viewer.isOnline()) {
            return;
        }
        for (UUID id : shown) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                viewer.hideEntity(plugin, e);
            }
        }
    }

    // ---- ALWAYS reveal: hide only from trusted members -----------------------

    @Override
    public void onShowToggle(Player viewer, boolean showing) {
        if (plugin.settings().displayReveal() != ClaimConfig.RevealMode.ALWAYS) {
            return;
        }
        for (Map.Entry<BoundaryEdge, UUID> entry : entities.entrySet()) {
            Claim claim = plugin.claims().getClaimAt(entry.getKey().chunk());
            if (claim != null && claim.isTrusted(viewer)) {
                applyVisibility(viewer, entry.getValue(), claim);
            }
        }
    }

    @Override
    public void onJoin(Player player) {
        for (Map.Entry<BoundaryEdge, UUID> entry : entities.entrySet()) {
            Claim claim = plugin.claims().getClaimAt(entry.getKey().chunk());
            applyVisibility(player, entry.getValue(), claim);
        }
    }

    private void applyVisibility(Player player, UUID entityId, Claim claim) {
        Entity e = Bukkit.getEntity(entityId);
        if (e == null) {
            return;
        }
        boolean visible;
        if (plugin.settings().displayReveal() == ClaimConfig.RevealMode.NEAR) {
            visible = false; // revealed by render() when close
        } else {
            boolean trusted = claim != null && claim.isTrusted(player);
            visible = !(trusted && plugin.settings().displayHideFromTrusted() && !service.isShowing(player));
        }
        if (visible) {
            player.showEntity(plugin, e);
        } else {
            player.hideEntity(plugin, e);
        }
    }

    // ---- keeping entities in sync with claims and chunk loading -----------

    @Override
    public void onClaimChange(ClaimManager.ClaimChange change) {
        ChunkKey key = change.chunk();
        syncChunk(key);
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            syncChunk(key.offset(side.dx, side.dz));
        }
    }

    @Override
    public void onChunkLoad(Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        if (plugin.claims().getClaimAt(key) != null) {
            syncChunk(key);
        }
    }

    @Override
    public void onChunkUnload(Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            removeEdge(new BoundaryEdge(key, side));
        }
    }

    /** Make the entities of this chunk match its current boundary edges. */
    private void syncChunk(ChunkKey key) {
        if (!key.isLoaded()) {
            return;
        }
        Set<BoundaryEdge> wanted = new HashSet<>(BoundaryCalculator.edgesOf(plugin.claims(), key));
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            BoundaryEdge edge = new BoundaryEdge(key, side);
            UUID existing = entities.get(edge);
            boolean alive = existing != null && Bukkit.getEntity(existing) != null;
            if (wanted.contains(edge)) {
                if (!alive) {
                    entities.remove(edge);
                    spawnEdge(edge);
                }
            } else if (existing != null) {
                removeEdge(edge);
            }
        }
    }

    private void spawnEdge(BoundaryEdge edge) {
        World world = edge.chunk().bukkitWorld();
        if (world == null) {
            return;
        }
        ClaimConfig cfg = plugin.settings();
        float thickness = cfg.displayThickness();
        int minY;
        int height;
        if (cfg.displayHeight() < 0) {
            minY = world.getMinHeight();
            height = world.getMaxHeight() - minY;
        } else {
            int surface = world.getMinHeight();
            for (int i = 0; i < 16; i++) {
                surface = Math.max(surface, world.getHighestBlockYAt(edge.innerBlockX(i), edge.innerBlockZ(i)));
            }
            minY = Math.max(world.getMinHeight(), surface - 2);
            height = cfg.displayHeight();
        }

        double x;
        double z;
        Vector3f scale;
        if (edge.alongX()) {
            x = edge.chunk().minBlockX();
            z = edge.side() == BoundaryEdge.Side.NORTH ? edge.chunk().minBlockZ() : edge.chunk().minBlockZ() + 16 - thickness;
            scale = new Vector3f(16f, height, thickness);
        } else {
            x = edge.side() == BoundaryEdge.Side.WEST ? edge.chunk().minBlockX() : edge.chunk().minBlockX() + 16 - thickness;
            z = edge.chunk().minBlockZ();
            scale = new Vector3f(thickness, height, 16f);
        }

        Location loc = new Location(world, x, minY, z);
        BlockDisplay display = world.spawn(loc, BlockDisplay.class);
        display.setBlock(cfg.displayMaterial().createBlockData());
        display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), scale, new Quaternionf()));
        display.setBrightness(new Display.Brightness(15, 15));
        display.setDisplayWidth(32f);
        display.setDisplayHeight(height + 2f);
        display.setViewRange(1.5f);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setGravity(false);
        if (cfg.displayGlow()) {
            display.setGlowing(true);
            display.setGlowColorOverride(cfg.displayGlowColor());
        }
        display.getPersistentDataContainer().set(tag, PersistentDataType.STRING, edge.chunk().serialize() + ";" + edge.side());
        entities.put(edge, display.getUniqueId());

        Claim claim = plugin.claims().getClaimAt(edge.chunk());
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyVisibility(p, display.getUniqueId(), claim);
        }
    }

    private void removeEdge(BoundaryEdge edge) {
        UUID id = entities.remove(edge);
        if (id == null) {
            return;
        }
        Entity e = Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
        }
        for (Set<UUID> shown : shownNear.values()) {
            shown.remove(id);
        }
    }

    /** Removes tagged panels left behind by a previous plugin instance (PlugManX unload, crash...). */
    private void sweepLeftovers() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (BlockDisplay d : world.getEntitiesByClass(BlockDisplay.class)) {
                if (d.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) {
                    d.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " leftover border panel(s).");
        }
    }

    @Override
    public void shutdown() {
        for (UUID id : entities.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        entities.clear();
        shownNear.clear();
        sweepLeftovers();
    }
}
