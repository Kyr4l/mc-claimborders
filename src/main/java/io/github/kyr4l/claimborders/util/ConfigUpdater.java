package io.github.kyr4l.claimborders.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Adds keys that exist in the bundled config.yml but are missing from the server's copy, with their
 * comments, without touching existing values. Lets a jar update ship new options without a config wipe.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /** Returns the number of keys added. */
    public static int mergeDefaults(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveDefaultConfig();
            return 0;
        }
        InputStream bundled = plugin.getResource("config.yml");
        if (bundled == null) {
            return 0;
        }
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8));
        YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                if (!user.isConfigurationSection(key)) {
                    user.createSection(key);
                    user.setComments(key, defaults.getComments(key));
                }
                continue;
            }
            if (user.contains(key, true)) {
                continue;
            }
            user.set(key, defaults.get(key));
            user.setComments(key, defaults.getComments(key));
            user.setInlineComments(key, defaults.getInlineComments(key));
            added++;
        }
        if (added > 0) {
            try {
                user.save(file);
                plugin.getLogger().info("Added " + added + " new option(s) to config.yml.");
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not update config.yml: " + ex.getMessage());
            }
        }
        return added;
    }
}
