package io.github.kyr4l.claimborders;

import io.github.kyr4l.claimborders.util.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Typed snapshot of config.yml. Rebuilt on reload. */
public final class ClaimConfig {

    public enum ForceFieldMode { HARD, BOUNCE, PUSH }

    public enum RendererType { PARTICLES, GHOST_BLOCKS, DISPLAY, NONE }

    public enum RevealMode { ALWAYS, NEAR }

    public enum DisplayStyle { BLOCK, TEXT }

    // claims
    private final int maxChunks;
    private final boolean requireAdjacent;
    private final List<String> allowedWorlds;
    private final List<String> blockedWorlds;
    private final boolean ejectOnClaim;
    private final boolean ejectOnJoin;

    // protection
    private final boolean protectBlocks;
    private final boolean protectInteract;
    private final boolean protectEntities;
    private final boolean protectExplosions;
    private final boolean noPvp;
    private final boolean ignoreFakePlayers;

    // force field
    private final boolean forceFieldEnabled;
    private final ForceFieldMode forceFieldMode;
    private final double knockback;
    private final double pushRadius;
    private final double pushStrength;
    private final boolean blockTeleports;
    private final boolean blockVehicles;
    private final boolean visitorGlow;
    private final int messageCooldownMillis;

    // border
    private final RendererType renderer;
    private final boolean showOwnByDefault;
    private final int borderRadius;
    private final int intervalTicks;

    private final String particleType;
    private final Color particleColor;
    private final Color particleForeignColor;
    private final Color particleVisitorColor;
    private final float particleSize;
    private final double particleStep;
    private final List<Integer> particleLayers;
    private final int particleVerticalSpan;

    private final Material ghostWallMaterial;
    private final int ghostWallBelow;
    private final int ghostWallAbove;
    private final Material ghostOutlineMaterial;
    private final Material ghostVisitorOutlineMaterial;
    private final int ghostRadius;

    private final DisplayStyle displayStyle;
    private final double displayOpacity;
    private final Color displayColor;
    private final Color displayVisitorColor;
    private final Color displayForeignColor;
    private final Material displayMaterial;
    private final Material displayForeignMaterial;
    private final Material displayVisitorMaterial;
    private final int displayHeight; // -1 = FULL
    private final float displayThickness;
    private final boolean displayGlow;
    private final boolean displayCullingBox;
    private final Color displayGlowColor;
    private final boolean displayHideFromTrusted;
    private final RevealMode displayReveal;
    private final int displayRevealRadius;

    private final String prefix;
    private final Map<String, String> messages = new HashMap<>();

