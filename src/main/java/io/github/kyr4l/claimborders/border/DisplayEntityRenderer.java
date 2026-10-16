package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.claim.ChunkKey;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import io.github.kyr4l.claimborders.claim.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
 * Translucent display-entity panels along claim borders: a "force field" wall with no per-player packet
 * work beyond show/hide. Every straight run of boundary (up to {@link #MAX_RUN} chunks) is one panel per
 * look (member, visitor, outsider) at the same place; each player is shown only the one that applies to
 * them. BLOCK style scales glass panes, TEXT style stretches text-display background quads (free colour
 * and opacity). Entities are non-persistent and tagged, so a crash or a PlugManX unload never leaves
 * garbage behind. Needs Minecraft 1.19.4 or newer.
 *
 * <p>Client rendering rules this code is built around:
 * <ul>
 *   <li>The client frustum-tests a display entity by a box centred on its origin and extending upward,
 *       so the box is sized to cover the whole wall and the origin sits near the lowest terrain along the
 *       run (border.display.culling-box: false drops the box; the client then never frustum-culls it).</li>
 *   <li>It is only drawn if the chunk section holding the entity's position is built, so the origin is
 *       never placed below the built section at its column.</li>
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
     * at each end so corners close without any two faces sharing a plane. {@code shifted} alternates along
     * runs cut by MAX_RUN: a shifted run sits 2*INSET further inside the chunk and overlaps its neighbours
     * by 2*INSET, which closes the seam without any coplanar faces.
     */
    private record Run(UUID owner, UUID world, BoundaryEdge.Side side, int line, int from, int to, End start, End end,
                       boolean shifted) {

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

    /**
     * The entities of one run: the panel(s) per look (member, visitor, outsider). Looks that share a
     * material or colour share the entities. TEXT style uses two entities per look, one per facing.
     */
    private record Panels(List<UUID> own, List<UUID> visitor, List<UUID> foreign) {
        Set<UUID> all() {
            Set<UUID> all = new LinkedHashSet<>(own);
            all.addAll(visitor);
            all.addAll(foreign);
            return all;
        }

        List<UUID> forLevel(TrustLevel level) {
            return switch (level) {
                case MEMBER -> own;
                case VISITOR -> visitor;
                case NONE -> foreign;
            };
        }
    }

    /**
     * A text display with a single space renders a background quad of (space width 4 + 2) x (line 10 + 1)
     * font pixels at 0.025 blocks per pixel, centred horizontally on the origin and rising from it.
     * Used to map that quad onto a panel. Single-sided, so two are spawned, one per facing.
     */
    private static final double TEXT_QUAD_X0 = -2 * 0.025;
    private static final double TEXT_QUAD_X1 = 3 * 0.025;
    private static final double TEXT_QUAD_H = 11 * 0.025;

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
                wanted.addAll(panels.forLevel(view.level()));
            }
        }
        Set<UUID> shown = shownNear.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
        for (UUID id : new ArrayList<>(shown)) {
            if (!wanted.contains(id)) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    hide(viewer, e);
                }
                shown.remove(id);
            }
        }
        for (UUID id : wanted) {
            if (shown.add(id)) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    show(viewer, e);
                }
            }
        }
    }

    /**
     * Re-assert visibility for every panel. NEAR: everything not in the viewer's shown set is hidden.
     * ALWAYS: the per-level rule is re-applied. Hiding a hidden entity (or showing a visible one) sends no
     * packet, so this is cheap and heals any drift (reconnects, missed join-time calls, other plugins).
     */
    @Override
    public void reconcile(Player viewer) {
        if (plugin.settings().displayReveal() == ClaimConfig.RevealMode.NEAR) {
            Set<UUID> shown = shownNear.getOrDefault(viewer.getUniqueId(), Set.of());
            for (Panels panels : entities.values()) {
                for (UUID id : panels.all()) {
                    if (!shown.contains(id)) {
                        Entity e = Bukkit.getEntity(id);
                        if (e != null) {
                            hide(viewer, e);
                        }
                    }
                }
            }
        } else {
            onJoin(viewer);
        }
    }

    /**
     * Bukkit throws if a plugin that is disabling calls hide/showEntity. While disabling we only remove the
     * entities, which clears every client anyway, so these become no-ops.
     */
    private void hide(Player player, Entity e) {
        if (plugin.isEnabled() && player.isOnline()) {
            player.hideEntity(plugin, e);
        }
    }

    private void show(Player player, Entity e) {
        if (plugin.isEnabled() && player.isOnline()) {
            player.showEntity(plugin, e);
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
                hide(viewer, e);
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
            if (claim != null && service.relationOf(viewer, claim) == TrustLevel.MEMBER) {
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

    /** Decides which colour (if any) this player sees. In NEAR mode everything starts hidden. */
    private void applyVisibility(Player player, Panels panels, Claim claim) {
        List<UUID> visible = List.of();
        if (plugin.settings().displayReveal() == ClaimConfig.RevealMode.ALWAYS) {
            TrustLevel level = service.relationOf(player, claim);
            boolean show = level != TrustLevel.MEMBER
                    || !plugin.settings().displayHideFromTrusted() || service.isShowing(player);
            if (show) {
                visible = panels.forLevel(level);
            }
        }
        for (UUID id : panels.all()) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) {
                continue;
            }
            if (visible.contains(id)) {
                show(player, e);
            } else {
                hide(player, e);
            }
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
            for (int i = 0; i < segments.size(); i++) {
                int[] seg = segments.get(i);
                ChunkKey first = alongX ? new ChunkKey(g.world(), seg[0], g.line()) : new ChunkKey(g.world(), g.line(), seg[0]);
                ChunkKey last = alongX ? new ChunkKey(g.world(), seg[1], g.line()) : new ChunkKey(g.world(), g.line(), seg[1]);
                int dx = alongX ? 1 : 0;
                int dz = alongX ? 0 : 1;
                End startKind = endKind(claim, first.offset(-dx, -dz), g.side());
                End endKind = endKind(claim, last.offset(dx, dz), g.side());
                runs.add(new Run(claim.getOwner(), g.world(), g.side(), g.line(), seg[0], seg[1], startKind, endKind, i % 2 == 1));
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
        // Runs cut by MAX_RUN overlap their neighbour by 2*INSET, and every other one sits 2*INSET deeper, so
        // the seam is a continuous wall with a 2*INSET step and still no shared planes (checked for all corner
        // combinations: every end cap stays at least INSET from every face it could coincide with).
        // TEXT style panels are flat quads: a depth step between overlapping neighbours would show as a slit at
        // grazing angles, so they stay in one plane and overlap by a hair instead (sub-pixel z-fight only).
        boolean flat = cfg.displayStyle() == ClaimConfig.DisplayStyle.TEXT;
        int lineBlock = run.line() << 4;
        int fromBlock = run.from() << 4;
        int toBlock = (run.to() + 1) << 4;
        float shift = run.shifted() && !flat ? 2 * INSET : 0f;
        float splitOverlap = flat ? 0.002f : INSET;
        double x0;
        double z0;
        double lenX;
        double lenZ;
        if (run.alongX()) {
            z0 = run.side() == BoundaryEdge.Side.NORTH ? lineBlock + INSET + shift : lineBlock + 16 - INSET - t - shift;
            lenZ = t;
            double xs = fromBlock + switch (run.start()) {
                case INNER -> -(3 * INSET + t);
                case CONVEX -> INSET;
                case SPLIT -> -splitOverlap;
            };
            double xe = toBlock + switch (run.end()) {
                case INNER -> 3 * INSET + t;
                case CONVEX -> -INSET;
                case SPLIT -> splitOverlap;
            };
            x0 = xs;
            lenX = xe - xs;
        } else {
            x0 = run.side() == BoundaryEdge.Side.WEST ? lineBlock + 2 * INSET + shift : lineBlock + 16 - 2 * INSET - t - shift;
            lenX = t;
            double zs = fromBlock + switch (run.start()) {
                case INNER -> -(2 * INSET + t);
                case CONVEX -> 2 * INSET;
                case SPLIT -> -splitOverlap;
            };
            double ze = toBlock + switch (run.end()) {
                case INNER -> 2 * INSET + t;
                case CONVEX -> -2 * INSET;
                case SPLIT -> splitOverlap;
            };
            z0 = zs;
            lenZ = ze - zs;
        }

        // Terrain along the run (loaded chunks only; never force a chunk load from here).
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

        // Origin: inside the panel's footprint, in the middle chunk. Vertically as low as possible so the
        // culling box (which starts at the origin and extends upward) covers the wall down into valleys, but
        // never below the chunk section that holds the air above the terrain here, which is the section the
        // client has built; an origin in an unbuilt section is never drawn.
        double ox = run.alongX() ? originChunk.minBlockX() + 8 : x0 + t / 2;
        double oz = run.alongX() ? z0 + t / 2 : originChunk.minBlockZ() + 8;
        int airHere = world.getHighestBlockYAt((int) Math.floor(ox), (int) Math.floor(oz)) + 1;
        int originY = Math.max(minSurface + 1, airHere & ~15);
        originY = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 2, originY));

        // Vertical extent.
        int minY;
        int height;
        if (cfg.displayHeight() < 0) {
            minY = world.getMinHeight();
            height = world.getMaxHeight() - minY;
        } else {
            minY = Math.max(world.getMinHeight(), minSurface - 2);
            height = Math.min(world.getMaxHeight() - minY, (maxSurface - minSurface) + cfg.displayHeight());
        }
        // Culling box: centred on the origin horizontally, from the origin up. Covers the whole panel.
        float cullWidth = 2f * (run.length() * 8f + 12f);
        float cullHeight = Math.max(1f, minY + height - originY + 1f);

        Location loc = new Location(world, ox, originY, oz);
        Claim claim = plugin.claims().getClaimOf(run.owner());
        Map<Object, List<UUID>> byLook = new HashMap<>();
        List<Object> looks = cfg.displayStyle() == ClaimConfig.DisplayStyle.TEXT
                ? List.of(cfg.displayColor(), cfg.displayVisitorColor(), cfg.displayForeignColor())
                : List.of(cfg.displayMaterial(), cfg.displayVisitorMaterial(), cfg.displayForeignMaterial());
        for (Object look : looks) {
            byLook.computeIfAbsent(look, l -> l instanceof Material m
                    ? List.of(spawnPanel(world, loc, run, m, x0, z0, lenX, lenZ, minY, height, cullWidth, cullHeight).getUniqueId())
                    : spawnTextPanel(world, loc, run, (Color) l, x0, z0, lenX, lenZ, minY, height, cullWidth, cullHeight));
        }
        Panels panels = new Panels(byLook.get(looks.get(0)), byLook.get(looks.get(1)), byLook.get(looks.get(2)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyVisibility(p, panels, claim);
        }
        entities.put(run, panels);
    }

    private BlockDisplay spawnPanel(World world, Location loc, Run run, Material material,
                                    double x0, double z0, double lenX, double lenZ, int minY, int height,
                                    float cullWidth, float cullHeight) {
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
            // With a culling box the client frustum-tests the whole panel (vanilla, Sodium and box-aware culling
            // mods all pass it). Without one the client's noCulling flag stays set and the panel is never
            // frustum-culled, but position-based culling mods then test a single point. Configurable for testing.
            if (cfg.displayCullingBox()) {
                d.setDisplayWidth(cullWidth);
                d.setDisplayHeight(cullHeight);
            }
            // View range maxed: 16 * 64 blocks from the origin.
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
                hide(p, d);
            }
        });
    }

    /**
     * TEXT style: two text displays (one per facing) whose background quad is stretched over the panel.
     * The panel is centred within the thickness band so it lines up with the BLOCK geometry.
     */
    private List<UUID> spawnTextPanel(World world, Location loc, Run run, Color color,
                                      double x0, double z0, double lenX, double lenZ, int minY, int height,
                                      float cullWidth, float cullHeight) {
        ClaimConfig cfg = plugin.settings();
        int alpha = (int) Math.round(cfg.displayOpacity() * 255);
        Color argb = Color.fromARGB(alpha, color.getRed(), color.getGreen(), color.getBlue());
        boolean alongX = run.alongX();
        double length = alongX ? lenX : lenZ;
        float sx = (float) (length / (TEXT_QUAD_X1 - TEXT_QUAD_X0));
        float sy = (float) (height / TEXT_QUAD_H);
        double planeX = x0 + lenX / 2;
        double planeZ = z0 + lenZ / 2;
        List<UUID> ids = new ArrayList<>(2);
        for (int facing = 0; facing < 2; facing++) {
            Quaternionf rot;
            double tx;
            double tz;
            if (alongX) {
                // quad in the XY plane; facing 1 is turned 180 degrees so its front looks the other way
                rot = new Quaternionf().rotationY(facing == 0 ? 0f : (float) Math.PI);
                tx = facing == 0 ? x0 - loc.getX() - TEXT_QUAD_X0 * sx : x0 - loc.getX() + TEXT_QUAD_X1 * sx;
                tz = planeZ - loc.getZ();
            } else {
                // quad turned into the ZY plane
                rot = new Quaternionf().rotationY(facing == 0 ? (float) (Math.PI / 2) : (float) (-Math.PI / 2));
                tx = planeX - loc.getX();
                tz = facing == 0 ? z0 - loc.getZ() + TEXT_QUAD_X1 * sx : z0 - loc.getZ() - TEXT_QUAD_X0 * sx;
            }
            Vector3f translation = new Vector3f((float) tx, (float) (minY - loc.getY()), (float) tz);
            Vector3f scale = new Vector3f(sx, sy, 1f);
            TextDisplay d = world.spawn(loc, TextDisplay.class, (java.util.function.Consumer<TextDisplay>) t -> {
                t.setText(" ");
                t.setBackgroundColor(argb);
                t.setDefaultBackground(false);
                t.setSeeThrough(cfg.displayTextSeeThrough());
                t.setShadowed(false);
                t.setBillboard(Display.Billboard.FIXED);
                t.setTransformation(new Transformation(translation, rot, scale, new Quaternionf()));
                t.setBrightness(new Display.Brightness(15, 15));
                if (cfg.displayCullingBox()) {
                    t.setDisplayWidth(cullWidth);
                    t.setDisplayHeight(cullHeight);
                }
                t.setViewRange(16f);
                t.setPersistent(false);
                t.setInvulnerable(true);
                t.setSilent(true);
                t.setGravity(false);
                t.getPersistentDataContainer().set(tag, PersistentDataType.STRING,
                        run.originChunk().serialize() + ";" + run.side() + ";" + run.length());
                for (Player p : Bukkit.getOnlinePlayers()) {
                    hide(p, t);
                }
            });
            ids.add(d.getUniqueId());
        }
        return ids;
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
            for (Display d : world.getEntitiesByClass(Display.class)) {
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
