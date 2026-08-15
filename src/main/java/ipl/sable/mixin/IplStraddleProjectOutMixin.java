package ipl.sable.mixin;

import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Atlas pick fix: {@code projectOutOfSubLevel} maps plot-space points to world via
 * the ship's UNMAPPED pose — but a point on the THROUGH half of a straddling ship
 * physically exists at its portal-MAPPED position. Without this, a projection pick
 * hit measures its reach distance to the source-frame twin (often out of range or
 * plain wrong), vanilla's {@code filterHitResult} clamps it to MISS, and Jade/
 * interaction report whatever world block lies behind the apparition.
 *
 * <p>Remapping here heals every consumer at once — pick reach, interaction
 * distance, Jade's ray, sound positions, rider eye positions — on BOTH sides
 * (this companion is common code; the session lookup branches client/server).
 */
@Pseudo
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class IplStraddleProjectOutMixin {

    @Inject(
        method = "distanceSquaredWithSubLevels(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;Lnet/minecraft/core/Position;)D",
        at = @At("RETURN"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$frameAwareDistance(
        Level level, Position a, Position b, CallbackInfoReturnable<Double> cir
    ) {
        boolean plotA = ipl$isHostedPlotPosition(level, a.x(), a.z());
        boolean plotB = ipl$isHostedPlotPosition(level, b.x(), b.z());
        if (!plotA && !plotB) return;

        // The runtime companion's overload delegation does not reliably route this
        // pair through the (remapping) 3-arg projection - recompute both endpoints
        // through it DIRECTLY and take the min: a through-half plot point measures
        // to its MAPPED world position (where it physically is), never the ghost.
        ActiveSableCompanion self = (ActiveSableCompanion) (Object) this;
        Vector3d worldA = self.projectOutOfSubLevel(
            level, new Vector3d(a.x(), a.y(), a.z()), new Vector3d());
        Vector3d worldB = self.projectOutOfSubLevel(
            level, new Vector3d(b.x(), b.y(), b.z()), new Vector3d());
        double remappedSq = worldA.distanceSquared(worldB);
        if (remappedSq < cir.getReturnValue()) {
            cir.setReturnValue(remappedSq);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean ipl$isHostedPlotPosition(Level level, double x, double z) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return true;
        var hosting = ipl.sable.dim.IplDimAgnostic.getHostingContainerFor(serverLevel);
        if (hosting == null) return false;
        int chunkX = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(x));
        int chunkZ = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(z));
        return hosting.inBounds(chunkX, chunkZ) && hosting.getPlot(chunkX, chunkZ) != null;
    }

    @Inject(
        method = "projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
        at = @At("RETURN"),
        remap = false,
        require = 0
    )
    private void ipl$remapThroughHalf(
        Level level, Vector3dc pos, Vector3d dest, CallbackInfoReturnable<Vector3d> cir
    ) {
        SubLevel owner = ((ActiveSableCompanion) (Object) this).getContaining(level, pos);
        if (owner == null) return;
        Vector3d out = cir.getReturnValue();
        Vec3 remapped = IplStraddlePoseMap.remapThroughHalfProjection(
            owner, level, new Vec3(out.x, out.y, out.z));
        if (remapped != null) {
            out.set(remapped.x, remapped.y, remapped.z);
        }
    }

    @Inject(
        method = "projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$remapThroughHalfVec(
        Level level, Position pos, CallbackInfoReturnable<Vec3> cir
    ) {
        SubLevel owner = ((ActiveSableCompanion) (Object) this).getContaining(level, pos);
        if (owner == null) return;
        Vec3 remapped = IplStraddlePoseMap.remapThroughHalfProjection(
            owner, level, cir.getReturnValue());
        if (remapped != null) {
            cir.setReturnValue(remapped);
        }
    }
}
