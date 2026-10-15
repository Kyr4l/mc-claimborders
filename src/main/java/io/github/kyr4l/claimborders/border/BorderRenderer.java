package io.github.kyr4l.claimborders.border;

import io.github.kyr4l.claimborders.claim.ClaimManager;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * One way of drawing claim borders. Implementations must leave no trace after {@link #shutdown()}
 * so the plugin can be unloaded with PlugManX without leftovers.
 */
public interface BorderRenderer {

    /** Called once after construction, with claims already loaded. */
    default void start() {
    }

    /** True if the renderer wants {@link #render} called every interval with nearby edges. */
    default boolean needsTick() {
        return true;
    }

    /** Horizontal radius (blocks) around a viewer in which edges are collected for {@link #render}. */
    int radius();

    /** Draw the given edges for this viewer. Called every interval on the main thread. */
    void render(Player viewer, List<EdgeView> edges);

    /** Remove anything shown to this viewer (quit, world change, renderer swap). */
    void clear(Player viewer);

    /** Viewer toggled /claim show. */
    default void onShowToggle(Player viewer, boolean showing) {
    }

    default void onJoin(Player player) {
    }

    default void onClaimChange(ClaimManager.ClaimChange change) {
    }

    default void onChunkLoad(Chunk chunk) {
    }

    default void onChunkUnload(Chunk chunk) {
    }

    /** One line of diagnostics for /claim admin stats. */
    default String debug(Player viewer) {
        return "";
    }

    /** Remove everything, for every viewer, from every world. */
    void shutdown();
}
