package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Workaround for a Sable initialization bug:
 * {@code Entity.sable$collisionInfo} (a {@code @Unique} field on Sable's
 * {@code entity_sublevel_collision.EntityMixin}) defaults to {@code null} and is only
 * populated by Sable's {@code @Redirect} on {@code Entity.collide()} inside
 * {@link Entity#move(MoverType, Vec3)}. Sable's {@code @WrapOperation} on
 * {@code setOnGroundWithMovement()} in the same {@code move()} method then reads
 * that field unconditionally.
 *
 * <p><b>The bug:</b> vanilla {@code Entity.move} short-circuits the
 * {@code this.collide(movement)} call when {@code movement.lengthSqr() < 1.0E-7}.
 * For an entity that's never moved with non-zero displacement (just constructed,
 * freshly teleported via {@code changeEntityDimension}, etc.), the field stays
 * {@code null}. The next non-noPhysics {@code move(SELF, Vec3.ZERO)} call -- common
 * for idle animals on flat ground -- skips Sable's redirect and goes straight to
 * Sable's wrap, which dereferences the null field -> NPE:
 * <pre>
 *   Cannot read field "horizontalCollision" because "this.sable$collisionInfo" is null
 *     at Entity.wrapOperation$cai000$sable$moveInject(Entity.java:...)
 *     at Entity.move
 *     at LivingEntity.handleRelativeFrictionAndCalculateMovement
 *     at LivingEntity.travel
 *     at LivingEntity.aiStep
 *     ...
 * </pre>
 *
 * <p>We hit this when {@code SableTransitOps.teleportRiders} sends an idle animal
 * (e.g., a pig riding the airship deck) through a portal. NeoForge's
 * {@code changeEntityDimension} constructs a new {@code Entity} instance on the
 * destination side; that fresh instance has the {@code @Unique} field at its
 * default {@code null}, and the first tick where {@code travel()} computes zero
 * movement immediately NPEs.
 *
 * <p><b>The fix:</b> at HEAD of {@code Entity.move}, before vanilla's
 * lengthSqr check, if the entity is not noPhysics, not a passenger, the movement
 * is below vanilla's threshold, AND {@code sable$collisionInfo} is still null,
 * substitute a sub-millimeter downward drift {@code (0, -5e-4, 0)} (lengthSqr ~=
 * 2.5e-7, just above vanilla's 1e-7 cutoff). This forces vanilla to call
 * {@code this.collide(movement)}, Sable's {@code @Redirect} fires and populates
 * the field, and subsequent {@code setOnGroundWithMovement} sees a non-null field.
 *
 * <p>Drift cost: 0.5mm downward on the entity's first idle tick after creation.
 * Imperceptible visually, gets absorbed by vanilla gravity on the same tick.
 *
 * <p>Mixin priority {@code 900} is intentionally lower than Sable's {@code 1100}
 * (mixin uses lower number = higher priority = applied earlier in the pipeline).
 * Our {@code @ModifyVariable} runs before Sable sees the {@code movement} arg, so
 * Sable's downstream redirects/wraps see our bumped value.
 *
 * <p>This should be reported upstream to Sable -- our compat layer is patching a
 * bug that affects all Sable users, not just IP+Sable. TODO file a Sable issue.
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class SableCollisionInfoInitMixin {

    @ModifyVariable(
        method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0  // best-effort: if the method signature changes, fall back to no-op
    )
    private Vec3 ipl$bumpMovementToInitSableCollisionInfo(Vec3 movement) {
        Entity self = (Entity) (Object) this;

        // noPhysics entities early-return from move() before reaching the
        // setOnGroundWithMovement wrap site, so they can't trigger the bug. Skip
        // them to avoid an unnecessary 0.5mm drift on Pos.
        if (self.noPhysics) {
            return movement;
        }

        // NB: we do NOT skip passengers. Empirically, a passenger's move() can
        // still reach setOnGroundWithMovement (LivingEntity.travel calls move
        // regardless of vehicle state in some code paths). Confirmed via crash
        // report 23:40:48: a pig riding a Create Aero seat that transited cross-dim
        // (carried as a passenger of the seat entity by vanilla
        // changeEntityDimension) ended up as a fresh Entity instance with
        // sable$collisionInfo=null. Its idle tick NPE'd because this mixin's old
        // isPassenger() check skipped it. Removing the skip; sub-mm drift on
        // passenger entities is harmless.

        // If movement is non-trivial, vanilla will call collide() and Sable's
        // redirect fires naturally. No bump needed.
        if (movement.lengthSqr() >= 1.0E-7) {
            return movement;
        }

        // Only bump when sable$collisionInfo is null, to avoid jitter on every idle
        // tick after the field is populated.
        if (self instanceof EntityMovementExtension ext
            && ext.sable$getCollisionInfo() == null) {
            return new Vec3(0.0, -5.0E-4, 0.0);
        }

        return movement;
    }

    /**
     * Last-resort safeguard for the same bug. Even with non-zero movement (where the
     * bump above doesn't trigger and vanilla DOES call collide), the user has hit
     * cases where {@code sable$collisionInfo} is still null at the wrap site -- for
     * example, a freshly-teleported ItemEntity in a real chunk with non-zero momentum
     * crashed in this path. The exact reason {@code SubLevelEntityCollision.collide}
     * doesn't populate the field isn't clear without reading deeper into Sable's
     * collision pipeline.
     *
     * <p>We register a {@code @WrapOperation} on the same {@code setOnGroundWithMovement}
     * invoke site Sable's mixin targets. Our mixin is at priority 900 vs. Sable's 1100;
     * in Mixin's convention <i>lower number = higher priority = applied first</i>, so
     * our wrap is the outermost at runtime. We get to inspect {@code sable$collisionInfo}
     * before deciding whether to delegate to Sable's wrap (via {@code Operation.call})
     * or bypass it (call the original method directly).
     *
     * <p>When we bypass, the entity loses Sable's sub-level-aware {@code onGround} /
     * {@code horizontalCollision} update for that one tick. Vanilla's plain
     * {@code setOnGroundWithMovement} runs with the args computed by vanilla
     * {@code move}. For entities not in contact with a sub-level (the case where this
     * path actually matters), that's correct anyway.
     */
    @WrapOperation(
        method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"
        ),
        require = 0  // best-effort
    )
    private void ipl$safeguardSableWrapAgainstNullCollisionInfo(
        Entity instance, boolean onGround, Vec3 collided, Operation<Void> original
    ) {
        if (instance instanceof EntityMovementExtension ext
            && ext.sable$getCollisionInfo() == null) {
            // Sable's wrap inside this Operation chain would NPE on its first line
            // (reading sable$collisionInfo.horizontalCollision). Skip the chain entirely
            // and call vanilla's setOnGroundWithMovement directly with the args we
            // were handed.
            instance.setOnGroundWithMovement(onGround, collided);
            return;
        }
        // Normal path: let Sable's wrap (and any other wraps in the chain) do their work.
        original.call(instance, onGround, collided);
    }
}
