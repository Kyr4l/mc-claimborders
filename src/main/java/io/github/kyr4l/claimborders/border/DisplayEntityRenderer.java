package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Translucent BlockDisplay panels along claim borders: a "force field" wall everyone sees, with no
 * per-player packet work. Every straight run of boundary (up to {@link #MAX_RUN} chunks) is one panel,
 * so walls have no seams; gaps exist only at corners where they are hidden by the perpendicular panel.
 * Entities are non-persistent and tagged, so a crash or a PlugManX unload never leaves garbage behind.
 * Needs Minecraft 1.19.4 or newer.
 */
public final class DisplayEntityRenderer implements BorderRenderer {

    /** Longest panel, in chunks. The panel lives in its first chunk, which must be loaded for it to exist. */
    private static final int MAX_RUN = 8;

    /** Gap between a panel and the chunk line, and between panels. Keeps faces off each other's planes. */
    private static final float INSET = 0.025f;

    /**
     * One straight panel. {@code line} is the chunk row (north/south) or column (west/east) it runs along;
     * {@code from..to} are the chunk coordinates along it. {@code shortenStart/End} are set on west/east
     * runs whose end chunk also has a north/south panel occupying the corner.
     */
    private record Run(UUID owner, UUID world, BoundaryEdge.Side side, int line, int from, int to,
                       boolean shortenStart, boolean shortenEnd) {

        boolean alongX() {
            return side == BoundaryEdge.Side.NORTH || side == BoundaryEdge.Side.SOUTH;
        }

        ChunkKey chunkAt(int coord) {
            return alongX() ? new ChunkKey(world, coord, line) : new ChunkKey(world, line, coord);
        }

        ChunkKey originChunk() {
            return chunkAt(from);
        }

        int length() {
            return to - from + 1;
        }
    }

    private final ClaimBordersPlugin plugin;
    private final BorderService service;
    private final NamespacedKey tag;
    private final Map<Run, UUID> entities = new HashMap<>();
    private final Map<UUID, Set<Run>> runsByOwner = new HashMap<>();
    private final Map<BoundaryEdge, Run> runByEdge = new HashMap<>();
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
            syncClaim(claim);
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
            Run run = runByEdge.get(view.edge());
            UUID id = run == null ? null : entities.get(run);
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
        for (Map.Entry<Run, UUID> entry : entities.entrySet()) {
            Claim claim = plugin.claims().getClaimOf(entry.getKey().owner());
            if (claim != null && claim.isTrusted(viewer)) {
                applyVisibility(viewer, entry.getValue(), claim);
            }
        }
    }

    @Override
    public void onJoin(Player player) {
        for (Map.Entry<Run, UUID> entry : entities.entrySet()) {
            applyVisibility(player, entry.getValue(), plugin.claims().getClaimOf(entry.getKey().owner()));
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

    // ---- keeping panels in sync with claims and chunk loading ---------------

    @Override
    public void onClaimChange(ClaimManager.ClaimChange change) {
        Set<Claim> affected = new LinkedHashSet<>();
        affected.add(change.claim());
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            Claim neighbour = plugin.claims().getClaimAt(change.chunk().offset(side.dx, side.dz));
            if (neighbour != null) {
                affected.add(neighbour);
            }
        }
        for (Claim claim : affected) {
            syncClaim(claim);
        }
    }

    @Override
    public void onChunkLoad(Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        Claim claim = plugin.claims().getClaimAt(key);
        if (claim == null) {
            return;
        }
        for (Run run : runsByOwner.getOrDefault(claim.getOwner(), Set.of())) {
            UUID id = entities.get(run);
            if (run.originChunk().equals(key) && (id == null || Bukkit.getEntity(id) == null)) {
                spawnRun(run);
            }
        }
    }

    @Override
    public void onChunkUnload(Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        Claim claim = plugin.claims().getClaimAt(key);
        if (claim == null) {
            return;
        }
        for (Run run : runsByOwner.getOrDefault(claim.getOwner(), Set.of())) {
            if (run.originChunk().equals(key)) {
                despawnRun(run);
            }
        }
    }

    /** Make the panels of this claim match its current perimeter. */
    private void syncClaim(Claim claim) {
        Set<Run> wanted = computeRuns(claim);
        Set<Run> current = runsByOwner.getOrDefault(claim.getOwner(), Set.of());
        for (Run run : new ArrayList<>(current)) {
            if (!wanted.contains(run)) {
                forgetRun(run);
            }
        }
        for (Run run : wanted) {
            if (!current.contains(run)) {
                registerRun(run);
            }
        }
        if (wanted.isEmpty()) {
            runsByOwner.remove(claim.getOwner());
        }
    }

    private void registerRun(Run run) {
        runsByOwner.computeIfAbsent(run.owner(), k -> new HashSet<>()).add(run);
        for (int c = run.from(); c <= run.to(); c++) {
            runByEdge.put(new BoundaryEdge(run.chunkAt(c), run.side()), run);
        }
        if (run.originChunk().isLoaded()) {
            spawnRun(run);
        }
    }

    private void forgetRun(Run run) {
        despawnRun(run);
        Set<Run> runs = runsByOwner.get(run.owner());
        if (runs != null) {
            runs.remove(run);
        }
        for (int c = run.from(); c <= run.to(); c++) {
            runByEdge.remove(new BoundaryEdge(run.chunkAt(c), run.side()), run);
        }
    }

    /** Groups the claim's boundary edges into straight runs. */
    private static Set<Run> computeRuns(Claim claim) {
        record Group(UUID world, BoundaryEdge.Side side, int line) {
        }
        Map<Group, TreeSet<Integer>> groups = new HashMap<>();
        for (ChunkKey chunk : claim.getChunks()) {
            for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
                if (claim.contains(chunk.offset(side.dx, side.dz))) {
                    continue;
                }
                boolean alongX = side == BoundaryEdge.Side.NORTH || side == BoundaryEdge.Side.SOUTH;
                int line = alongX ? chunk.z() : chunk.x();
                int coord = alongX ? chunk.x() : chunk.z();
                groups.computeIfAbsent(new Group(chunk.world(), side, line), k -> new TreeSet<>()).add(coord);
            }
        }
        Set<Run> runs = new HashSet<>();
        for (Map.Entry<Group, TreeSet<Integer>> entry : groups.entrySet()) {
            Group g = entry.getKey();
            boolean alongX = g.side() == BoundaryEdge.Side.NORTH || g.side() == BoundaryEdge.Side.SOUTH;
            Integer start = null;
            int previous = 0;
            List<int[]> segments = new ArrayList<>();
            for (int coord : entry.getValue()) {
                if (start == null) {
                    start = coord;
                } else if (coord != previous + 1 || coord - start + 1 > MAX_RUN) {
                    segments.add(new int[] {start, previous});
                    start = coord;
                }
                previous = coord;
            }
            if (start != null) {
                segments.add(new int[] {start, previous});
            }
            for (int[] seg : segments) {
                boolean shortenStart = false;
                boolean shortenEnd = false;
                if (!alongX) {
                    // A west/east panel yields the corner to the north/south panel of the same chunk.
                    shortenStart = !claim.contains(new ChunkKey(g.world(), g.line(), seg[0]).offset(0, -1));
                    shortenEnd = !claim.contains(new ChunkKey(g.world(), g.line(), seg[1]).offset(0, 1));
                }
                runs.add(new Run(claim.getOwner(), g.world(), g.side(), g.line(), seg[0], seg[1], shortenStart, shortenEnd));
            }
        }
        return runs;
    }

    private void spawnRun(Run run) {
        World world = Bukkit.getWorld(run.world());
        if (world == null || !run.originChunk().isLoaded()) {
            return;
        }
        ClaimConfig cfg = plugin.settings();
        float thickness = cfg.displayThickness();

        // Terrain along the run, sampled only in loaded chunks (never force a chunk load from here).
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        for (int c = run.from(); c <= run.to(); c++) {
            ChunkKey chunk = run.chunkAt(c);
            if (!chunk.isLoaded()) {
                continue;
            }
            BoundaryEdge edge = new BoundaryEdge(chunk, run.side());
            for (int i = 0; i < 16; i++) {
                int y = world.getHighestBlockYAt(edge.innerBlockX(i), edge.innerBlockZ(i));
                minSurface = Math.min(minSurface, y);
                maxSurface = Math.max(maxSurface, y);
            }
        }
        if (minSurface == Integer.MAX_VALUE) {
            return;
        }

        // Clients render an entity only if the chunk section holding its ORIGIN is built, and cull it with a box
        // that extends from the origin upward. So: origin a little under the lowest surface point, but never
        // below the section that holds that surface (which is visible, hence built); the model is shifted
        // down with a translation and the culling box covers everything above the origin.
        int top = minSurface + 1;
        int originY = Math.max(top - 3, top & ~15);
        originY = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 2, originY));
        int minY;
        int height;
        if (cfg.displayHeight() < 0) {
            minY = world.getMinHeight();
            height = world.getMaxHeight() - minY;
        } else {
            minY = Math.max(world.getMinHeight(), minSurface - 2);
            height = (maxSurface - minSurface) + cfg.displayHeight();
        }
        float translationY = minY - originY;
        float aboveOrigin = Math.max(2f, minY + height - originY);

        // Every panel sits INSET inside its own chunks. Panels along X run the full (inset) length; panels along Z
        // yield the corners to them. No two panels share a face plane, so translucent faces never z-fight, and
        // facing panels of two different claims stay 2*INSET apart.
        int lineBlock = run.line() << 4;
        int fromBlock = run.from() << 4;
        int toBlock = (run.to() + 1) << 4;
        double x;
        double z;
        Vector3f scale;
        if (run.alongX()) {
            x = fromBlock + INSET;
            z = run.side() == BoundaryEdge.Side.NORTH ? lineBlock + INSET : lineBlock + 16 - INSET - thickness;
            scale = new Vector3f(toBlock - fromBlock - 2 * INSET, height, thickness);
        } else {
            x = run.side() == BoundaryEdge.Side.WEST ? lineBlock + INSET : lineBlock + 16 - INSET - thickness;
            float zStart = fromBlock + INSET + (run.shortenStart() ? thickness + INSET : 0f);
            float zEnd = toBlock - INSET - (run.shortenEnd() ? thickness + INSET : 0f);
            z = zStart;
            scale = new Vector3f(thickness, height, zEnd - zStart);
        }

        Location loc = new Location(world, x, originY, z);
        BlockDisplay display = world.spawn(loc, BlockDisplay.class);
        display.setBlock(cfg.displayMaterial().createBlockData());
        display.setTransformation(new Transformation(new Vector3f(0f, translationY, 0f), new Quaternionf(), scale, new Quaternionf()));
        display.setBrightness(new Display.Brightness(15, 15));
        // Culling box is centred on the origin horizontally and starts at the origin vertically.
        display.setDisplayWidth(2f * run.length() * 16f + 4f);
        display.setDisplayHeight(aboveOrigin + 1f);
        // The client draws a display entity only within viewRange * 64 blocks of its origin, measured in 3D.
        display.setViewRange(Math.max(2f, Math.min(16f, (aboveOrigin + run.length() * 16f + 128f) / 64f)));
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setGravity(false);
        if (cfg.displayGlow()) {
            display.setGlowing(true);
            display.setGlowColorOverride(cfg.displayGlowColor());
        }
        display.getPersistentDataContainer().set(tag, PersistentDataType.STRING,
                run.originChunk().serialize() + ";" + run.side() + ";" + run.length());
        entities.put(run, display.getUniqueId());

        Claim claim = plugin.claims().getClaimOf(run.owner());
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyVisibility(p, display.getUniqueId(), claim);
        }
    }

    private void despawnRun(Run run) {
        UUID id = entities.remove(run);
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
        Set<Run> nearRuns = new HashSet<>();
        for (Map.Entry<BoundaryEdge, Run> entry : runByEdge.entrySet()) {
            if (entry.getKey().chunk().world().equals(at.getWorld().getUID())
                    && entry.getKey().distanceSquared(at.getX(), at.getZ()) <= r2) {
                nearRuns.add(entry.getValue());
            }
        }
        for (Map.Entry<Run, UUID> entry : entities.entrySet()) {
            if (Bukkit.getEntity(entry.getValue()) != null) {
                alive++;
                if (nearRuns.contains(entry.getKey())) {
                    near++;
                }
            }
        }
        Set<UUID> shown = shownNear.get(viewer.getUniqueId());
        return "panels=" + entities.size() + " alive=" + alive + " edges-covered=" + runByEdge.size()
                + " within " + radius() + " blocks=" + near + " shown-to-you=" + (shown == null ? 0 : shown.size())
                + " reveal=" + plugin.settings().displayReveal();
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
        runsByOwner.clear();
        runByEdge.clear();
        shownNear.clear();
        sweepLeftovers();
    }
}
