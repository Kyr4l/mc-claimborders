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
                JsonObject o = el.getAsJsonObject();
                Claim claim = new Claim(UUID.fromString(o.get("owner").getAsString()),
                        o.has("ownerName") ? o.get("ownerName").getAsString() : "?");
                if (o.has("trusted")) {
                    for (JsonElement t : o.getAsJsonArray("trusted")) {
                        claim.addTrusted(UUID.fromString(t.getAsString()));
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
                result.add(claim);
            }
        } catch (IOException | RuntimeException ex) {
            log.severe("Could not read " + file + ": " + ex.getMessage());
            backupBroken();
        }
        return result;
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
            JsonArray trusted = new JsonArray();
            claim.getTrusted().forEach(id -> trusted.add(id.toString()));
            o.add("trusted", trusted);
            JsonArray chunks = new JsonArray();
            claim.getChunks().forEach(k -> chunks.add(k.serialize()));
            o.add("chunks", chunks);
            arr.add(o);
        }
        root.add("claims", arr);
        return GSON.toJson(root);
    }

    /** Writes atomically (temp file + move). Safe to call off the main thread. */
    public void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
