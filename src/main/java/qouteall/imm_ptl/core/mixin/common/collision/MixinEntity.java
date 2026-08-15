package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.SableBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.api.ImmPtlEntityExtension;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.CountDownInt;

@Mixin(Entity.class)
public abstract class MixinEntity implements IEEntity, ImmPtlEntityExtension {
    
    @Nullable
    @Unique
    private PortalCollisionHandler ip_portalCollisionHandler;
    
    @Shadow
    private Level level;
    
    @Shadow
    protected abstract Vec3 collide(Vec3 vec3d_1);
    
    @Shadow
    public abstract Component getName();
    
    @Shadow
    public abstract double getX();
    
    @Shadow
    public abstract double getY();
    
    @Shadow
    public abstract double getZ();
    
    
    @Shadow
    public int tickCount;
    
    @Shadow
    protected abstract void unsetRemoved();
    
    @Shadow
    private Vec3 position;
    
    @Shadow
    private BlockPos blockPosition;
    
    @Shadow
    private ChunkPos chunkPosition;
    
    @Shadow @Final private static Logger LOGGER;
    @Shadow private @Nullable BlockState inBlockState;
    @Unique
    private static final CountDownInt IMM_PTL_LOG_COUNTER = new CountDownInt(20);
    
    // Originally a @Redirect of Entity.collide(Vec3) inside Entity.move(MoverType,Vec3).
    // Converted to @WrapOperation so this coexists with Sable's @Redirect at
    // sable.mixins.json:entity.entity_sublevel_collision.EntityMixin (priority 1100) targeting
    // the same INVOKE.
    //
    // With two @Redirects at unequal priority, Sable's wins and IP's is skipped; IP's default
    // require=1 then trips an InjectionError at mixin apply time and the server refuses to boot
    // (this was conflict #1 of 5 in the audit at audit/phase4_classified.md).
    //
    // @WrapOperation chains via MixinExtras instead: when we delegate via
    //   original.call(entity, attemptedMove)
    // the underlying INVOKE routes into Sable's @Redirect, which does sub-level-aware collision
    // followed by vanilla Entity.collide(...) and returns the merged motion. So Sable's full
    // collision behavior is preserved for the non-portal path. When IP handles cross-portal
    // collision itself, Sable's path is intentionally bypassed -- an entity actually traversing
    // a portal should not have sub-level block-collision applied to it.
    //
    // TODO(sable-transit): This is also the natural integration point for sub-level traversal
    // through portals. Pseudocode for the future expansion:
    //
    //     if (entity instanceof KinematicContraption kc && isCrossingPortalThisTick(self)) {
    //         return handleSubLevelTransit(kc, attemptedMove);  // detect + trigger + suppress
    //     }
    //
    // would go above the IPGlobal.crossPortalCollision branch. Detection here (per-collision-
    // step, accurate to the bytecode INVOKE site) replaces the bridge mod's per-10-tick
    // polling scan in XDimTransit, which had a cooldown-cycling duplication bug. See
    // audit/phase4_classified.md row 4 and the conversation around XDimTransit's failures.
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectHandleCollisions(Entity entity, Vec3 attemptedMove, Operation<Vec3> original) {
        if (!IPGlobal.enableServerCollision) {
            if (!entity.level().isClientSide()) {
                if (entity instanceof Player) {
                    return attemptedMove;
                }
                else {
                    return Vec3.ZERO;
                }
            }
        }

        if (attemptedMove.lengthSqr() > 60 * 60) {
            // avoid loading too many chunks in collision calculation and lag the server
            if (IMM_PTL_LOG_COUNTER.tryDecrement()) {
                LOGGER.error(
                    "[ImmPtl] Skipping collision calculation because entity moves too fast {} {} {}",
                    entity, attemptedMove, entity.level().getGameTime(),
                    new Throwable()
                );
            }

            return Vec3.ZERO;
        }

        // Base pass: ALWAYS delegate through the operation chain first. With Sable
        // present this runs its sub-level-aware @Redirect (computing the airship
        // floor and populating sable$collisionInfo); without Sable it bottoms out
        // at vanilla collide(). Either way this is the result we return when no
        // portal collision is active -- identical to upstream in that case.
        Vec3 sableResult = (Vec3) original.call(entity, attemptedMove);

        if (!IPGlobal.crossPortalCollision
            || ip_portalCollisionHandler == null
            || !ip_portalCollisionHandler.hasCollisionEntry()
        ) {
            return sableResult;
        }

        Vec3 result = ip_portalCollisionHandler.handleCollision(
            (Entity) (Object) this, attemptedMove
        );

        if (result.lengthSqr() > 20 * 20) {
            if (IMM_PTL_LOG_COUNTER.tryDecrement()) {
                LOGGER.error(
                    "[ImmPtl] cross portal collision result too large {} {} {}",
                    this, attemptedMove, result
                );
            }
            return Vec3.ZERO;
        }

        // Sable-compat fix: when an entity is standing on a sub-level (airship)
        // that straddles a portal, IP's portal branch previously returned `result`
        // and dropped Sable's sub-level floor entirely -- so the moment the ship
        // clipped the portal frame, the deck stopped colliding and the entity fell
        // through. The two collision systems are not mutually exclusive here: the
        // real sub-level lives wholly in the source dim (the mirror is a separate
        // copy), so its floor is always valid source-dim geometry and must keep
        // applying. We fold both constraints together per-axis (most-restrictive).
        //
        // Gated on hasSubLevelFloorThisTick so the ONLY behavior that changes is
        // "riding a ship floor at a portal": a pure portal crossing with no ship
        // under the entity still returns `result` exactly as before. No-op when
        // Sable is absent.
        //
        // FUTURE (option B): `sableResult` is Sable's FULL collide -- it includes
        // Sable's internal vanilla this-side collision, so a block in the portal
        // frame edge could theoretically over-constrain via the merge below. To
        // eliminate that, pull ONLY the sub-level contribution (call
        // SubLevelEntityCollision.collide directly, skipping Sable's vanilla merge)
        // and constrain `result` with just that. More coupling to Sable internals;
        // deferred until/unless the frame-edge case actually bites.
        if (SableBridge.hasSubLevelFloorThisTick((Entity) (Object) this)) {
            // Riding a ship that's STRADDLING the portal: Sable's collision is now
            // both-frame aware through Atlas image mappings, so it is
            // authoritative. Folding IP's portal result in here constrained the rider
            // against dest-world geometry beyond the plane (the ground the linked portal
            // stands on, the frame at mapped coords) — phantom floors that reduced
            // apparent gravity and capped jumps while on the through-part.
            if (SableBridge.isFloorSubLevelStraddlingPortal((Entity) (Object) this)) {
                return sableResult;
            }
            return ip_mostRestrictivePerAxis(attemptedMove, result, sableResult);
        }

        return result;
    }

