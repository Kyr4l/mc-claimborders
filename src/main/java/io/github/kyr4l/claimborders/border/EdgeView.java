package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.claim.BoundaryEdge;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.TrustLevel;

/**
 * A boundary edge as seen by one viewer. The level is the viewer's standing in that claim (or the
 * /claim view override), which picks the look: member, visitor or outsider.
 */
public record EdgeView(BoundaryEdge edge, Claim claim, TrustLevel level) {

    public boolean trusted() {
        return level == TrustLevel.MEMBER;
    }

    /** The claim is sealed: owner look versus everyone-else look, whatever the levels say. */
    public boolean lockdown() {
        return claim != null && claim.isLockdown();
    }
}
