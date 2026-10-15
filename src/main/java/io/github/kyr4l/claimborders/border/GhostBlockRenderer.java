package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player fake blocks (client side only, nothing changes in the world).
 * Outsiders see a wall band around their own height along the border: it even collides client-side.
 * Trusted members with /claim show see a lit outline on the ground along their border.
 */
public final class GhostBlockRenderer implements BorderRenderer {

    private record BlockPos(int x, int y, int z) {
    }

    private static final class ViewerState {
        final UUID world;
        final Map<BlockPos, BlockData> shown;

        ViewerState(UUID world, Map<BlockPos, BlockData> shown) {
            this.world = world;
            this.shown = shown;
        }
    }

    private final ClaimBordersPlugin plugin;
    private final Map<UUID, ViewerState> viewers = new HashMap<>();
    private final BlockData wall;
    private final BlockData outline;

    public GhostBlockRenderer(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
        this.wall = plugin.settings().ghostWallMaterial().createBlockData();
        this.outline = plugin.settings().ghostOutlineMaterial().createBlockData();
    }

    @Override
    public int radius() {
        return plugin.settings().ghostRadius();
    }

    @Override
    public void render(Player viewer, List<EdgeView> edges) {
        World world = viewer.getWorld();
        ClaimConfig cfg = plugin.settings();
        int py = viewer.getLocation().getBlockY();
        Map<BlockPos, BlockData> desired = new HashMap<>();

        for (EdgeView view : edges) {
            BoundaryEdge edge = view.edge();
            if (!edge.chunk().isLoaded()) {
                continue;
            }
            for (int i = 0; i < 16; i++) {
                int bx = edge.innerBlockX(i);
                int bz = edge.innerBlockZ(i);
                if (view.trusted()) {
                    int y = surfaceY(world, bx, bz, py);
                    if (y != Integer.MIN_VALUE) {
                        desired.put(new BlockPos(bx, y, bz), outline);
                    }
                } else {
                    int minY = Math.max(world.getMinHeight(), py - cfg.ghostWallBelow());
                    int maxY = Math.min(world.getMaxHeight() - 1, py + cfg.ghostWallAbove());
                    for (int y = minY; y <= maxY; y++) {
                        if (world.getBlockAt(bx, y, bz).getType().isAir()) {
                            desired.put(new BlockPos(bx, y, bz), wall);
                        }
                    }
                }
            }
        }

        ViewerState state = viewers.get(viewer.getUniqueId());
        Map<BlockPos, BlockData> previous;
        if (state == null || !state.world.equals(world.getUID())) {
            previous = Map.of();
        } else {
            previous = state.shown;
        }

        for (Map.Entry<BlockPos, BlockData> old : previous.entrySet()) {
            if (!desired.containsKey(old.getKey())) {
                restore(viewer, world, old.getKey());
            }
        }
        for (Map.Entry<BlockPos, BlockData> now : desired.entrySet()) {
            if (previous.get(now.getKey()) != now.getValue()) {
                BlockPos p = now.getKey();
                viewer.sendBlockChange(new Location(world, p.x(), p.y(), p.z()), now.getValue());
            }
        }
        if (desired.isEmpty()) {
            viewers.remove(viewer.getUniqueId());
        } else {
            viewers.put(viewer.getUniqueId(), new ViewerState(world.getUID(), desired));
        }
    }

    /** Highest solid block with air above, searched around the viewer's height. */
    private static int surfaceY(World world, int x, int z, int aroundY) {
        int top = Math.min(world.getMaxHeight() - 2, aroundY + 8);
        int bottom = Math.max(world.getMinHeight(), aroundY - 12);
        for (int y = top; y >= bottom; y--) {
            Block b = world.getBlockAt(x, y, z);
            Material type = b.getType();
            if (type.isSolid() && !world.getBlockAt(x, y + 1, z).getType().isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static void restore(Player viewer, World world, BlockPos p) {
        if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
            return;
        }
        Block real = world.getBlockAt(p.x(), p.y(), p.z());
        viewer.sendBlockChange(real.getLocation(), real.getBlockData());
    }

    @Override
    public void clear(Player viewer) {
        ViewerState state = viewers.remove(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        World world = Bukkit.getWorld(state.world);
        if (world == null || !viewer.isOnline() || !viewer.getWorld().getUID().equals(state.world)) {
            return; // client reloads chunks on world change / relog anyway
        }
        for (BlockPos p : state.shown.keySet()) {
            restore(viewer, world, p);
        }
    }

    @Override
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            clear(p);
        }
        viewers.clear();
    }
}
