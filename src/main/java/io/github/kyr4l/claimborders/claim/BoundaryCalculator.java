package io.github.kyr4l.claimborders.claim;

import java.util.ArrayList;
import java.util.List;

/** Computes which sides of a chunk form the perimeter of its claim. */
public final class BoundaryCalculator {

    private BoundaryCalculator() {
    }

    /** Edges of {@code chunk} whose neighbour is not part of the same claim. Empty if the chunk is unclaimed. */
    public static List<BoundaryEdge> edgesOf(ClaimManager claims, ChunkKey chunk) {
        Claim claim = claims.getClaimAt(chunk);
        if (claim == null) {
            return List.of();
        }
        List<BoundaryEdge> edges = new ArrayList<>(4);
        for (BoundaryEdge.Side side : BoundaryEdge.Side.values()) {
            if (!claim.contains(chunk.offset(side.dx, side.dz))) {
                edges.add(new BoundaryEdge(chunk, side));
            }
        }
        return edges;
    }
}
