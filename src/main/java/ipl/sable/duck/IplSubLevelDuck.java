package ipl.sable.duck;

import net.minecraft.world.level.Level;

/**
 * Duck-types {@code dev.ryanhcode.sable.sublevel.SubLevel} with the two-field model required by
 * the dim-agnostic refactor (see {@code REFACTOR_SPEC.md}).
 *
 * <p>Today, {@code SubLevel.level} is a single field doing two jobs: "where the airship visually
 * appears" and "where the plot chunks live." After the refactor those split:
 *
 * <ul>
 *   <li>{@link #ipl$getParentLevel()} - the dim the airship is currently visible from. Used for
 *       pose-transform origin, IP portal lookup, networking dim ref, player presence. Mutable
 *       because it flips during cross-portal transit.</li>
 *   <li>{@link #ipl$getHostingLevel()} - the dim that physically owns the plot chunks. After
 *       phase 2 this is always the {@code ipl_sable:sublevels} dimension. Mutable only for the
 *       migration path; conceptually a constant after first set.</li>
 * </ul>
 *
 * <p>Implemented on {@code SubLevel} via {@code IplSubLevelFieldSplitMixin}. Phase 1 populates both
 * accessors with the same value (the constructor's {@code level} arg), so no observable behavior
 * change. Phase 2 starts diverging them at allocation time.
 *
 * <p><b>Why a duck interface instead of editing Sable:</b> we ship as a mod that mixin-patches
 * Sable at runtime; we don't fork Sable source. Sable's {@code SubLevel.level} field stays where
 * it is; we add parallel state via {@code @Unique} mixin fields and expose it through this cast
 * target. See {@code REFACTOR_SPEC.md} section "Mixin-only architecture" for the trade-off
 * analysis.
 */
public interface IplSubLevelDuck {

    /**
     * The parent dim - where this sub-level is currently visible from. Mutable across the
     * sub-level's lifetime: flips during cross-portal transit (phase 6).
     */
    Level ipl$getParentLevel();

    /**
     * The hosting dim - where this sub-level's plot chunks physically live. After phase 2 this
     * resolves to {@code SableSubLevelDimension.SUBLEVELS} for all sub-levels.
     */
    Level ipl$getHostingLevel();

    /**
     * Reassign the parent dim. Called by transit logic (phase 6) when an airship crosses an IP
     * portal and its visible dim flips. Should not be called outside transit code.
     */
    void ipl$setParentLevel(Level level);

    /**
     * Reassign the hosting dim. Conceptually a constant after first set; mutator exists only for
     * the phase-7 migration path (relocating chunks from a parent dim to {@code ipl_sable:sublevels}).
     * Should not be called outside migration code.
     */
    void ipl$setHostingLevel(Level level);
}
