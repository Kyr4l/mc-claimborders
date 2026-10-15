package io.github.kyr4l.claimborders.claim;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/** Immutable identity of a chunk: world id plus chunk coordinates. */
public record ChunkKey(UUID world, int x, int z) {

    public static ChunkKey of(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    public static ChunkKey of(Location location) {
        return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ChunkKey of(World world, int chunkX, int chunkZ) {
        return new ChunkKey(world.getUID(), chunkX, chunkZ);
    }

    public ChunkKey offset(int dx, int dz) {
        return new ChunkKey(world, x + dx, z + dz);
    }

    /** World X coordinate of the chunk's west edge. */
    public int minBlockX() {
        return x << 4;
    }

    /** World Z coordinate of the chunk's north edge. */
    public int minBlockZ() {
        return z << 4;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public boolean isLoaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x, z);
    }

    public String serialize() {
        return world + ";" + x + ";" + z;
    }

    public static ChunkKey parse(String s) {
        String[] parts = s.split(";");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Bad chunk key: " + s);
        }
        return new ChunkKey(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    @Override
    public String toString() {
        World w = bukkitWorld();
        return (w != null ? w.getName() : world.toString()) + " [" + x + ", " + z + "]";
    }
}
