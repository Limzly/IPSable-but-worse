package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Baseline access for {@link MergedMassTracker} — used by {@code SableRehomeOps} to SEED a
 * rehomed twin's tracker from its source before the plot block copy.
 *
 * <p>{@code uploadData} keeps the world mapping invariant when the center of mass moves
 * ({@code position += R·(CoM − lastCoM)}, then {@code rotationPoint := CoM}) — but only
 * when a baseline exists. A FRESH tracker's first upload null-baselines
 * {@code lastCenterOfMass}, computes zero movement, and still jumps {@code rotationPoint}
 * to the partial CoM of whatever block happened to be copied first — shifting the whole
 * ship by {@code R·(shipCoM − firstBlockCoM)}: the 0.5-along-facing assembly offset that
 * grew with structure size. Seeding {@code lastCenterOfMass}/{@code lastInertiaTensor}/
 * {@code lastMass} from the settled SOURCE tracker means the null-baseline branch never
 * runs during the copy: every upload walks the invariant-preserving path and the final
 * mapping equals the source mapping by construction.
 */
@Pseudo
@Mixin(value = MergedMassTracker.class, remap = false)
public interface IplMergedMassBaselineAccessor {

    @Accessor(value = "lastCenterOfMass", remap = false)
    void ipl$setLastCenterOfMass(Vector3d value);

    @Accessor(value = "lastInertiaTensor", remap = false)
    void ipl$setLastInertiaTensor(Matrix3d value);

    @Accessor(value = "lastMass", remap = false)
    void ipl$setLastMass(double value);

    @Accessor(value = "lastCenterOfMass", remap = false)
    Vector3d ipl$getLastCenterOfMass();
}
