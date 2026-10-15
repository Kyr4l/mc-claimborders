package io.github.kyr4l.claimborders.protect;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.Claim;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;

/**
 * Block and entity protection against non-trusted players.
 * Deliberately limited to events caused by a real player: pistons, redstone, fluids, block physics,
 * mod machinery (Create contraptions, deployers, drills, belts, kinetic networks...) are never cancelled.
 */
public final class BlockProtection implements Listener {

    private final ClaimBordersPlugin plugin;

    public BlockProtection(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
    }

    private ClaimConfig cfg() {
        return plugin.settings();
    }

    /** The claim that forbids this player at this location, or null if the action is fine. */
    private Claim denied(Player p, Location loc) {
        if ((cfg().ignoreFakePlayers() && Players.isFake(p)) || p.hasPermission("claimborders.bypass")) {
            return null;
        }
        Claim claim = plugin.claims().getClaimAt(loc);
        return claim != null && !claim.isTrusted(p) ? claim : null;
    }

    private void tell(Player p, Claim claim) {
        plugin.sendCooled(p, "action", cfg().msg("action-denied", "owner", claim.getOwnerName()));
    }

    // ---- blocks ----------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!cfg().protectBlocks()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getBlock().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!cfg().protectBlocks()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getBlock().getLocation());
        if (c == null && e instanceof BlockMultiPlaceEvent multi) {
            // Beds, doors, tall plants: the second half may land across the border.
            for (BlockState state : multi.getReplacedBlockStates()) {
                c = denied(e.getPlayer(), state.getLocation());
                if (c != null) {
                    break;
                }
            }
        }
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (!cfg().protectBlocks() || e.getPlayer() == null) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getBlock().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (!cfg().protectBlocks() || e.getPlayer() == null) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getBlock().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!cfg().protectBlocks()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getBlock().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!cfg().protectBlocks()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getBlock().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!cfg().protectInteract() || e.getClickedBlock() == null) {
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL) {
            return;
        }
        if (e.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getClickedBlock().getLocation());
        if (c != null) {
            // Deny the block only, so eating/placing-in-hand style item use keeps working.
            e.setUseInteractedBlock(Event.Result.DENY);
            if (e.getAction() == Action.PHYSICAL) {
                e.setCancelled(true);
            } else {
                tell(e.getPlayer(), c);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (cfg().protectExplosions()) {
            e.blockList().removeIf(b -> plugin.claims().getClaimAt(b.getLocation()) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (cfg().protectExplosions()) {
            e.blockList().removeIf(b -> plugin.claims().getClaimAt(b.getLocation()) != null);
        }
    }

    // ---- entities --------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attacker = Players.realAttacker(e.getDamager());
        if (attacker == null || (cfg().ignoreFakePlayers() && Players.isFake(attacker))) {
            return;
        }
        Entity victim = e.getEntity();
        if (victim instanceof Player) {
            if (cfg().noPvp() && plugin.claims().getClaimAt(victim.getLocation()) != null
                    && !attacker.hasPermission("claimborders.bypass")) {
                e.setCancelled(true);
            }
            return;
        }
        if (!cfg().protectEntities() || victim instanceof Monster) {
            return;
        }
        Claim c = denied(attacker, victim.getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(attacker, c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!cfg().protectEntities()) {
            return;
        }
        Player remover = Players.realAttacker(e.getRemover());
        if (remover == null) {
            return;
        }
        Claim c = denied(remover, e.getEntity().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(remover, c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!cfg().protectEntities()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getRightClicked().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent e) {
        if (!cfg().protectEntities()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getEntity().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (!cfg().protectEntities()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getEntity().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) {
        if (!cfg().protectEntities()) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getEntity().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!cfg().protectEntities() || e.getRightClicked() instanceof Player) {
            return;
        }
        Claim c = denied(e.getPlayer(), e.getRightClicked().getLocation());
        if (c != null) {
            e.setCancelled(true);
            tell(e.getPlayer(), c);
        }
    }
}
