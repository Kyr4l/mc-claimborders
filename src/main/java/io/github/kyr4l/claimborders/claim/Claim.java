package io.github.kyr4l.claimborders.claim;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * All chunks owned by one player, plus the players they trust.
 * A claim does not have to be contiguous.
 */
public final class Claim {

    private final UUID owner;
    private String ownerName;
    private final Set<UUID> trusted = new LinkedHashSet<>();
    private final Set<ChunkKey> chunks = new LinkedHashSet<>();

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

    public Set<UUID> getTrusted() {
        return Collections.unmodifiableSet(trusted);
    }

    public Set<ChunkKey> getChunks() {
        return Collections.unmodifiableSet(chunks);
    }

    public boolean isOwner(UUID id) {
        return owner.equals(id);
    }

    public boolean isTrusted(UUID id) {
        return owner.equals(id) || trusted.contains(id);
    }

    public boolean isTrusted(Player player) {
        return isTrusted(player.getUniqueId());
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

    boolean addTrusted(UUID id) {
        return !owner.equals(id) && trusted.add(id);
    }

    boolean removeTrusted(UUID id) {
        return trusted.remove(id);
    }
}
