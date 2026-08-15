package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Edit-driven occupied full-block cache for portal narrow phase. */
public final class IplPortalVolumeCache {

    private static final Map<UUID, List<BlockPos>> BLOCKS = new HashMap<>();
    private static final Map<UUID, Long> REVISIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST_SEEN = new HashMap<>();
    private static long tick;

    private IplPortalVolumeCache() {}

    public static List<BlockPos> blocks(ServerSubLevel ship) {
        LAST_SEEN.put(ship.getUniqueId(), tick);
        return BLOCKS.computeIfAbsent(ship.getUniqueId(), ignored -> build(ship));
    }

    /** Starts a hosting-container scan. Entries not touched by its live ships are stale. */
    public static void beginTick() {
        tick++;
    }

    /** Marks a live ship even when no portal narrow phase queried its block list this tick. */
    public static void touch(ServerSubLevel ship) {
        LAST_SEEN.put(ship.getUniqueId(), tick);
    }

    /** Prevent unbounded UUID/block-list retention after assembly, split, removal or transit. */
    public static void prune() {
        LAST_SEEN.entrySet().removeIf(entry -> {
            if (entry.getValue() == tick) return false;
            UUID id = entry.getKey();
            BLOCKS.remove(id);
            REVISIONS.remove(id);
            return true;
        });
    }

    public static void invalidate(ServerSubLevel ship) {
        UUID id = ship.getUniqueId();
        BLOCKS.remove(id);
        REVISIONS.merge(id, 1L, Long::sum);
    }

    /** Changes only when LevelPlot reports a block edit, never while a ship moves. */
    public static long revision(ServerSubLevel ship) {
        return REVISIONS.getOrDefault(ship.getUniqueId(), 0L);
    }

    public static void clear() {
        BLOCKS.clear();
        REVISIONS.clear();
        LAST_SEEN.clear();
        tick = 0L;
    }

    private static List<BlockPos> build(ServerSubLevel ship) {
        ServerLevel level = (ServerLevel) ship.getLevel();
        var bounds = ship.getPlot().getBoundingBox();
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) blocks.add(pos);
                }
            }
        }
        return List.copyOf(blocks);
    }
}
