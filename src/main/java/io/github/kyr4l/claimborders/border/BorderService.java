package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.BoundaryCalculator;
import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.claim.ChunkKey;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import io.github.kyr4l.claimborders.claim.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Drives the active {@link BorderRenderer}: collects the edges near each player every few ticks,
 * tracks who toggled /claim show, and forwards world events.
 */
public final class BorderService implements Listener {

    private final ClaimBordersPlugin plugin;
    private final Set<UUID> showing;
    /** /claim view: pretend to be this level in every claim (visual only). */
    private final Map<UUID, TrustLevel> viewAs = new HashMap<>();
    private BorderRenderer renderer;
    private BukkitTask task;
    private final Consumer<ClaimManager.ClaimChange> claimListener = change -> {
        if (renderer != null) {
            renderer.onClaimChange(change);
        }
    };

    public BorderService(ClaimBordersPlugin plugin, Set<UUID> showing) {
        this.plugin = plugin;
        this.showing = showing;
    }

    public void start() {
        ClaimConfig cfg = plugin.settings();
        renderer = createRenderer(cfg);
        renderer.start();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.claims().addListener(claimListener);
        if (renderer.needsTick()) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, cfg.intervalTicks(), cfg.intervalTicks());
        }
        plugin.getLogger().info("Border renderer: " + renderer.getClass().getSimpleName());
    }

    public void shutdown() {
        plugin.claims().removeListener(claimListener);
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        if (renderer != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                renderer.clear(p);
            }
            renderer.shutdown();
            renderer = null;
        }
    }

    private BorderRenderer createRenderer(ClaimConfig cfg) {
        ClaimConfig.RendererType type = cfg.renderer();
        if (type == ClaimConfig.RendererType.DISPLAY && !displayEntitiesAvailable()) {
            plugin.getLogger().warning("This server has no BlockDisplay entities (needs 1.19.4+). Falling back to PARTICLES.");
            type = ClaimConfig.RendererType.PARTICLES;
        }
        return switch (type) {
            case PARTICLES -> new ParticleRenderer(plugin);
            case GHOST_BLOCKS -> new GhostBlockRenderer(plugin);
            case DISPLAY -> new DisplayEntityRenderer(plugin, this);
            case NONE -> new NoneRenderer();
        };
    }

    private static boolean displayEntitiesAvailable() {
        try {
            Class.forName("org.bukkit.entity.BlockDisplay");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    // ---- show toggle -----------------------------------------------------

    public boolean isShowing(Player player) {
        return showing.contains(player.getUniqueId()) != plugin.settings().showOwnByDefault();
    }

    /** Flips the toggle and returns the new state. */
    public boolean toggle(Player player) {
        if (!showing.remove(player.getUniqueId())) {
            showing.add(player.getUniqueId());
        }
        boolean now = isShowing(player);
        if (renderer != null) {
            renderer.onShowToggle(player, now);
            if (!now) {
                renderer.clear(player);
            }
        }
        return now;
    }

    /** The viewer's standing in a claim for rendering purposes: the /claim view override, else the real one. */
    public TrustLevel relationOf(Player player, Claim claim) {
        TrustLevel override = viewAs.get(player.getUniqueId());
        if (override != null) {
            return override;
        }
        return claim == null ? TrustLevel.NONE : claim.levelOf(player);
    }

    public TrustLevel getViewAs(Player player) {
        return viewAs.get(player.getUniqueId());
    }

    /** Sets or clears (null) the preview level and re-renders for the player. */
    public void setViewAs(Player player, TrustLevel level) {
        if (level == null) {
            viewAs.remove(player.getUniqueId());
        } else {
            viewAs.put(player.getUniqueId(), level);
        }
        refresh(player);
    }

    /** Re-applies what this player should see (trust changed, world changed...). */
    public void refresh(Player player) {
        if (renderer != null) {
            renderer.clear(player);
            renderer.onJoin(player);
        }
    }

    /** Diagnostics for /claim admin stats. */
    public String debug(Player player) {
        if (renderer == null) {
            return "renderer not running";
        }
        int radius = renderer.radius();
        List<EdgeView> edges = collectEdges(player, radius);
        long own = edges.stream().filter(EdgeView::trusted).count();
        return renderer.getClass().getSimpleName() + ": showing=" + isShowing(player) + ", edges within " + radius
                + " blocks=" + edges.size() + " (own " + own + "), " + renderer.debug(player);
    }

    // ---- tick ------------------------------------------------------------

    private void tick() {
        if (renderer == null) {
            return;
        }
        int radius = renderer.radius();
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<EdgeView> edges = collectEdges(player, radius);
            renderer.render(player, edges);
        }
    }

    /** Edges within {@code radius} blocks of the player that this player should see. */
    public List<EdgeView> collectEdges(Player player, int radius) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return List.of();
        }
        ClaimManager claims = plugin.claims();
        boolean showing = isShowing(player) || (renderer != null && renderer.showsTrustedByDefault());
        double px = loc.getX();
        double pz = loc.getZ();
        double r2 = (double) radius * radius;
        int minCx = (int) Math.floor(px - radius) >> 4;
        int maxCx = (int) Math.floor(px + radius) >> 4;
        int minCz = (int) Math.floor(pz - radius) >> 4;
        int maxCz = (int) Math.floor(pz + radius) >> 4;
        List<EdgeView> out = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ChunkKey key = ChunkKey.of(world, cx, cz);
                Claim claim = claims.getClaimAt(key);
                if (claim == null) {
                    continue;
                }
                TrustLevel level = relationOf(player, claim);
                if (level == TrustLevel.MEMBER && !showing) {
                    continue;
                }
                for (BoundaryEdge edge : BoundaryCalculator.edgesOf(claim, key)) {
                    if (edge.distanceSquared(px, pz) <= r2) {
                        out.add(new EdgeView(edge, claim, level));
                    }
                }
            }
        }
        return out;
    }

    // ---- events ----------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (renderer != null) {
            renderer.onJoin(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        showing.remove(e.getPlayer().getUniqueId());
        viewAs.remove(e.getPlayer().getUniqueId());
        plugin.forgetCooldown(e.getPlayer());
        if (renderer != null) {
            renderer.clear(e.getPlayer());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (renderer != null) {
            renderer.clear(e.getPlayer());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (renderer != null) {
            renderer.onChunkLoad(e.getChunk());
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (renderer != null) {
            renderer.onChunkUnload(e.getChunk());
        }
    }

    /** Renderer used when border.renderer is NONE. */
    private static final class NoneRenderer implements BorderRenderer {
        @Override
        public boolean needsTick() {
            return false;
        }

        @Override
        public int radius() {
            return 0;
        }

        @Override
        public void render(Player viewer, List<EdgeView> edges) {
        }

        @Override
        public void clear(Player viewer) {
        }

        @Override
        public void shutdown() {
        }
    }
}
