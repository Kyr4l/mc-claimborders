package io.github.kyr4l.claimborders.claim;

import java.util.Locale;

/** A player's standing in a claim. Ordered from least to most access. */
public enum TrustLevel {
    /** Not trusted: kept out by the force field. */
    NONE,
    /** May enter and look around, cannot change blocks or use things. */
    VISITOR,
    /** Full access. The owner always has this. */
    MEMBER;

    public boolean canEnter() {
        return this != NONE;
    }

    public boolean canEdit() {
        return this == MEMBER;
    }

    public static TrustLevel parse(String s, TrustLevel fallback) {
        if (s == null) {
            return fallback;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "member", "full", "trusted" -> MEMBER;
            case "visitor", "readonly", "read-only", "guest" -> VISITOR;
            case "none", "outsider", "untrusted", "foreign" -> NONE;
            default -> fallback;
        };
    }
}
