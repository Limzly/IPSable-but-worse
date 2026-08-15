package ipl.sable.mixin;

import ipl.sable.transit.IplGrabChain;
import ipl.sable.duck.IplStaffDragSessionControl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Hook actual drag-session writes, after routing made `level` the hosting level. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public abstract class IplStaffServerStateMixin {

    @Shadow(remap = false) private ServerLevel level;
    @Inject(method = "drag", at = @At("HEAD"), require = 0)
    private void ipl$beginFrame(
        UUID playerId, UUID subId, Vector3dc playerRelativeGoal, Vector3dc localAnchor,
        Quaterniondc orientation, CallbackInfo ci
    ) {
        ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) IplGrabChain.begin(player, subId);
    }

    /**
     * A body transit is server-initiated: drag packets the client composed BEFORE it
     * processed the ordered rebase RPC still carry the pre-transit orientation and would
     * stomp the server-side reframe for a tick. Premultiply those packets by the pending
     * transit rotations until the client acknowledges — exact via TCP ordering.
     */
    @ModifyVariable(method = "drag", at = @At("HEAD"), argsOnly = true, require = 0)
    private Quaterniondc ipl$compensateUnackedTransits(
        Quaterniondc orientation,
        UUID playerId, UUID subId, Vector3dc playerRelativeGoal, Vector3dc localAnchor,
        Quaterniondc orientationArg
    ) {
        return IplGrabChain.adjustIncomingOrientation(playerId, new Quaterniond(orientation));
    }

    @Inject(method = "stopDragging", at = @At("HEAD"), require = 0)
    private void ipl$endFrame(UUID playerId, CallbackInfo ci) {
        Object session = ((IplStaffServerHandlerAccessorMixin) (Object) this)
            .ipl$getDraggingSessions().get(playerId);
        if (session instanceof IplStaffDragSessionControl control) {
            control.ipl$removeConstraint();
        }
        IplGrabChain.end(this.level.getServer(), playerId);
    }

}
