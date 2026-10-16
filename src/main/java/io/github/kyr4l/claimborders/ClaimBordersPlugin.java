package io.github.kyr4l.claimborders;

import io.github.kyr4l.claimborders.border.BorderService;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import io.github.kyr4l.claimborders.command.ClaimCommand;
import io.github.kyr4l.claimborders.protect.BlockProtection;
import io.github.kyr4l.claimborders.protect.EntryGuard;
import io.github.kyr4l.claimborders.protect.ZoneMessages;
import io.github.kyr4l.claimborders.util.ConfigUpdater;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point. Holds no static state, so PlugManX load/unload/reload cycles are clean:
 * onDisable cancels every task, removes every entity and fake block, unregisters listeners and saves.
 */
public final class ClaimBordersPlugin extends JavaPlugin {

    private ClaimConfig settings;
    private ClaimManager claims;
    private BorderService borders;
    private EntryGuard entryGuard;
    private ZoneMessages zoneMessages;
    private final Map<String, Long> lastMessage = new HashMap<>();
    /** Players who toggled /claim show. Survives renderer restarts. */
    private final Set<UUID> showToggles = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (ConfigUpdater.mergeDefaults(this) > 0) {
            reloadConfig();
        }
        settings = new ClaimConfig(getConfig(), getLogger());
        claims = new ClaimManager(this);
        claims.load();

        entryGuard = new EntryGuard(this);
        Bukkit.getPluginManager().registerEvents(entryGuard, this);
        Bukkit.getPluginManager().registerEvents(new BlockProtection(this), this);

        borders = new BorderService(this, showToggles);
        borders.start();
        zoneMessages = new ZoneMessages(this);

        ClaimCommand executor = new ClaimCommand(this);
        for (String name : new String[] {"claim", "unclaim", "trust", "untrust"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(executor);
                cmd.setTabCompleter(executor);
            }
        }
        getLogger().info("ClaimBorders enabled. Force field: " + settings.forceFieldMode() + ", renderer: " + settings.renderer());
    }

    @Override
    public void onDisable() {
        // Save first: nothing below may cost us data. Each step is isolated so one failure cannot skip the rest.
        step("save claims", () -> {
            if (claims != null) {
                claims.saveNow();
            }
        });
        step("stop border renderer", () -> {
            if (borders != null) {
                borders.shutdown();
                borders = null;
            }
        });
        step("stop entry guard", () -> {
            if (entryGuard != null) {
                entryGuard.shutdown();
            }
        });
        step("stop zone messages", () -> {
            if (zoneMessages != null) {
                zoneMessages.shutdown();
            }
        });
        HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
        lastMessage.clear();
        showToggles.clear();
        getLogger().info("ClaimBorders disabled, claims saved.");
    }

    private void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to " + what + " while disabling", ex);
        }
    }

    /** Re-reads config.yml and restarts the border renderer. Claims stay loaded. */
    public void reload() {
        ConfigUpdater.mergeDefaults(this);
        reloadConfig();
        settings = new ClaimConfig(getConfig(), getLogger());
        borders.shutdown();
        borders = new BorderService(this, showToggles);
        borders.start();
    }

    public ClaimConfig settings() {
        return settings;
    }

    public ClaimManager claims() {
        return claims;
    }

    public BorderService borders() {
        return borders;
    }

    public EntryGuard entryGuard() {
        return entryGuard;
    }

    public ZoneMessages zoneMessages() {
        return zoneMessages;
    }

    public void forgetCooldown(Player player) {
        String prefix = player.getUniqueId() + ":";
        lastMessage.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Sends a message unless this player got the same kind of message within the configured cooldown. */
    public void sendCooled(Player player, String key, String message) {
        long now = System.currentTimeMillis();
        String k = player.getUniqueId() + ":" + key;
        Long last = lastMessage.get(k);
        if (last != null && now - last < settings.messageCooldownMillis()) {
            return;
        }
        lastMessage.put(k, now);
        player.sendMessage(message);
    }
}
