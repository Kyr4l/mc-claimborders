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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
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
 * Translucent BlockDisplay panels along claim borders: a "force field" wall with no per-player packet
 * work beyond show/hide. Every straight run of boundary (up to {@link #MAX_RUN} chunks) is one panel in
 * the member colour plus one in the outsider colour at the same place; each player is shown only the one
 * that applies to them. Entities are non-persistent and tagged, so a crash or a PlugManX unload never
 * leaves garbage behind. Needs Minecraft 1.19.4 or newer.
 *
 * <p>Client rendering rules this code is built around:
 * <ul>
 *   <li>A display entity with no culling width/height is never frustum-culled (vanilla and Sodium both
 *       honour that flag), so the panel can be any size without blinking at the edges of the view.</li>
 *   <li>It is still only drawn if the chunk section holding the entity's position is built, so the origin
 *       sits at terrain level in the middle chunk of the run, never underground.</li>
 *   <li>It is only drawn within viewRange * 64 blocks of that origin, so the view range is maxed out.</li>
 *   <li>The server only sends it to players within the entity tracking range of the origin; with the origin
 *       in the middle of a run of at most MAX_RUN chunks, no point of the panel is far from it.</li>
 * </ul>
 */
public final class DisplayEntityRenderer implements BorderRenderer {

    /** Longest panel, in chunks. The panel lives in its middle chunk, which must be loaded for it to exist. */
    private static final int MAX_RUN = 8;

    /** Gap between a panel and the chunk line, and between panels. Keeps faces off each other's planes. */
    private static final float INSET = 0.025f;

    /** How a run ends: at an outer corner, at an inner corner, or cut by MAX_RUN with the border continuing. */
    private enum End { CONVEX, INNER, SPLIT }

    /**
     * One straight wall. {@code line} is the chunk row (north/south) or column (west/east) it runs along;
     * {@code from..to} are the chunk coordinates along it. The end kinds decide how far the panel reaches
     * at each end so corners close without any two faces sharing a plane.
     */
    private record Run(UUID owner, UUID world, BoundaryEdge.Side side, int line, int from, int to, End start, End end) {

        boolean alongX() {
            return side == BoundaryEdge.Side.NORTH || side == BoundaryEdge.Side.SOUTH;
        }

        ChunkKey chunkAt(int coord) {
            return alongX() ? new ChunkKey(world, coord, line) : new ChunkKey(world, line, coord);
        }

        int length() {
            return to - from + 1;
        }

        /** The chunk the entities live in: the middle of the run. */
        ChunkKey originChunk() {
            return chunkAt(from + length() / 2);
        }
    }

    /** The entities of one run: the member-coloured panel and the outsider-coloured one. */
    private record Panels(UUID own, UUID foreign) {
        List<UUID> all() {
            return own.equals(foreign) ? List.of(own) : List.of(own, foreign);
        }
    }

    private final ClaimBordersPlugin plugin;
    private final BorderService service;
    private final NamespacedKey tag;
    private final Map<Run, Panels> entities = new HashMap<>();
    private final Map<UUID, Set<Run>> runsByOwner = new HashMap<>();
    private final Map<BoundaryEdge, Run> runByEdge = new HashMap<>();
    private final Map<UUID, Set<UUID>> shownNear = new HashMap<>();
    private BukkitTask healTask;

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
        // Panels killed by /kill @e, clear-lag plugins or missed chunk events come back within a few minutes.
        healTask = Bukkit.getScheduler().runTaskTimer(plugin, this::heal, 20L * 60, 20L * 60 * 3);
    }

    private void heal() {
        for (Set<Run> runs : runsByOwner.values()) {
            for (Run run : runs) {
                if (!alive(run) && run.originChunk().isLoaded()) {
                    despawnRun(run);
                    spawnRun(run);
                }
            }
        }
    }

    private boolean alive(Run run) {
        Panels panels = entities.get(run);
        if (panels == null) {
            return false;
        }
        for (UUID id : panels.all()) {
            if (Bukkit.getEntity(id) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean showsTrustedByDefault() {
        return !plugin.settings().displayHideFromTrusted();
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
            Panels panels = run == null ? null : entities.get(run);
            if (panels != null) {
                wanted.add(view.trusted() ? panels.own() : panels.foreign());
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

    // ---- ALWAYS reveal: members see their colour, outsiders theirs ------------

    @Override
    public void onShowToggle(Player viewer, boolean showing) {
        if (plugin.settings().displayReveal() != ClaimConfig.RevealMode.ALWAYS) {
            return;
        }
        for (Map.Entry<Run, Panels> entry : entities.entrySet()) {
            Claim claim = plugin.claims().getClaimOf(entry.getKey().owner());
            if (claim != null && claim.isTrusted(viewer)) {
                applyVisibility(viewer, entry.getValue(), claim);
            }
        }
    }

    @Override
    public void onJoin(Player player) {
        for (Map.Entry<Run, Panels> entry : entities.entrySet()) {
            applyVisibility(player, entry.getValue(), plugin.claims().getClaimOf(entry.getKey().owner()));
        }
    }

    private void applyVisibility(Player player, Panels panels, Claim claim) {
        Entity own = Bukkit.getEntity(panels.own());
        Entity foreign = Bukkit.getEntity(panels.foreign());
        if (own == null || foreign == null) {
            return;
        }
        applyVisibility(player, own, foreign, claim);
    }

    /** Decides which of the two panels (if any) this player sees. In NEAR mode both start hidden. */
    private void applyVisibility(Player player, Entity own, Entity foreign, Claim claim) {
        boolean showOwn = false;
        boolean showForeign = false;
        if (plugin.settings().displayReveal() == ClaimConfig.RevealMode.ALWAYS) {
            boolean trusted = claim != null && claim.isTrusted(player);
            if (trusted) {
                showOwn = !plugin.settings().displayHideFromTrusted() || service.isShowing(player);
            } else {
                showForeign = true;
            }
        }
        if (own == foreign) {
            toggle(player, own, showOwn || showForeign);
        } else {
            toggle(player, own, showOwn);
            toggle(player, foreign, showForeign);
        }
    }

    private void toggle(Player player, Entity e, boolean visible) {
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
        // Spawn a tick later: during ChunkLoadEvent the chunk's entity storage may not be ready yet.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Run run : runsByOwner.getOrDefault(claim.getOwner(), Set.of())) {
                if (run.originChunk().equals(key) && !alive(run)) {
                    despawnRun(run);
                    spawnRun(run);
                }
            }
        });
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
                ChunkKey first = alongX ? new ChunkKey(g.world(), seg[0], g.line()) : new ChunkKey(g.world(), g.line(), seg[0]);
                ChunkKey last = alongX ? new ChunkKey(g.world(), seg[1], g.line()) : new ChunkKey(g.world(), g.line(), seg[1]);
                int dx = alongX ? 1 : 0;
                int dz = alongX ? 0 : 1;
                End startKind = endKind(claim, first.offset(-dx, -dz), g.side());
                End endKind = endKind(claim, last.offset(dx, dz), g.side());
                runs.add(new Run(claim.getOwner(), g.world(), g.side(), g.line(), seg[0], seg[1], startKind, endKind));
            }
        }
        return runs;
    }

    /** {@code next} is the chunk just past the run's end along its direction. */
    private static End endKind(Claim claim, ChunkKey next, BoundaryEdge.Side side) {
        if (!claim.contains(next)) {
            return End.CONVEX;              // the border turns toward the claim: outer corner
        }
        if (!claim.contains(next.offset(side.dx, side.dz))) {
            return End.SPLIT;               // the border goes straight on; this run was cut by MAX_RUN
        }
        return End.INNER;                   // the border turns away from the claim: inner corner
    }

    private void spawnRun(Run run) {
        World world = Bukkit.getWorld(run.world());
        ChunkKey originChunk = run.originChunk();
        if (world == null || !originChunk.isLoaded()) {
            return;
        }
        ClaimConfig cfg = plugin.settings();
        float t = cfg.displayThickness();

        // Geometry. Faces must never share a plane (translucent z-fighting), and corners must be sealed.
        // North/south panels sit INSET inside the chunk line, west/east panels 2*INSET: they cross each other at
        // corners with every face at least INSET apart. At an outer corner the north/south panel stops INSET
        // short and the west/east one runs through it; at an inner corner both run through each other.
        int lineBlock = run.line() << 4;
        int fromBlock = run.from() << 4;
        int toBlock = (run.to() + 1) << 4;
        double x0;
        double z0;
        double lenX;
        double lenZ;
        if (run.alongX()) {
            z0 = run.side() == BoundaryEdge.Side.NORTH ? lineBlock + INSET : lineBlock + 16 - INSET - t;
            lenZ = t;
            double xs = fromBlock + (run.start() == End.INNER ? -(3 * INSET + t) : INSET);
            double xe = toBlock + (run.end() == End.INNER ? 3 * INSET + t : -INSET);
            x0 = xs;
            lenX = xe - xs;
        } else {
            x0 = run.side() == BoundaryEdge.Side.WEST ? lineBlock + 2 * INSET : lineBlock + 16 - 2 * INSET - t;
            lenX = t;
            double zs = fromBlock + switch (run.start()) {
                case INNER -> -(2 * INSET + t);
                case CONVEX -> 2 * INSET;
                case SPLIT -> INSET;
            };
            double ze = toBlock + switch (run.end()) {
                case INNER -> 2 * INSET + t;
                case CONVEX -> -2 * INSET;
                case SPLIT -> -INSET;
            };
            z0 = zs;
            lenZ = ze - zs;
        }

        // Origin: inside the panel's footprint, in the middle chunk, one block above the terrain there.
        double ox = run.alongX() ? originChunk.minBlockX() + 8 : x0 + t / 2;
        double oz = run.alongX() ? z0 + t / 2 : originChunk.minBlockZ() + 8;
        int originY = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt((int) Math.floor(ox), (int) Math.floor(oz)) + 1);

        // Vertical extent.
        int minY;
        int height;
        if (cfg.displayHeight() < 0) {
            minY = world.getMinHeight();
            height = world.getMaxHeight() - minY;
        } else {
            int minSurface = Integer.MAX_VALUE;
            int maxSurface = Integer.MIN_VALUE;
            for (int c = run.from(); c <= run.to(); c++) {
                ChunkKey chunk = run.chunkAt(c);
                if (!chunk.isLoaded()) {
                    continue;   // never force a chunk load from here
                }
                BoundaryEdge edge = new BoundaryEdge(chunk, run.side());
                for (int i = 0; i < 16; i++) {
                    int y = world.getHighestBlockYAt(edge.innerBlockX(i), edge.innerBlockZ(i));
                    minSurface = Math.min(minSurface, y);
                    maxSurface = Math.max(maxSurface, y);
                }
            }
            minY = Math.max(world.getMinHeight(), minSurface - 2);
            height = Math.min(world.getMaxHeight() - minY, (maxSurface - minSurface) + cfg.displayHeight());
        }

        Location loc = new Location(world, ox, originY, oz);
        Claim claim = plugin.claims().getClaimOf(run.owner());
        Material ownMat = cfg.displayMaterial();
        Material foreignMat = cfg.displayForeignMaterial();
        BlockDisplay own = spawnPanel(world, loc, run, ownMat, x0, z0, lenX, lenZ, minY, height);
        BlockDisplay foreign = foreignMat == ownMat ? own
                : spawnPanel(world, loc, run, foreignMat, x0, z0, lenX, lenZ, minY, height);
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyVisibility(p, own, foreign, claim);
        }
        entities.put(run, new Panels(own.getUniqueId(), foreign.getUniqueId()));
    }

    private BlockDisplay spawnPanel(World world, Location loc, Run run, Material material,
                                    double x0, double z0, double lenX, double lenZ, int minY, int height) {
        // A glass pane's centre post is 2/16 wide and uses only the uniform middle strip of the texture, so a
        // scaled pane is a flat tint with no stretched frame. Map the post [7/16, 9/16] onto the panel box.
        boolean pane = material.createBlockData() instanceof GlassPane;
        Vector3f scale = pane
                ? new Vector3f((float) (lenX * 8), height, (float) (lenZ * 8))
                : new Vector3f((float) lenX, height, (float) lenZ);
        Vector3f translation = new Vector3f(
                (float) (x0 - loc.getX() - (pane ? 3.5 * lenX : 0)),
                (float) (minY - loc.getY()),
                (float) (z0 - loc.getZ() - (pane ? 3.5 * lenZ : 0)));
        ClaimConfig cfg = plugin.settings();
        // Configure before the entity is added to the world: one spawn packet with the final state, and the
        // per-player hide decisions are applied right after, before the next tick sends anything.
        return world.spawn(loc, BlockDisplay.class, (java.util.function.Consumer<BlockDisplay>) d -> {
            d.setBlock(material.createBlockData());
            d.setTransformation(new Transformation(translation, new Quaternionf(), scale, new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15));
            // No culling width/height on purpose: that leaves the client's noCulling flag set, so the panel is
            // never frustum-culled by its (origin-anchored) box. View range maxed: 16 * 64 blocks from the origin.
            d.setViewRange(16f);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setSilent(true);
            d.setGravity(false);
            if (cfg.displayGlow()) {
                d.setGlowing(true);
                d.setGlowColorOverride(cfg.displayGlowColor());
            }
            d.getPersistentDataContainer().set(tag, PersistentDataType.STRING,
                    run.originChunk().serialize() + ";" + run.side() + ";" + run.length());
            // Hidden from everyone until applyVisibility decides; avoids a flash for players who should not see it.
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideEntity(plugin, d);
            }
        });
    }

    private void despawnRun(Run run) {
        Panels panels = entities.remove(run);
        if (panels == null) {
            return;
        }
        for (UUID id : panels.all()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
            for (Set<UUID> shown : shownNear.values()) {
                shown.remove(id);
            }
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
        for (Run run : entities.keySet()) {
            if (alive(run)) {
                alive++;
                if (nearRuns.contains(run)) {
                    near++;
                }
            }
        }
        Set<UUID> shown = shownNear.get(viewer.getUniqueId());
        return "runs=" + entities.size() + " alive=" + alive + " edges-covered=" + runByEdge.size()
                + " within " + radius() + " blocks=" + near + " shown-to-you=" + (shown == null ? 0 : shown.size())
                + " reveal=" + plugin.settings().displayReveal();
    }

    @Override
    public void shutdown() {
        if (healTask != null) {
            healTask.cancel();
            healTask = null;
        }
        for (Run run : new ArrayList<>(entities.keySet())) {
            despawnRun(run);
        }
        entities.clear();
        runsByOwner.clear();
        runByEdge.clear();
        shownNear.clear();
        sweepLeftovers();
    }
}
