package io.github.kyr4l.claimborders.claim;

/**
 * One side of a claimed chunk that faces a chunk not owned by the same claim.
 * Coordinates are world block coordinates of the line between the two chunks.
 */
public record BoundaryEdge(ChunkKey chunk, Side side) {

    public enum Side {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

        public final int dx;
        public final int dz;

        Side(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    public ChunkKey neighbour() {
        return chunk.offset(side.dx, side.dz);
    }

    /** True when the line runs along X (north/south side), false when it runs along Z. */
    public boolean alongX() {
        return side == Side.NORTH || side == Side.SOUTH;
    }

    /** World X of the line start. */
    public int x1() {
        return side == Side.EAST ? chunk.minBlockX() + 16 : chunk.minBlockX();
    }

    /** World Z of the line start. */
    public int z1() {
        return side == Side.SOUTH ? chunk.minBlockZ() + 16 : chunk.minBlockZ();
    }

    public int x2() {
        return alongX() ? x1() + 16 : x1();
    }

    public int z2() {
        return alongX() ? z1() : z1() + 16;
    }

    /** Block X of the outermost claimed column on this side (for fake blocks). */
    public int innerBlockX(int i) {
        return switch (side) {
            case WEST -> chunk.minBlockX();
            case EAST -> chunk.minBlockX() + 15;
            default -> chunk.minBlockX() + i;
        };
    }

    /** Block Z of the outermost claimed row on this side (for fake blocks). */
    public int innerBlockZ(int i) {
        return switch (side) {
            case NORTH -> chunk.minBlockZ();
            case SOUTH -> chunk.minBlockZ() + 15;
            default -> chunk.minBlockZ() + i;
        };
    }

    /** Horizontal squared distance from a point to the nearest point on this edge. */
    public double distanceSquared(double px, double pz) {
        double cx = Math.max(x1(), Math.min(x2(), px));
        double cz = Math.max(z1(), Math.min(z2(), pz));
        double dx = px - cx;
        double dz = pz - cz;
        return dx * dx + dz * dz;
    }
}