    /**
     * Combine two independent collision results by taking the more-restrictive
     * component on each axis, relative to the attempted movement's direction.
     * For a falling entity ({@code attempted.y < 0}), this keeps whichever result
     * stops it higher (closer to zero) -- so a sub-level floor that halts the fall
     * wins over a portal result that would let it keep falling.
     */
    @Unique
    private static Vec3 ip_mostRestrictivePerAxis(Vec3 attempted, Vec3 a, Vec3 b) {
        return new Vec3(
            ip_restrictAxis(attempted.x, a.x, b.x),
            ip_restrictAxis(attempted.y, a.y, b.y),
            ip_restrictAxis(attempted.z, a.z, b.z)
        );
    }

    @Unique
    private static double ip_restrictAxis(double attempted, double a, double b) {
        if (attempted > 0.0) {
            return Math.min(a, b); // moving positive: the smaller allowed step wins
        }
        if (attempted < 0.0) {
            return Math.max(a, b); // moving negative: the larger (closer to 0) wins -> floor holds
        }
        return 0.0;
    }
    
    //don't burn when jumping into end portal
    // TODO make it work for all portals
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;fireImmune()Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onIsFireImmune(CallbackInfoReturnable<Boolean> cir) {
        if (ip_getCollidingPortal() instanceof EndPortalEntity) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Entity;checkInsideBlocks()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"
        )
    )
    private AABB redirectBoundingBoxInCheckingBlockCollision(Entity entity) {
        return ip_getActiveCollisionBox(entity.getBoundingBox());
    }
    
    @Inject(
        method = "checkInsideBlocks",
        at = @At(
            value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
            shift = At.Shift.AFTER
        ),
        locals = LocalCapture.CAPTURE_FAILHARD,
        cancellable = true
    )
    private void onCheckInsideBlocks(CallbackInfo ci, AABB box) {
        if (box == null) {
            ci.cancel();
        }
    }
    
    // avoid suffocation when colliding with a portal on wall
    @Inject(method = "Lnet/minecraft/world/entity/Entity;isInWall()Z", at = @At("HEAD"), cancellable = true)
    private void onIsInsideWall(CallbackInfoReturnable<Boolean> cir) {
        if (ip_isRecentlyCollidingWithPortal()) {
            cir.setReturnValue(false);
        }
    }
    
    // for teleportation debug
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;setPosRaw(DDD)V",
        at = @At("HEAD")
    )
    private void onSetPos(double nx, double ny, double nz, CallbackInfo ci) {
        Entity this_ = (Entity) (Object) this;
        
        if (this_ instanceof Player) {
            if (IPGlobal.teleportationDebugEnabled) {
                if (Math.abs(getX() - nx) > 10 ||
                    Math.abs(getY() - ny) > 10 ||
                    Math.abs(getZ() - nz) > 10
                ) {
                    Helper.log(String.format(
                        "%s %s teleported from %s %s %s to %s %s %s",
                        getName().getContents(),
                        level.dimension(),
                        (int) getX(), (int) getY(), (int) getZ(),
                        (int) nx, (int) ny, (int) nz
                    ));
                    new Throwable().printStackTrace();
                }
            }
        }
    }
    
    // Was: @Inject HEAD cancellable on getInBlockState, returning early when an entity under
    // an upward-facing portal should report the across-portal block (e.g. ladder cross-portal).
    //
    // Conflict #2 of 5 in audit/phase4_classified.md: Sable's
    // entity.entity_sublevel_collision.EntityMixin (priority 1100) @Overwrites this method to
    // also walk intersecting sub-levels on the source side. With an @Overwrite from Sable and
    // an @Inject HEAD cancellable from IP, IP's injection can't bind (Sable erases the HEAD
    // anchor) and IP's default require=1 fails -> InjectionError, no boot.
    //
    // Converted to @ModifyReturnValue. This binds to the method's RETURN sites, which survive
    // Sable's overwrite (Sable's body still has a return statement). The layering is now:
    //   1. Sable's @Overwrite computes the in-block-state, consulting source-side sub-levels.
    //   2. Our @ModifyReturnValue runs on the way out. If the entity is colliding with an
    //      upward-facing portal AND a non-air block exists at the portal-transformed position
    //      in the destination world -- either as a natural block or inside a destination-side
    //      sub-level -- we override Sable's result with that destination block.
    //   3. Otherwise Sable's answer passes through unchanged.
    //
    // Behavioral expansion over the original IP: this also handles "ladder lives on an
    // airship in the destination dim" via SableBridge.lookupNonAirSubLevelBlockAt, which
    // mirrors Sable's source-side sub-level walk on the destination side. With Sable absent
    // the call is a no-op (returns null), restoring exact upstream IP behavior.
    @ModifyReturnValue(method = "getInBlockState", at = @At("RETURN"))
    private BlockState ip_overrideForUpwardPortalCrossing(BlockState sableResult) {
        Portal collidingPortal = ((IEEntity) this).ip_getCollidingPortal();
        if (collidingPortal == null) {
            return sableResult;
        }
        if (collidingPortal.getNormal().y <= 0) {
            // only upward-facing portals get the cross-portal block lookup
            return sableResult;
        }

        Entity self = (Entity) (Object) this;
        Vec3 remoteWorldPos = collidingPortal.transformPoint(self.position());
        BlockPos remoteLandingPos = BlockPos.containing(remoteWorldPos);
        Level destinationWorld = collidingPortal.getDestinationWorld();

        if (!destinationWorld.hasChunkAt(remoteLandingPos)) {
            return sableResult;
        }

        // (1) Try the destination world's natural block (upstream IP behavior).
        BlockState naturalDestState = destinationWorld.getBlockState(remoteLandingPos);
        if (!naturalDestState.isAir()) {
            return naturalDestState;
        }

        // (2) Try destination-side sub-levels via Sable's API. No-op (returns null) when Sable
        //     isn't loaded. Symmetric to Sable's source-side getInBlockState pattern.
        BlockState subLevelDestState = SableBridge.lookupNonAirSubLevelBlockAt(destinationWorld, remoteWorldPos);
        if (subLevelDestState != null) {
            return subLevelDestState;
        }

        return sableResult;
    }
    
