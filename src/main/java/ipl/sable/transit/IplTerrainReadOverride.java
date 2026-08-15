package ipl.sable.transit;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-local level override for Sable's {@code LevelAccelerator} chunk reads.
 *
 * <p>The Rapier pipeline's {@code handleChunkSectionAddition} ignores the passed section's
 * CONTENT — it re-reads every voxel through the pipeline's {@code LevelAccelerator}, which is
 * bound to the pipeline's own level. For a hosted sub-level that means the hosting dim's
 * void chunks: parent terrain fed by coordinate alone bakes to an empty collider (dim-agnostic
 * bring-up bug B — ships fall through the world).
 *
 * <p>{@code IplHostedTicketManagerMixin} sets this override to the sub-level's PARENT level
 * around its terrain pre-enrollment calls; {@code IplLevelAcceleratorOverrideMixin} routes
 * {@code LevelAccelerator.getChunk} to the override while it is set (bypassing the
 * accelerator's single-entry cache so no cross-level chunk pollution is possible).
 */
public final class IplTerrainReadOverride {

    private static final ThreadLocal<Level> OVERRIDE = new ThreadLocal<>();

    private IplTerrainReadOverride() {}

    @Nullable
    public static Level get() {
        return OVERRIDE.get();
    }

    public static void set(Level level) {
        OVERRIDE.set(level);
    }

    public static void clear() {
        OVERRIDE.remove();
    }
}
