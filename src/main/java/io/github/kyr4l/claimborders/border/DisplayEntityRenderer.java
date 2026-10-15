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

    /**
     * Make the entities of this chunk match its current boundary edges. The length of a west/east panel
     * depends on whether a north/south panel exists in the same chunk, so any change respawns the whole chunk.
     */
    private void syncChunk(ChunkKey key) {
        if (!key.isLoaded()) {
            return;
        }
        Set<BoundaryEdge> wanted = new HashSet<>(BoundaryCalculator.edgesOf(plugin.claims(), key));
        Set<BoundaryEdge> alive = new HashSet<>();
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            BoundaryEdge edge = new BoundaryEdge(key, side);
            UUID existing = entities.get(edge);
            if (existing != null && Bukkit.getEntity(existing) != null) {
                alive.add(edge);
            } else if (existing != null) {
                entities.remove(edge);
            }
        }
        if (alive.equals(wanted)) {
            return;
        }
        for (BoundaryEdge edge : alive) {
            removeEdge(edge);
        }
        for (BoundaryEdge edge : wanted) {
            spawnEdge(edge, wanted);
        }
    }

    /** Gap kept between a panel and the chunk line, and between panels. Keeps faces off each other's planes. */
    private static final float INSET = 0.025f;

    private void spawnEdge(BoundaryEdge edge, Set<BoundaryEdge> chunkEdges) {
        World world = edge.chunk().bukkitWorld();
        if (world == null) {
            return;
        }
        ClaimConfig cfg = plugin.settings();
        float thickness = cfg.displayThickness();

        // Terrain height along this edge. The entity's ORIGIN must sit in a chunk section the client actually
        // renders (surface level): clients skip entities whose own section is occluded/unbuilt, and an origin at
        // bedrock is exactly that. The model is then shifted down with a translation so it still spans the column.
        int surface = world.getMinHeight();
        for (int i = 0; i < 16; i++) {
            surface = Math.max(surface, world.getHighestBlockYAt(edge.innerBlockX(i), edge.innerBlockZ(i)));
        }
        int originY = Math.min(world.getMaxHeight() - 2, surface + 1);
        int minY;
        int height;
        if (cfg.displayHeight() < 0) {
            minY = world.getMinHeight();
            height = world.getMaxHeight() - minY;
        } else {
            minY = Math.max(world.getMinHeight(), surface - 2);
            height = cfg.displayHeight();
        }
        float translationY = minY - originY;
        float aboveOrigin = Math.max(2f, minY + height - originY);

        // Every panel sits INSET inside its own chunk. Panels along X run the full (inset) length; panels along Z
        // are shortened where a north/south panel occupies the corner. Result: no two panels share a face plane,
        // so translucent faces never z-fight, and facing panels of two different claims stay 2*INSET apart.
        int bx = edge.chunk().minBlockX();
        int bz = edge.chunk().minBlockZ();
        double x;
        double z;
        Vector3f scale;
        if (edge.alongX()) {
            x = bx + INSET;
            z = edge.side() == BoundaryEdge.Side.NORTH ? bz + INSET : bz + 16 - INSET - thickness;
            scale = new Vector3f(16f - 2 * INSET, height, thickness);
        } else {
            x = edge.side() == BoundaryEdge.Side.WEST ? bx + INSET : bx + 16 - INSET - thickness;
            float zStart = bz + INSET;
            float zEnd = bz + 16 - INSET;
            if (chunkEdges.contains(new BoundaryEdge(edge.chunk(), BoundaryEdge.Side.NORTH))) {
                zStart += thickness + INSET;
            }
            if (chunkEdges.contains(new BoundaryEdge(edge.chunk(), BoundaryEdge.Side.SOUTH))) {
                zEnd -= thickness + INSET;
            }
            z = zStart;
            scale = new Vector3f(thickness, height, zEnd - zStart);
        }

        Location loc = new Location(world, x, originY, z);
        BlockDisplay display = world.spawn(loc, BlockDisplay.class);
        display.setBlock(cfg.displayMaterial().createBlockData());
        display.setTransformation(new Transformation(new Vector3f(0f, translationY, 0f), new Quaternionf(), scale, new Quaternionf()));
        display.setBrightness(new Display.Brightness(15, 15));
        display.setDisplayWidth(32f);
        display.setDisplayHeight(aboveOrigin + 1f);
        // The client draws a display entity only within viewRange * 64 blocks of its origin, measured in 3D.
        display.setViewRange(Math.max(2f, Math.min(16f, (aboveOrigin + 128f) / 64f)));
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
    public String debug(Player viewer) {
        int alive = 0;
        int near = 0;
        Location at = viewer.getLocation();
        double r2 = (double) radius() * radius();
        for (Map.Entry<BoundaryEdge, UUID> entry : entities.entrySet()) {
            Entity e = Bukkit.getEntity(entry.getValue());
            if (e == null) {
                continue;
            }
            alive++;
            if (e.getWorld().equals(at.getWorld()) && entry.getKey().distanceSquared(at.getX(), at.getZ()) <= r2) {
                near++;
            }
        }
        Set<UUID> shown = shownNear.get(viewer.getUniqueId());
        return "panels tracked=" + entities.size() + " alive=" + alive + " within " + radius() + " blocks=" + near
                + " shown-to-you=" + (shown == null ? 0 : shown.size()) + " reveal=" + plugin.settings().displayReveal();
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
