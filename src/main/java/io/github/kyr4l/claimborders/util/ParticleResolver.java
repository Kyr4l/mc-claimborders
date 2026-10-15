package io.github.kyr4l.claimborders.util;

import org.bukkit.Particle;

import java.util.Locale;

/**
 * Resolves particle names at runtime so one jar works across the 1.20.5 rename
 * (REDSTONE became DUST, ENCHANTMENT_TABLE became ENCHANT, ...).
 */
public final class ParticleResolver {

    private ParticleResolver() {
    }

    public static Particle resolve(String configured, Particle fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        String name = configured.trim().toUpperCase(Locale.ROOT);
        Particle p = tryName(name);
        if (p != null) {
            return p;
        }
        // Known renames in both directions.
        p = switch (name) {
            case "DUST" -> tryName("REDSTONE");
            case "REDSTONE" -> tryName("DUST");
            case "ENCHANT" -> tryName("ENCHANTMENT_TABLE");
            case "ENCHANTMENT_TABLE" -> tryName("ENCHANT");
            case "ENTITY_EFFECT" -> tryName("SPELL_MOB");
            case "SPELL_MOB" -> tryName("ENTITY_EFFECT");
            case "HAPPY_VILLAGER" -> tryName("VILLAGER_HAPPY");
            case "VILLAGER_HAPPY" -> tryName("HAPPY_VILLAGER");
            case "WITCH" -> tryName("SPELL_WITCH");
            case "SPELL_WITCH" -> tryName("WITCH");
            default -> null;
        };
        return p != null ? p : fallback;
    }

    public static Particle dustOrFallback() {
        Particle p = tryName("DUST");
        if (p == null) {
            p = tryName("REDSTONE");
        }
        return p != null ? p : Particle.END_ROD;
    }

    private static Particle tryName(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
