package io.github.kyr4l.claimborders.protect;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Helpers to tell real players from mod fake players.
 * Create's Deployer, and other Forge/NeoForge FakePlayers, show up as Player on hybrid servers
 * (Arclight, Mohist, Youer...) but are never in the online list. Claim rules must not apply to them,
 * otherwise a Deployer inside your own base would be "an outsider".
 */
public final class Players {

    private Players() {
    }

    public static boolean isFake(Player player) {
        if (Bukkit.getPlayer(player.getUniqueId()) == null) {
            return true;
        }
        String cls = player.getClass().getName();
        return cls.contains("FakePlayer") || cls.contains("Deployer");
    }

    /** The player behind an attacking entity (direct hit or projectile), or null. */
    public static Player realAttacker(Entity damager) {
        Player p = null;
        if (damager instanceof Player player) {
            p = player;
        } else if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                p = player;
            }
        }
        return p;
    }
}
