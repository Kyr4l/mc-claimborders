package io.github.kyr4l.claimborders.command;

import io.github.kyr4l.claimborders.ClaimBordersPlugin;
import io.github.kyr4l.claimborders.ClaimConfig;
import io.github.kyr4l.claimborders.claim.ChunkKey;
import io.github.kyr4l.claimborders.claim.Claim;
import io.github.kyr4l.claimborders.claim.ClaimManager;
import io.github.kyr4l.claimborders.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** /claim and its aliases. */
public final class ClaimCommand implements TabExecutor {

    private static final List<String> SUBS = List.of(
            "help", "unclaim", "unclaimall", "trust", "untrust", "trustlist", "info", "list", "map", "show", "admin");

    private final ClaimBordersPlugin plugin;

    public ClaimCommand(ClaimBordersPlugin plugin) {
        this.plugin = plugin;
    }

    private ClaimConfig cfg() {
        return plugin.settings();
    }

    private ClaimManager claims() {
        return plugin.claims();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Aliases registered in plugin.yml map straight to sub-commands.
        String lbl = command.getName().toLowerCase(Locale.ROOT);
        if (lbl.equals("unclaim") || lbl.equals("trust") || lbl.equals("untrust")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = lbl;
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            return admin(sender, args);
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this. Try /claim admin reload.");
            return true;
        }
        if (!p.hasPermission("claimborders.use")) {
            p.sendMessage(Text.color("&cYou may not use claims."));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> claimHere(p);
            case "help" -> help(p);
            case "unclaim" -> unclaimHere(p);
            case "unclaimall" -> unclaimAll(p, args);
            case "trust" -> trust(p, args, true);
            case "untrust" -> trust(p, args, false);
            case "trustlist" -> trustList(p);
            case "info" -> info(p);
            case "list" -> list(p);
            case "map" -> map(p);
            case "show" -> show(p);
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(Text.color("&b&lClaimBorders &7- commands"));
        p.sendMessage(Text.color("&b/claim &7- claim the chunk you stand in"));
        p.sendMessage(Text.color("&b/claim unclaim &7- release this chunk"));
        p.sendMessage(Text.color("&b/claim unclaimall confirm &7- release every chunk you own"));
        p.sendMessage(Text.color("&b/claim trust <player> &7/ &buntrust <player> &7/ &btrustlist"));
        p.sendMessage(Text.color("&b/claim info &7- who owns this chunk"));
        p.sendMessage(Text.color("&b/claim list &7- your chunks"));
        p.sendMessage(Text.color("&b/claim map &7- chunk map around you"));
        p.sendMessage(Text.color("&b/claim show &7- toggle your border display"));
        if (p.hasPermission("claimborders.admin")) {
            p.sendMessage(Text.color("&b/claim admin unclaim|unclaimall <player>|reload|stats"));
        }
    }

    private void claimHere(Player p) {
        ChunkKey key = ChunkKey.of(p.getLocation());
        ClaimManager.ClaimResult result = claims().claim(p, key);
        Claim own = claims().getClaimOf(p.getUniqueId());
        String count = String.valueOf(own == null ? 0 : own.size());
        String limit = limitText(p);
        switch (result) {
            case OK -> p.sendMessage(cfg().msg("claimed", "count", count, "limit", limit));
            case ALREADY_OWN -> p.sendMessage(cfg().msg("already-own"));
            case TAKEN -> p.sendMessage(cfg().msg("taken", "owner", claims().getClaimAt(key).getOwnerName()));
            case LIMIT -> p.sendMessage(cfg().msg("limit", "limit", limit));
            case NOT_ADJACENT -> p.sendMessage(cfg().msg("not-adjacent"));
            case WORLD_DISABLED -> p.sendMessage(cfg().msg("world-disabled"));
        }
    }

    private void unclaimHere(Player p) {
        ChunkKey key = ChunkKey.of(p.getLocation());
        Claim claim = claims().getClaimAt(key);
        if (claim == null || !claim.isOwner(p.getUniqueId())) {
            p.sendMessage(cfg().msg("not-yours"));
            return;
        }
        claims().unclaim(key);
        Claim own = claims().getClaimOf(p.getUniqueId());
        p.sendMessage(cfg().msg("unclaimed", "count", String.valueOf(own == null ? 0 : own.size()), "limit", limitText(p)));
    }

    private void unclaimAll(Player p, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            p.sendMessage(Text.color("&eThis releases every chunk you own. Type &f/claim unclaimall confirm&e to proceed."));
            return;
        }
        int n = claims().unclaimAll(p.getUniqueId());
        p.sendMessage(Text.color("&eReleased &f" + n + "&e chunk(s)."));
    }

