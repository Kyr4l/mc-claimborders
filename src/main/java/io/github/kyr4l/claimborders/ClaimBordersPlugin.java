package io.github.kyr4l.claimborders;

import io.github.kyr4l.claimborders.border.BorderService;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import io.github.kyr4l.claimborders.command.ClaimCommand;
import io.github.kyr4l.claimborders.protect.BlockProtection;
import io.github.kyr4l.claimborders.protect.EntryGuard;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
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
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new ClaimConfig(getConfig(), getLogger());
        claims = new ClaimManager(this);
        claims.load();

        entryGuard = new EntryGuard(this);
        Bukkit.getPluginManager().registerEvents(entryGuard, this);
        Bukkit.getPluginManager().registerEvents(new BlockProtection(this), this);

        borders = new BorderService(this);
        borders.start();

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
        if (borders != null) {
            borders.shutdown();
            borders = null;
        }
        HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
        if (claims != null) {
            claims.saveNow();
        }
        lastMessage.clear();
        getLogger().info("ClaimBorders disabled, claims saved.");
    }

    /** Re-reads config.yml and restarts the border renderer. Claims stay loaded. */
    public void reload() {
        reloadConfig();
        settings = new ClaimConfig(getConfig(), getLogger());
        borders.shutdown();
        borders = new BorderService(this);
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

    /** Sends a message unless the same player got one within the configured cooldown. */
    public void sendCooled(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < settings.messageCooldownMillis()) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(message);
    }
}
