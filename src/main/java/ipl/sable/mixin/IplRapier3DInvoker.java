package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Sable 2.0 made most {@code Rapier3D} natives package-private (and scene ids became
 * native {@code long} handles). Portal rim bodies live outside normal sub-level pipelines,
 * so they need direct access —
 * static invokers merge into the target and carry package-private access with them.
 * (A same-package bridge class would be a JPMS split package; NeoForge forbids those.)
 */
@Pseudo
@Mixin(value = Rapier3D.class, remap = false)
public interface IplRapier3DInvoker {

    // Kinematic contraption family — used for the portal rim containment bodies
    // (world-anchored voxel bodies all ships bounce off; mountId -1 = static mount).

    @Invoker(value = "createKinematicContraption", remap = false)
    static void ipl$createKinematicContraption(
        long sceneHandle, int mountId, int id, double[] pose
    ) {
        throw new AssertionError();
    }

    @Invoker(value = "removeKinematicContraption", remap = false)
    static void ipl$removeKinematicContraption(long sceneHandle, int id) {
        throw new AssertionError();
    }

    @Invoker(value = "setKinematicContraptionTransform", remap = false)
    static void ipl$setKinematicContraptionTransform(
        long sceneHandle, int id, double[] centerOfMass, double[] pose, double[] velocities
    ) {
        throw new AssertionError();
    }

    @Invoker(value = "addKinematicContraptionChunkSection", remap = false)
    static void ipl$addKinematicContraptionChunkSection(
        long sceneHandle, int id, int x, int y, int z, int[] data
    ) {
        throw new AssertionError();
    }

    @Invoker(value = "setLocalBounds", remap = false)
    static void ipl$setLocalBounds(
        long sceneHandle, int id,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ
    ) {
        throw new AssertionError();
    }
}
