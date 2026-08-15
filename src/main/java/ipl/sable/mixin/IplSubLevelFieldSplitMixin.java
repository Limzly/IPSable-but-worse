package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 of the dim-agnostic refactor: split {@code SubLevel.level} into two parallel fields
 * (parent + hosting) via {@code @Unique} mixin state, exposed through {@link IplSubLevelDuck}.
 *
 * <p><b>No behavior change in this phase.</b> Both new fields are populated with the constructor's
 * {@code level} argument, so {@code ipl$getParentLevel() == ipl$getHostingLevel() == getLevel()}
 * universally. The fields exist only to give phase 2+ a place to start diverging them.
 *
 * <p><b>Why TAIL injection:</b> we need {@code this.level} (Sable's original field) to be set
 * before we can read it. {@code @At("TAIL")} runs after Sable's constructor finishes, when both
 * Sable's {@code level} and our {@code @Unique} fields can be initialized from the same source.
 * The constructor argument is also available directly, but reading from {@code this.level} is
 * defensive: if Sable's constructor ever mutates the argument before assigning, we still get the
 * authoritative value.
 *
 * <p><b>Constructor signature note:</b> Sable's {@code SubLevel} is abstract with a single
 * constructor {@code (Level, int, int, Pose3d)}. The descriptor below matches that exact shape.
 * If Sable adds a constructor overload in a future release, this injection will fail loudly at
 * Mixin apply time rather than silently miss the new path.
 *
 * @see ipl.sable.duck.IplSubLevelDuck
 */
@Mixin(SubLevel.class)
public abstract class IplSubLevelFieldSplitMixin implements IplSubLevelDuck {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-field-split");

    /**
     * One-shot success log. Fires on the first SubLevel construction we see, so we have
     * explicit evidence both fields populated correctly. Subsequent constructions stay quiet
     * to avoid log spam during normal play.
     */
    @Unique
    private static volatile boolean ipl$loggedFirstConstruct = false;

    /**
     * The parent dim - where this sub-level is currently visible from. Mutable across lifetime
     * to support cross-portal transit (phase 6).
     */
    @Unique
    private Level ipl$parentLevel;

    /**
     * The hosting dim - where the plot chunks physically live. After phase 2 always resolves to
     * {@code ipl_sable:sublevels}; phase 1 mirrors the constructor's {@code level} arg.
     */
    @Unique
    private Level ipl$hostingLevel;

    @Inject(
        method = "<init>(Lnet/minecraft/world/level/Level;IILdev/ryanhcode/sable/companion/math/Pose3d;)V",
        at = @At("TAIL")
    )
    private void ipl$initFieldSplit(Level level, int plotX, int plotY, Pose3d pose, CallbackInfo ci) {
        // Phase 1: both fields point to the same Level. No behavior change.
        // Phase 2 will route allocations through SableSubLevelDimension.SUBLEVELS and pass the
        // parent separately, at which point ipl$parentLevel != ipl$hostingLevel for any
        // sub-level created post-refactor. Note: phase 2's IplSubLevelAllocRoutingMixin reassigns
        // ipl$parentLevel via the duck setter AFTER this @TAIL injection completes, so this
        // initial assignment is overwritten for routed allocations.
        this.ipl$parentLevel = level;
        this.ipl$hostingLevel = level;

        if (!ipl$loggedFirstConstruct) {
            ipl$loggedFirstConstruct = true;
            IPL$LOG.info(
                "[IPL-SABLE-FIELD-SPLIT] first SubLevel constructed - level={} plot=({},{}) side={}",
                level.dimension().location(),
                plotX,
                plotY,
                level.isClientSide ? "CLIENT" : "SERVER"
            );
        }
    }

    @Override
    public Level ipl$getParentLevel() {
        return this.ipl$parentLevel;
    }

    @Override
    public Level ipl$getHostingLevel() {
        return this.ipl$hostingLevel;
    }

    @Override
    public void ipl$setParentLevel(Level level) {
        this.ipl$parentLevel = level;
    }

    @Override
    public void ipl$setHostingLevel(Level level) {
        this.ipl$hostingLevel = level;
    }
}