    private void trust(Player p, String[] args, boolean add) {
        if (args.length < 2) {
            p.sendMessage(Text.color("&cUsage: /claim " + (add ? "trust" : "untrust") + " <player>"));
            return;
        }
        Claim claim = claims().getClaimOf(p.getUniqueId());
        if (claim == null) {
            p.sendMessage(Text.color("&cYou do not own any chunk yet. Claim one first."));
            return;
        }
        resolve(args[1], target -> {
            if (!p.isOnline()) {
                return;
            }
            if (target == null) {
                p.sendMessage(Text.color("&cUnknown player &f" + args[1] + "&c."));
                return;
            }
            String name = target.getName() == null ? args[1] : target.getName();
            if (add) {
                if (target.getUniqueId().equals(p.getUniqueId())) {
                    p.sendMessage(Text.color("&7You are already the owner."));
                    return;
                }
                if (claims().trust(claim, target.getUniqueId(), name)) {
                    p.sendMessage(cfg().msg("trusted", "player", name));
                } else {
                    p.sendMessage(Text.color("&7" + name + " is already trusted."));
                }
            } else {
                if (claims().untrust(claim, target.getUniqueId())) {
                    p.sendMessage(cfg().msg("untrusted", "player", name));
                } else {
                    p.sendMessage(Text.color("&7" + name + " was not trusted."));
                }
            }
            Player online = Bukkit.getPlayer(target.getUniqueId());
            if (online != null) {
                plugin.borders().refresh(online);
                Claim here = claims().getClaimAt(online.getLocation());
                if (!add && here == claim && !online.hasPermission("claimborders.bypass")) {
                    plugin.entryGuard().eject(online, claim);
                }
            }
        });
    }

    private void trustList(Player p) {
        Claim claim = claims().getClaimOf(p.getUniqueId());
        if (claim == null || claim.getTrusted().isEmpty()) {
            p.sendMessage(Text.color("&7Nobody is trusted in your claim."));
            return;
        }
        p.sendMessage(Text.color("&bTrusted: &f" + String.join(", ", claim.getTrustedNames().values())));
    }

    private void info(Player p) {
        ChunkKey key = ChunkKey.of(p.getLocation());
        Claim claim = claims().getClaimAt(key);
        if (claim == null) {
            p.sendMessage(Text.color("&7Chunk &f" + key.x() + ", " + key.z() + " &7is free."));
            return;
        }
        p.sendMessage(Text.color("&7Chunk &f" + key.x() + ", " + key.z() + " &7belongs to &b" + claim.getOwnerName()
                + " &7(" + claim.size() + " chunks). " + (claim.isTrusted(p) ? "&aYou are trusted." : "&cYou are not trusted.")));
    }