    public ClaimConfig(FileConfiguration c, Logger log) {
        maxChunks = Math.max(0, c.getInt("claims.max-chunks", 16));
        requireAdjacent = c.getBoolean("claims.require-adjacent", true);
        allowedWorlds = c.getStringList("claims.allowed-worlds");
        blockedWorlds = c.getStringList("claims.blocked-worlds");
        ejectOnClaim = c.getBoolean("claims.eject-on-claim", true);
        ejectOnJoin = c.getBoolean("claims.eject-on-join", true);

        protectBlocks = c.getBoolean("protection.blocks", true);
        protectInteract = c.getBoolean("protection.interact", true);
        protectEntities = c.getBoolean("protection.entities", true);
        protectExplosions = c.getBoolean("protection.explosions", true);
        noPvp = c.getBoolean("protection.pvp", false);
        ignoreFakePlayers = c.getBoolean("protection.ignore-fake-players", true);

        forceFieldEnabled = c.getBoolean("forcefield.enabled", true);
        forceFieldMode = parseEnum(ForceFieldMode.class, c.getString("forcefield.mode"), ForceFieldMode.PUSH, log);
        knockback = c.getDouble("forcefield.knockback", 0.6);
        pushRadius = Math.max(0.5, c.getDouble("forcefield.push-radius", 3.0));
        pushStrength = c.getDouble("forcefield.push-strength", 0.35);
        blockTeleports = c.getBoolean("forcefield.block-teleports", true);
        blockVehicles = c.getBoolean("forcefield.block-vehicles", true);
        visitorGlow = c.getBoolean("forcefield.visitor-glow", true);
        messageCooldownMillis = (int) (c.getDouble("forcefield.message-cooldown-seconds", 2.0) * 1000);

        renderer = parseEnum(RendererType.class, c.getString("border.renderer"), RendererType.DISPLAY, log);
        showOwnByDefault = c.getBoolean("border.show-own-by-default", false);
        borderRadius = Math.max(8, c.getInt("border.radius", 48));
        intervalTicks = Math.max(1, c.getInt("border.interval-ticks", 10));

        particleType = c.getString("border.particles.type", "DUST");
        particleColor = Text.parseColor(c.getString("border.particles.color"), Color.fromRGB(0x4FC3F7));
        particleForeignColor = Text.parseColor(c.getString("border.particles.foreign-color"), Color.fromRGB(0xFF5252));
        particleVisitorColor = Text.parseColor(c.getString("border.particles.visitor-color"), Color.fromRGB(0xFFEE58));
        particleSize = (float) c.getDouble("border.particles.size", 1.3);
        particleStep = Math.max(0.25, c.getDouble("border.particles.step", 1.0));
        List<Integer> layers = c.getIntegerList("border.particles.layers");
        particleLayers = layers.isEmpty() ? List.of(0, 2) : layers;
        particleVerticalSpan = Math.max(0, c.getInt("border.particles.vertical-span", 6));

        ghostWallMaterial = parseMaterial(c.getString("border.ghost-blocks.wall-material"), Material.LIGHT_BLUE_STAINED_GLASS, log);
        ghostWallBelow = Math.max(0, c.getInt("border.ghost-blocks.wall-below", 3));
        ghostWallAbove = Math.max(1, c.getInt("border.ghost-blocks.wall-above", 4));
        ghostOutlineMaterial = parseMaterial(c.getString("border.ghost-blocks.outline-material"), Material.SEA_LANTERN, log);
        ghostVisitorOutlineMaterial = parseMaterial(c.getString("border.ghost-blocks.visitor-outline-material"), Material.GLOWSTONE, log);
        ghostRadius = Math.max(8, c.getInt("border.ghost-blocks.radius", 32));

        displayStyle = parseEnum(DisplayStyle.class, c.getString("border.display.style"), DisplayStyle.BLOCK, log);
        displayOpacity = Math.max(0.0, Math.min(1.0, c.getDouble("border.display.opacity", 0.35)));
        displayColor = Text.parseColor(c.getString("border.display.color"), Color.fromRGB(0x4FC3F7));
        displayVisitorColor = Text.parseColor(c.getString("border.display.visitor-color"), Color.fromRGB(0xFFEE58));
        displayForeignColor = Text.parseColor(c.getString("border.display.foreign-color"), Color.fromRGB(0xFF5252));
        displayMaterial = parseMaterial(c.getString("border.display.material"), Material.LIGHT_BLUE_STAINED_GLASS_PANE, log);
        displayForeignMaterial = parseMaterial(c.getString("border.display.foreign-material"), Material.RED_STAINED_GLASS_PANE, log);
        displayVisitorMaterial = parseMaterial(c.getString("border.display.visitor-material"), Material.YELLOW_STAINED_GLASS_PANE, log);
        String h = String.valueOf(c.get("border.display.height", "FULL")).trim();
        int parsedHeight;
        try {
            parsedHeight = h.equalsIgnoreCase("FULL") ? -1 : Math.max(1, Integer.parseInt(h));
        } catch (NumberFormatException ex) {
            log.warning("border.display.height must be FULL or a number, got '" + h + "'. Using FULL.");
            parsedHeight = -1;
        }
        displayHeight = parsedHeight;
        displayThickness = (float) Math.max(0.01, c.getDouble("border.display.thickness", 0.08));
        displayGlow = c.getBoolean("border.display.glow", false);
        displayCullingBox = c.getBoolean("border.display.culling-box", true);
        displayGlowColor = Text.parseColor(c.getString("border.display.glow-color"), Color.fromRGB(0x4FC3F7));
        displayHideFromTrusted = c.getBoolean("border.display.hide-from-trusted", true);
        displayReveal = parseEnum(RevealMode.class, c.getString("border.display.reveal"), RevealMode.NEAR, log);
        displayRevealRadius = Math.max(4, c.getInt("border.display.reveal-radius", 24));

        prefix = c.getString("messages.prefix", "&b[Claims]&r ");
        if (c.getConfigurationSection("messages") != null) {
            for (String key : c.getConfigurationSection("messages").getKeys(false)) {
                messages.put(key, c.getString("messages." + key, ""));
            }
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback, Logger log) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warning("Unknown " + type.getSimpleName() + " '" + value + "', using " + fallback);
            return fallback;
        }
    }

    private static Material parseMaterial(String value, Material fallback, Logger log) {
        if (value == null) {
            return fallback;
        }
        Material m = Material.matchMaterial(value.trim());
        if (m == null || !m.isBlock()) {
            log.warning("Unknown block material '" + value + "', using " + fallback);
            return fallback;
        }
        return m;
    }

    /** Message by key with %placeholders%, prefixed and colorised. */
    public String msg(String key, String... kv) {
        Map<String, String> ph = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            ph.put(kv[i], kv[i + 1]);
        }
        return Text.format(prefix + messages.getOrDefault(key, "&c<" + key + ">"), ph);
    }

    public int maxChunks() { return maxChunks; }
    public boolean requireAdjacent() { return requireAdjacent; }
    public List<String> allowedWorlds() { return allowedWorlds; }
    public List<String> blockedWorlds() { return blockedWorlds; }
    public boolean ejectOnClaim() { return ejectOnClaim; }
    public boolean ejectOnJoin() { return ejectOnJoin; }

    public boolean protectBlocks() { return protectBlocks; }
    public boolean protectInteract() { return protectInteract; }
    public boolean protectEntities() { return protectEntities; }
    public boolean protectExplosions() { return protectExplosions; }
    public boolean noPvp() { return noPvp; }
    public boolean ignoreFakePlayers() { return ignoreFakePlayers; }

    public boolean forceFieldEnabled() { return forceFieldEnabled; }
    public ForceFieldMode forceFieldMode() { return forceFieldMode; }
    public double knockback() { return knockback; }
    public double pushRadius() { return pushRadius; }
    public double pushStrength() { return pushStrength; }
    public boolean blockTeleports() { return blockTeleports; }
    public boolean blockVehicles() { return blockVehicles; }
    public boolean visitorGlow() { return visitorGlow; }
    public int messageCooldownMillis() { return messageCooldownMillis; }

    public RendererType renderer() { return renderer; }
    public boolean showOwnByDefault() { return showOwnByDefault; }
    public int borderRadius() { return borderRadius; }
    public int intervalTicks() { return intervalTicks; }

    public String particleType() { return particleType; }
    public Color particleColor() { return particleColor; }
    public Color particleForeignColor() { return particleForeignColor; }
    public Color particleVisitorColor() { return particleVisitorColor; }
    public float particleSize() { return particleSize; }
    public double particleStep() { return particleStep; }
    public List<Integer> particleLayers() { return particleLayers; }
    public int particleVerticalSpan() { return particleVerticalSpan; }

    public Material ghostWallMaterial() { return ghostWallMaterial; }
    public int ghostWallBelow() { return ghostWallBelow; }
    public int ghostWallAbove() { return ghostWallAbove; }
    public Material ghostOutlineMaterial() { return ghostOutlineMaterial; }
    public Material ghostVisitorOutlineMaterial() { return ghostVisitorOutlineMaterial; }
    public int ghostRadius() { return ghostRadius; }

    public DisplayStyle displayStyle() { return displayStyle; }
    public double displayOpacity() { return displayOpacity; }
    public Color displayColor() { return displayColor; }
    public Color displayVisitorColor() { return displayVisitorColor; }
    public Color displayForeignColor() { return displayForeignColor; }
    public Material displayMaterial() { return displayMaterial; }
    public Material displayForeignMaterial() { return displayForeignMaterial; }
    public Material displayVisitorMaterial() { return displayVisitorMaterial; }
    public int displayHeight() { return displayHeight; }
    public float displayThickness() { return displayThickness; }
    public boolean displayGlow() { return displayGlow; }
    public boolean displayCullingBox() { return displayCullingBox; }
    public Color displayGlowColor() { return displayGlowColor; }
    public boolean displayHideFromTrusted() { return displayHideFromTrusted; }
    public RevealMode displayReveal() { return displayReveal; }
    public int displayRevealRadius() { return displayRevealRadius; }
}
