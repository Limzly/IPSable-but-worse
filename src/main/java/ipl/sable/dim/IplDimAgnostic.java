package ipl.sable.dim;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Helpers for the mandatory hosted sub-level model.
 *
 * <p>Every {@code ServerSubLevel} is rehomed shortly after creation into the
 * {@code ipl_sable:sublevels} hosting dimension ({@link SableSubLevelDimension#SUBLEVELS}) by
 * {@link ipl.sable.transit.SableRehomeOps}. From then on:
 * <ul>
 *   <li>{@code subLevel.getLevel()} (Sable's field) == the hosting level — chunk storage,
 *       physics pipeline, container membership, persistence all live there.</li>
 *   <li>{@link IplSubLevelDuck#ipl$getParentLevel()} == the dimension the airship visually
 *       appears in. Tracking, portal queries, rendering, and Atlas image-collider terrain
 *       enrollment use the parent; the real Sable level remains the hosting dimension.</li>
 *   <li>Cross-portal transit flips {@code parentLevel} (plus a pose remap) — plot chunks
 *       never move again.</li>
 * </ul>
 *
 */
public final class IplDimAgnostic {

    private IplDimAgnostic() {}

    /** Is this level the dedicated hosting dimension? */
    public static boolean isHostingLevel(@Nullable Level level) {
        return level != null && level.dimension() == SableSubLevelDimension.SUBLEVELS;
    }

    /**
     * Is this sub-level rehomed into the hosting dimension? True iff its container/chunk level
     * is {@code ipl_sable:sublevels}. (The duck's hostingLevel field mirrors the constructor's
     * level, so checking the real level is the authoritative test on both sides.)
     */
    public static boolean isHosted(@Nullable SubLevel subLevel) {
        return subLevel != null && isHostingLevel(subLevel.getLevel());
    }

    /**
     * The parent level of a hosted sub-level — where it visually appears and physically
     * interacts. A freshly allocated sub-level may not have been stamped yet, in which case
     * its current level is returned until rehoming completes.
     */
    public static Level getParentLevel(SubLevel subLevel) {
        Level parent = ((IplSubLevelDuck) subLevel).ipl$getParentLevel();
        return parent != null ? parent : subLevel.getLevel();
    }

    /**
     * Server-side parent of a hosted sub-level, or null if the parent is unset/unresolvable
     * (e.g. freshly deserialized before {@code SableRehomeOps} restored it from user data).
     */
    @Nullable
    public static ServerLevel getServerParentLevel(SubLevel subLevel) {
        Level parent = getParentLevel(subLevel);
        if (parent instanceof ServerLevel serverLevel && !isHostingLevel(serverLevel)) {
            return serverLevel;
        }
        return null;
    }

    /**
     * The hosting dimension's sub-level container, resolved from any sided context level
     * (non-creating on the client). Null when the hosting dim/world isn't available yet.
     */
    @Nullable
    public static dev.ryanhcode.sable.api.sublevel.SubLevelContainer getHostingContainerFor(Level context) {
        if (context instanceof ServerLevel serverLevel) {
            ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(serverLevel.getServer());
            return hosting == null ? null
                : dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer((Level) hosting);
        }
        if (context.isClientSide) {
            // Client-only class; loaded lazily, only when this branch executes.
            return ipl.sable.client.IplClientHostedLookup.getHostingContainerOrNull();
        }
        return null;
    }
}
