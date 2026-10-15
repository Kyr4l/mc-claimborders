package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.util.ParticleResolver;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player particles: horizontal lines at the viewer's height along each nearby edge,
 * plus vertical posts at chunk corners. Nothing is shown to players who are far away.
 */
public final class ParticleRenderer implements BorderRenderer {

    private record Corner(int x, int z) {
    }

    private record Line(int x1, int z1, int x2, int z2) {
    }

    private final ClaimBordersPlugin plugin;
    private final Particle particle;
    private final Object ownData;
    private final Object visitorData;
    private final Object foreignData;

    public ParticleRenderer(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
        ClaimConfig cfg = plugin.settings();
        Particle resolved = ParticleResolver.resolve(cfg.particleType(), null);
        if (resolved == null) {
            plugin.getLogger().warning("Unknown particle '" + cfg.particleType() + "', using DUST.");
            resolved = ParticleResolver.dustOrFallback();
        } else if (resolved.getDataType() != Void.class && resolved.getDataType() != Particle.DustOptions.class) {
            plugin.getLogger().warning("Particle " + resolved + " needs extra data; using DUST instead.");
            resolved = ParticleResolver.dustOrFallback();
        }
        this.particle = resolved;
        if (particle.getDataType() == Particle.DustOptions.class) {
            ownData = new Particle.DustOptions(cfg.particleColor(), cfg.particleSize());
            visitorData = new Particle.DustOptions(cfg.particleVisitorColor(), cfg.particleSize());
            foreignData = new Particle.DustOptions(cfg.particleForeignColor(), cfg.particleSize());
        } else {
            ownData = null;
            visitorData = null;
            foreignData = null;
        }
    }

    @Override
    public int radius() {
        return plugin.settings().borderRadius();
    }

    @Override
    public void render(Player viewer, List<EdgeView> edges) {
        if (edges.isEmpty()) {
            return;
        }
        ClaimConfig cfg = plugin.settings();
        Location loc = viewer.getLocation();
        double py = loc.getY();
        double step = cfg.particleStep();
        int span = cfg.particleVerticalSpan();
        Set<Corner> posts = new HashSet<>();

        // Two claims facing each other share one line; draw it once, in the colour of the lower standing.
        Map<Line, EdgeView> lines = new LinkedHashMap<>();
        for (EdgeView view : edges) {
            BoundaryEdge e = view.edge();
            lines.merge(new Line(e.x1(), e.z1(), e.x2(), e.z2()), view, (a, b) -> a.level().compareTo(b.level()) <= 0 ? a : b);
        }

        for (EdgeView view : lines.values()) {
            BoundaryEdge edge = view.edge();
            Object data = switch (view.level()) {
                case MEMBER -> ownData;
                case VISITOR -> visitorData;
                case NONE -> foreignData;
            };
            for (int layer : cfg.particleLayers()) {
                double y = Math.floor(py) + layer + 0.1;
                if (edge.alongX()) {
                    double z = edge.z1();
                    for (double x = edge.x1(); x <= edge.x2(); x += step) {
                        spawn(viewer, x, y, z, data);
                    }
                } else {
                    double x = edge.x1();
                    for (double z = edge.z1(); z <= edge.z2(); z += step) {
                        spawn(viewer, x, y, z, data);
                    }
                }
            }
            if (span > 0) {
                post(viewer, posts, edge.x1(), edge.z1(), py, span, data);
                post(viewer, posts, edge.x2(), edge.z2(), py, span, data);
            }
        }
    }

    private void post(Player viewer, Set<Corner> posts, int x, int z, double py, int span, Object data) {
        if (!posts.add(new Corner(x, z))) {
            return;
        }
        double base = Math.floor(py);
        for (int dy = -span; dy <= span; dy++) {
            spawn(viewer, x, base + dy + 0.5, z, data);
        }
    }

    private void spawn(Player viewer, double x, double y, double z, Object data) {
        viewer.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, data);
    }

    @Override
    public void clear(Player viewer) {
        // particles are fire-and-forget
    }

    @Override
    public void shutdown() {
    }
}
