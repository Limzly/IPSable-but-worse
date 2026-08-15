package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.config.server.physics.SimPhysics;
import dev.simulated_team.simulated.service.SimConfigService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import ipl.sable.duck.IplStaffDragSessionControl;

/**
 * Authoritative replacement for Simulated's PD motor tick. This is intentionally an overwrite:
 * wrapping Quaternion.transformInverse was too late and let stock frame assumptions leak into
 * constraint setup and following packets.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public abstract class IplStaffDragSessionOverwriteMixin implements IplStaffDragSessionControl {

    @Shadow(remap = false) @Final private java.util.UUID playerUUID;
    @Shadow(remap = false) @Final private Vector3d plotAnchor;
    @Shadow(remap = false) @Final private Vector3d playerRelativeGoal;
    @Shadow(remap = false) @Final private Vector3d localGoal;
    @Shadow(remap = false) @Final private Quaterniond orientation;
    @Shadow(remap = false) @Final private ServerSubLevel subLevel;
    /** Hard cap on the velocity lead, in blocks. See the derivation in physicsTick. */
    private static final double IPL$MAX_LEAD_BLOCKS = 6.0;

    /** Previous mapped goal, in the body's parent frame, for the feed-forward term. */
    @org.spongepowered.asm.mixin.Unique
    private final Vector3d ipl$previousGoal = new Vector3d();

    /** Game time (in ticks, fractional) at which {@link #ipl$previousGoal} was sampled. */
    @org.spongepowered.asm.mixin.Unique
    private double ipl$previousGoalTime = Double.NaN;

    @Shadow(remap = false) private PhysicsConstraintHandle constraint;
    @Shadow(remap = false) private void attachConstraint(SubLevelPhysicsSystem physicsSystem) {}

    /**
     * @author IPL-Sable
     * @reason Goal arrives pre-mapped from client; install exact PD target without stock frame recovery.
     */
    @Overwrite(remap = false)
    private void physicsTick(SubLevelPhysicsSystem physicsSystem) {
        if (this.subLevel.isRemoved()) return;

        net.minecraft.server.level.ServerLevel hosting =
            (net.minecraft.server.level.ServerLevel) this.subLevel.getLevel();
        // A drag owns one native joint for its lifetime. Recreating it each substep grows
        // Rapier's retired-joint/island work for the entire held duration.
        if (this.constraint != null && !this.constraint.isValid()) {
            this.constraint = null;
        }
        if (this.constraint == null) this.attachConstraint(physicsSystem);

        ServerPlayer player = hosting.getServer().getPlayerList().getPlayer(this.playerUUID);
        if (player == null || this.constraint == null) return;

        SimPhysics config = SimConfigService.INSTANCE.server().physics;
        // Rapier can put an exactly stationary held body to sleep. A motor target update alone
        // does not reliably wake that body, leaving a live staff session that needs a second grab.
        // A live drag is an explicit external control, so wake it before applying this substep's goal.
        physicsSystem.getPipeline().wakeUp(this.subLevel);
        this.ipl$setMotorFrame(physicsSystem.getPipeline());
        for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
            this.constraint.setMotor(axis, 0.0, config.physicsStaffAngularStiffness.getF(),
                config.physicsStaffAngularDamping.getF(), false, 0.0);
        }

        double partial = physicsSystem.getPartialPhysicsTick();
        double eyeX = Mth.lerp(partial, player.xOld, player.getX());
        double eyeY = Mth.lerp(partial, player.yOld, player.getY()) + player.getEyeHeight();
        double eyeZ = Mth.lerp(partial, player.zOld, player.getZ());
        this.localGoal.set(this.playerRelativeGoal).add(eyeX, eyeY, eyeZ);

        // The absolute cursor point is in the PLAYER's world frame; fold it through the
        // grab chain (the event-sourced portal path between player and body) into the
        // body's parent frame, immediately before Simulated converts world space into
        // its constraint-local motor target.
        net.minecraft.world.phys.Vec3 goal = ipl.sable.transit.IplGrabChain.mapGoal(
            player, this.subLevel, new net.minecraft.world.phys.Vec3(
                this.localGoal.x, this.localGoal.y, this.localGoal.z
            )
        );
        this.localGoal.set(goal.x, goal.y, goal.z);

        // VELOCITY LEAD -- this is the millimetre dip when a held body enters a portal.
        //
        // Sable's motor is a pure POSITION spring: setMotor's last parameter is a force
        // limit, not a target velocity, so the solver has no feed-forward term at all.
        // Tracking a target that MOVES therefore requires a permanent error -- at constant
        // speed the spring must stay stretched by exactly e = (damping / stiffness) * v to
        // produce the force the damper consumes. The held body flies at that fixed lag
        // behind the cursor, which is invisible while nothing changes.
        //
        // It stops being invisible at a portal. The lag vector lives in the PLAYER's frame
        // before the crossing and in the destination frame after it, and the chain gains a
        // link on exactly the tick the parent flips. The stored error is re-expressed in
        // one step, so the spring is momentarily stretched by the wrong amount, the body
        // gives up that difference (the ~1 mm slow-down) and then re-accelerates as the
        // error rebuilds in the new frame. Rotating portals make it larger, but even an
        // identity portal shows it because the lag is rebuilt from zero.
        //
        // Cancelling the lag analytically removes the transient with it: lead the target
        // by the amount the spring would otherwise have to fall behind. With the lead in
        // place the steady-state error is ~0 in EVERY frame, so there is nothing left for
        // the crossing to re-express.
        double leadStiffness = config.physicsStaffLinearStiffness.getF();
        double leadDamping = config.physicsStaffLinearDamping.getF();
        if (leadStiffness > 1.0e-6 && leadDamping > 0.0) {
            // The feed-forward term is the velocity of the TARGET, and the target moves
            // for three independent reasons: the player walks, the player turns, and the
            // player scrolls the hold distance. Deriving it from the player's position
            // delta alone only covered the first one, so a body carried by look or scroll
            // still ran with a permanent spring error -- which is precisely the case where
            // the "seamless" crossing was observed to lose speed. Differentiating the
            // already-mapped goal itself covers all three by construction, and it is taken
            // in the body's own frame, so no direction mapping is needed either.
            double nowTicks = hosting.getGameTime() + partial;
            double vx = 0.0;
            double vy = 0.0;
            double vz = 0.0;
            if (!Double.isNaN(this.ipl$previousGoalTime)) {
                double dt = nowTicks - this.ipl$previousGoalTime;
                // Below a substep the quotient is noise; above a few ticks the sample is
                // from a different situation entirely (lag spike, reload).
                if (dt > 1.0e-3 && dt <= 4.0) {
                    vx = (this.localGoal.x - this.ipl$previousGoal.x) / dt;
                    vy = (this.localGoal.y - this.ipl$previousGoal.y) / dt;
                    vz = (this.localGoal.z - this.ipl$previousGoal.z) / dt;
                }
            }
            this.ipl$previousGoal.set(this.localGoal);
            this.ipl$previousGoalTime = nowTicks;
            // Goal velocity is per TICK; Rapier motor gains are per SECOND.
            double lead = leadDamping / leadStiffness * 20.0;
            double lx = vx * lead;
            double ly = vy * lead;
            double lz = vz * lead;
            double magnitude = Math.sqrt(lx * lx + ly * ly + lz * lz);
            // A teleport or a chunk-load hitch can produce an absurd one-tick delta; the
            // lead must never become a slingshot.
            if (magnitude > IPL$MAX_LEAD_BLOCKS) {
                double shrink = IPL$MAX_LEAD_BLOCKS / magnitude;
                lx *= shrink;
                ly *= shrink;
                lz *= shrink;
            }
            this.localGoal.add(lx, ly, lz);
        }

        this.orientation.transformInverse(this.localGoal);

        this.constraint.setMotor(ConstraintJointAxis.LINEAR_X, this.localGoal.x,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Y, this.localGoal.y,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Z, this.localGoal.z,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
    }

    /**
     * Parent flip runs after the current physics tick. Without this immediate reframe, the
     * existing native joint retains its source-frame target until the next substep and pulls
     * the fully crossed body back once: the visible handoff jerk on every portal, including
     * portals with identity rotation.
     */
    @Override
    public void ipl$reframeAfterTransit(qouteall.imm_ptl.core.portal.Portal portal) {
        if (this.subLevel.isRemoved()) return;

        // localGoal is the live motor target in the old orientation basis. Recover its world
        // point, map it through the same portal used for the body, then encode it in the new
        // basis before another solver substep can see a stale target.
        Vector3d target = new Vector3d(this.localGoal);
        this.orientation.transform(target);
        net.minecraft.world.phys.Vec3 mapped = portal.transformPoint(
            new net.minecraft.world.phys.Vec3(target.x, target.y, target.z));
        qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
        if (rotation != null) {
            this.orientation.set(new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w)
                .mul(this.orientation));
        }
        this.localGoal.set(mapped.x, mapped.y, mapped.z);
        // Carry the feed-forward history through the same isometry. Dropping it here would
        // make the very first post-crossing substep run with zero lead -- the one substep
        // where the lead exists to matter -- and mapping it keeps the derivative continuous
        // instead of producing one enormous bogus sample.
        net.minecraft.world.phys.Vec3 mappedPrevious = portal.transformPoint(
            new net.minecraft.world.phys.Vec3(
                this.ipl$previousGoal.x, this.ipl$previousGoal.y, this.ipl$previousGoal.z));
        this.ipl$previousGoal.set(mappedPrevious.x, mappedPrevious.y, mappedPrevious.z);
        this.orientation.transformInverse(this.localGoal);

        if (this.constraint == null || !this.constraint.isValid()) return;
        net.minecraft.server.level.ServerLevel hosting =
            (net.minecraft.server.level.ServerLevel) this.subLevel.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(hosting);
        if (container == null) return;
        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        this.ipl$setMotorFrame(pipeline);

        SimPhysics config = SimConfigService.INSTANCE.server().physics;
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_X, this.localGoal.x,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Y, this.localGoal.y,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Z, this.localGoal.z,
            config.physicsStaffLinearStiffness.getF(), config.physicsStaffLinearDamping.getF(), false, 0.0);
    }

    /** Keep the stored cursor vector continuous when the dragging player crosses a portal. */
    @Override
    public void ipl$rotateRelativeGoal(org.joml.Quaterniondc portalRotation) {
        portalRotation.transform(this.playerRelativeGoal);
    }

    @Override
    public void ipl$removeConstraint() {
        PhysicsConstraintHandle handle = this.constraint;
        this.constraint = null;
        // Match Simulated's release semantics: native removal is idempotent, while asking
        // validity first can miss a joint during its retirement window.
        if (handle != null) handle.remove();
    }

    /**
     * A free constraint bakes its motor basis at creation. Stock recreated it each substep
     * to refresh that basis, which accumulated retired-joint/island work. Update the native
     * frame in place instead: current held orientation, one live joint, no allocation churn.
     */
    private void ipl$setMotorFrame(PhysicsPipeline pipeline) {
        if (!(this.constraint instanceof RapierConstraintHandle handle)) return;
        // FreeConstraintConfiguration installs orientation on static-world endpoint (frame 1)
        // and plotAnchor on grabbed body endpoint (frame 2). Updating both prevents a newly
        // created session from briefly grabbing body COM before its clicked anchor arrives.
        Rapier3D.setConstraintFrame(
            ((ipl.sable.mixin.IplRapierPipelineAccess) pipeline).ipl$sceneHandle(),
            ((ipl.sable.mixin.IplRapierConstraintHandleAccessor) handle).ipl$getNativeHandle(), 0,
            0.0, 0.0, 0.0,
            this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w
        );
        Rapier3D.setConstraintFrame(
            ((ipl.sable.mixin.IplRapierPipelineAccess) pipeline).ipl$sceneHandle(),
            ((ipl.sable.mixin.IplRapierConstraintHandleAccessor) handle).ipl$getNativeHandle(), 1,
            this.plotAnchor.x, this.plotAnchor.y, this.plotAnchor.z,
            0.0, 0.0, 0.0, 1.0
        );
    }

    /**
     * Simulated removes sessions without stopDragging when the player logs off or stops
     * holding the staff; the grab chain must die with its session, never outlive it.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
        method = "onRemoved", at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
        remap = false, require = 0
    )
    private void ipl$endChainOnRemoval(
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        this.ipl$removeConstraint();
        net.minecraft.server.level.ServerLevel hosting =
            (net.minecraft.server.level.ServerLevel) this.subLevel.getLevel();
        if (hosting != null && hosting.getServer() != null) {
            ipl.sable.transit.IplGrabChain.end(hosting.getServer(), this.playerUUID);
        }
    }
}
