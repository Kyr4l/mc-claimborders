package io.github.kyr4l.claimborders.claim;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All chunks owned by one player, plus the players they trust and at which level.
 * A claim does not have to be contiguous.
 */
public final class Claim {

    /** A trusted player: the name they had when trusted (no lookups needed later) and their level. */
    public record Trusted(String name, TrustLevel level) {
    }

    private final UUID owner;
    private String ownerName;
    private final Map<UUID, Trusted> trusted = new LinkedHashMap<>();
    private final Set<ChunkKey> chunks = new LinkedHashSet<>();
    private boolean messagesEnabled = true;
    private boolean vanishEnabled = false;

    public Claim(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    /** Everyone trusted at any level, excluding the owner. */
    public Map<UUID, Trusted> getTrusted() {
        return Collections.unmodifiableMap(trusted);
    }

    public Set<ChunkKey> getChunks() {
        return Collections.unmodifiableSet(chunks);
    }

    public boolean isOwner(UUID id) {
        return owner.equals(id);
    }

    public TrustLevel levelOf(UUID id) {
        if (owner.equals(id)) {
            return TrustLevel.MEMBER;
        }
        Trusted t = trusted.get(id);
        return t == null ? TrustLevel.NONE : t.level();
    }

    public TrustLevel levelOf(Player player) {
        return levelOf(player.getUniqueId());
    }

    /** Full access: owner or member. */
    public boolean isTrusted(UUID id) {
        return levelOf(id) == TrustLevel.MEMBER;
    }

    public boolean isTrusted(Player player) {
        return isTrusted(player.getUniqueId());
    }

    /** Owner, member or visitor. */
    public boolean canEnter(Player player) {
        return levelOf(player.getUniqueId()).canEnter();
    }

    /** Whether welcome / approach messages are shown for this claim. */
    public boolean isMessagesEnabled() {
        return messagesEnabled;
    }

    void setMessagesEnabled(boolean enabled) {
        this.messagesEnabled = enabled;
    }

    /** Whether the owner is hidden from everyone while inside this claim. */
    public boolean isVanishEnabled() {
        return vanishEnabled;
    }

    void setVanishEnabled(boolean enabled) {
        this.vanishEnabled = enabled;
    }

    public boolean contains(ChunkKey key) {
        return chunks.contains(key);
    }

    public int size() {
        return chunks.size();
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /** True if the chunk touches (shares a side with) any owned chunk. */
    public boolean isAdjacent(ChunkKey key) {
        return chunks.contains(key.offset(1, 0)) || chunks.contains(key.offset(-1, 0))
                || chunks.contains(key.offset(0, 1)) || chunks.contains(key.offset(0, -1));
    }

    boolean addChunk(ChunkKey key) {
        return chunks.add(key);
    }

    boolean removeChunk(ChunkKey key) {
        return chunks.remove(key);
    }

    /** Returns true if something changed (new entry or a different level). */
    boolean setTrusted(UUID id, String name, TrustLevel level) {
        if (owner.equals(id) || level == TrustLevel.NONE) {
            return false;
        }
        Trusted previous = trusted.put(id, new Trusted(name == null ? "?" : name, level));
        return previous == null || previous.level() != level;
    }

    boolean removeTrusted(UUID id) {
        return trusted.remove(id) != null;
    }
}
