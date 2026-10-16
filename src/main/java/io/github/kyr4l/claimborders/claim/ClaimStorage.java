package io.github.kyr4l.claimborders.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Reads and writes claims.json. Gson ships with every Minecraft server. */
public final class ClaimStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger log;

    public ClaimStorage(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Returns null when the file exists but could not be read; callers must then refuse to overwrite it. */
    public List<Claim> load() {
        List<Claim> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray claims = root.has("claims") ? root.getAsJsonArray("claims") : new JsonArray();
            for (JsonElement el : claims) {
                try {
                    result.add(readClaim(el.getAsJsonObject()));
                } catch (RuntimeException ex) {
                    log.warning("Skipping unreadable claim entry: " + ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.severe("Could not read " + file + ": " + ex.getMessage());
            backupBroken();
            return null;
        }
        return result;
    }

    private void readClaimTrusted(Claim claim, JsonElement t) {
        if (t.isJsonObject()) {
            JsonObject o = t.getAsJsonObject();
            TrustLevel level = o.has("level") ? TrustLevel.parse(o.get("level").getAsString(), TrustLevel.MEMBER) : TrustLevel.MEMBER;
            claim.setTrusted(UUID.fromString(o.get("id").getAsString()), o.has("name") ? o.get("name").getAsString() : "?", level);
        } else {
            claim.setTrusted(UUID.fromString(t.getAsString()), "?", TrustLevel.MEMBER);
        }
    }

    private Claim readClaim(JsonObject o) {
        Claim claim = new Claim(UUID.fromString(o.get("owner").getAsString()),
                o.has("ownerName") ? o.get("ownerName").getAsString() : "?");
        if (o.has("messages")) {
            claim.setMessagesEnabled(o.get("messages").getAsBoolean());
        }
        if (o.has("trusted")) {
            for (JsonElement t : o.getAsJsonArray("trusted")) {
                try {
                    readClaimTrusted(claim, t);
                } catch (RuntimeException ex) {
                    log.warning("Skipping unreadable trusted entry: " + t);
                }
            }
        }
        if (o.has("chunks")) {
            for (JsonElement c : o.getAsJsonArray("chunks")) {
                try {
                    claim.addChunk(ChunkKey.parse(c.getAsString()));
                } catch (IllegalArgumentException ex) {
                    log.warning("Skipping unreadable chunk entry: " + c);
                }
            }
        }
        return claim;
    }

    /** Serialises on the calling thread; cheap, so it can run on the main thread. */
    public String serialize(Iterable<Claim> claims) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonArray arr = new JsonArray();
        for (Claim claim : claims) {
            if (claim.isEmpty()) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("owner", claim.getOwner().toString());
            o.addProperty("ownerName", claim.getOwnerName());
            o.addProperty("messages", claim.isMessagesEnabled());
            JsonArray trusted = new JsonArray();
            claim.getTrusted().forEach((id, entry) -> {
                JsonObject t = new JsonObject();
                t.addProperty("id", id.toString());
                t.addProperty("name", entry.name());
                t.addProperty("level", entry.level().name());
                trusted.add(t);
            });
            o.add("trusted", trusted);
            JsonArray chunks = new JsonArray();
            claim.getChunks().forEach(k -> chunks.add(k.serialize()));
            o.add("chunks", chunks);
            arr.add(o);
        }
        root.add("claims", arr);
        return GSON.toJson(root);
    }

    /** Writes atomically (temp file + move). Safe to call off the main thread; writes are serialised. */
    public synchronized void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.severe("Could not write " + file + ": " + ex.getMessage());
        }
    }

    private void backupBroken() {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // nothing more we can do
        }
    }
}