    private void list(Player p) {
        Claim claim = claims().getClaimOf(p.getUniqueId());
        if (claim == null) {
            p.sendMessage(Text.color("&7You own no chunk. Stand somewhere and type &f/claim&7."));
            return;
        }
        p.sendMessage(Text.color("&bYour chunks (&f" + claim.size() + "/" + limitText(p) + "&b):"));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ChunkKey key : claim.getChunks()) {
            if (n++ >= 40) {
                sb.append("&7...");
                break;
            }
            sb.append("&f").append(key.x()).append(',').append(key.z()).append("&7  ");
        }
        p.sendMessage(Text.color(sb.toString()));
    }

    private void map(Player p) {
        ChunkKey center = ChunkKey.of(p.getLocation());
        int halfW = 7;
        int halfH = 4;
        p.sendMessage(Text.color("&7--- chunk map, north is up (&a■&7 yours, &b■&7 trusted, &c■&7 others, &8■&7 free, &f◆&7 you) ---"));
        for (int dz = -halfH; dz <= halfH; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -halfW; dx <= halfW; dx++) {
                ChunkKey key = center.offset(dx, dz);
                Claim claim = claims().getClaimAt(key);
                String color;
                if (claim == null) {
                    color = "&8";
                } else if (claim.isOwner(p.getUniqueId())) {
                    color = "&a";
                } else if (claim.isTrusted(p)) {
                    color = "&b";
                } else {
                    color = "&c";
                }
                row.append(color).append(dx == 0 && dz == 0 ? "◆" : "■");
            }
            p.sendMessage(Text.color(row.toString()));
        }
    }

    private void show(Player p) {
        boolean now = plugin.borders().toggle(p);
        p.sendMessage(cfg().msg(now ? "show-on" : "show-off"));
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("claimborders.admin")) {
            sender.sendMessage(Text.color("&cNo permission."));
            return true;
        }
        String op = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Text.color("&aClaimBorders reloaded."));
            }
            case "stats" -> {
                sender.sendMessage(Text.color("&b" + claims().totalChunks() + " chunk(s) claimed by "
                        + claims().getClaims().size() + " player(s). Renderer: " + cfg().renderer() + ", force field: " + cfg().forceFieldMode()));
                if (sender instanceof Player p) {
                    sender.sendMessage(Text.color("&7" + plugin.borders().debug(p)));
                }
            }
            case "unclaim" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                Claim claim = claims().unclaim(ChunkKey.of(p.getLocation()));
                sender.sendMessage(Text.color(claim == null ? "&7This chunk was free." : "&eChunk taken from &f" + claim.getOwnerName() + "&e."));
            }
            case "unclaimall" -> {
                if (args.length < 3) {
                    sender.sendMessage(Text.color("&cUsage: /claim admin unclaimall <player>"));
                    return true;
                }
                resolve(args[2], target -> {
                    int n = target == null ? 0 : claims().unclaimAll(target.getUniqueId());
                    sender.sendMessage(Text.color("&eReleased &f" + n + "&e chunk(s)."));
                });
            }
            default -> sender.sendMessage(Text.color("&b/claim admin unclaim|unclaimall <player>|reload|stats"));
        }
        return true;
    }

    private String limitText(Player p) {
        int limit = claims().limitFor(p);
        return limit == Integer.MAX_VALUE ? "∞" : String.valueOf(limit);
    }

    /**
     * Resolves a name to a player. Online players answer immediately; otherwise the profile lookup (network +
     * disk) runs off the main thread and the callback is invoked back on it. The callback gets null if unknown.
     */
    @SuppressWarnings("deprecation")
    private void resolve(String name, Consumer<OfflinePlayer> callback) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            callback.accept(online);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            boolean known = offline.hasPlayedBefore() || offline.getName() != null;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(known ? offline : null));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String lbl = command.getName().toLowerCase(Locale.ROOT);
        if (lbl.equals("trust") || lbl.equals("untrust")) {
            return args.length == 1 ? playerNames(args[0]) : List.of();
        }
        if (lbl.equals("unclaim")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                if (s.equals("admin") && !sender.hasPermission("claimborders.admin")) {
                    continue;
                }
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return playerNames(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("unclaim", "unclaimall", "reload", "stats").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("unclaimall")) {
            return playerNames(args[2]);
        }
        return List.of();
    }

    private static List<String> playerNames(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
