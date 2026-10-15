package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.claim.Claim;

/** A boundary edge as seen by one viewer: trusted viewers get the "own border" look, others the "force field" look. */
public record EdgeView(BoundaryEdge edge, Claim claim, boolean trusted) {
}