//    // IDEA's conditional breakpoint hurts performance
//    @Inject(
//        method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
//        at = @At("HEAD")
//    )
//    private void debug_onSetDeltaMovement(Vec3 deltaMovement, CallbackInfo ci) {
//        Entity this_ = (Entity) (Object) this;
//        if (this_ instanceof Player && this_.level().isClientSide()) {
//            int i = 0;
//        }
//    }
    
    @Override
    public Portal ip_getCollidingPortal() {
        if (ip_portalCollisionHandler == null) {
            return null;
        }
        if (ip_portalCollisionHandler.portalCollisions.isEmpty()) {
            return null;
        }
        return ip_portalCollisionHandler.portalCollisions.get(0).portal;
    }
    
    // this should be called between the range of updating last tick pos and calculating movement
    // because between these two operations, this tick pos is the same as last tick pos
    // CollisionHelper.getStretchedBoundingBox uses the difference between this tick pos and last tick pos
    @Override
    public void ip_tickCollidingPortal() {
        Entity this_ = (Entity) (Object) this;
        
        if (ip_portalCollisionHandler != null) {
            ip_portalCollisionHandler.update(this_);
        }
        
        if (level.isClientSide) {
            IPMcHelper.onClientEntityTick(this_);
        }
    }
    
    @Override
    public void ip_notifyCollidingWithPortal(Entity portal) {
        Entity this_ = (Entity) (Object) this;
        
        if (ip_portalCollisionHandler == null) {
            ip_portalCollisionHandler = new PortalCollisionHandler();
        }
        
        ip_portalCollisionHandler.notifyCollidingWithPortal(this_, ((Portal) portal));
    }
    
    @Override
    public boolean ip_isCollidingWithPortal() {
        if (ip_portalCollisionHandler == null) {
            return false;
        }
        return ip_portalCollisionHandler.hasCollisionEntry();
    }
    
    @Override
    public boolean ip_isRecentlyCollidingWithPortal() {
        if (ip_portalCollisionHandler == null) {
            return false;
        }
        return ip_portalCollisionHandler.isRecentlyCollidingWithPortal((Entity) (Object) this);
    }
    
    @Override
    public void ip_unsetRemoved() {
        unsetRemoved();
    }
    
    /**
     * {@link Entity#setPosRaw(double, double, double)}
     */
    @IPVanillaCopy
    @Override
    public void ip_setPositionWithoutTriggeringCallback(Vec3 newPos) {
        double x = newPos.x;
        double y = newPos.y;
        double z = newPos.z;
        
        if (this.position.x != x || this.position.y != y || this.position.z != z) {
            this.position = new Vec3(x, y, z);
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            if (bx != this.blockPosition.getX()
                || by != this.blockPosition.getY()
                || bz != this.blockPosition.getZ()
            ) {
                this.blockPosition = new BlockPos(bx, by, bz);
                this.inBlockState = null;
                if (SectionPos.blockToSectionCoord(bx) != this.chunkPosition.x
                    || SectionPos.blockToSectionCoord(bz) != this.chunkPosition.z
                ) {
                    this.chunkPosition = new ChunkPos(this.blockPosition);
                }
            }
        }
    }
    
    @Override
    public void ip_clearCollidingPortal() {
        ip_portalCollisionHandler = null;
    }
    
    @Nullable
    @Override
    public AABB ip_getActiveCollisionBox(AABB originalBox) {
        Entity this_ = (Entity) (Object) this;
        
        if (ip_portalCollisionHandler == null) {
            return originalBox;
        }
        
        return ip_portalCollisionHandler.getActiveCollisionBox(this_, originalBox);
    }
    
    @Nullable
    @Override
    public PortalCollisionHandler ip_getPortalCollisionHandler() {
        return ip_portalCollisionHandler;
    }
    
    @Override
    public PortalCollisionHandler ip_getOrCreatePortalCollisionHandler() {
        if (ip_portalCollisionHandler == null) {
            ip_portalCollisionHandler = new PortalCollisionHandler();
        }
        return ip_portalCollisionHandler;
    }
    
    @Override
    public void ip_setPortalCollisionHandler(@Nullable PortalCollisionHandler handler) {
        ip_portalCollisionHandler = handler;
    }
    
    @Override
    public void ip_setWorld(Level world) {
        this.level = world;
    }
    
    // don't use game time because client game time may jump due to time synchronization
    private long ip_getStableTiming() {
        return tickCount;
    }
}
