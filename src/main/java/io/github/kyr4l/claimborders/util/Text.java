package io.github.kyr4l.claimborders.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.entity.Player;

import java.util.Map;

/** Small helpers for chat formatting and color parsing. */
public final class Text {

    private Text() {
    }

    public static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    /** Replaces %key% placeholders then colorizes. */
    public static String format(String template, Map<String, String> placeholders) {
        String out = template == null ? "" : template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", String.valueOf(e.getValue()));
        }
        return color(out);
    }

    /** Shows a line above the hotbar (action bar); the text is already colorised. */
    public static void actionBar(Player player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    /** Parses "#RRGGBB" or "RRGGBB"; falls back to the given default on bad input. */
    public static Color parseColor(String hex, Color fallback) {
        if (hex == null) {
            return fallback;
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (h.length() != 6) {
            return fallback;
        }
        try {
            return Color.fromRGB(Integer.parseInt(h, 16));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
