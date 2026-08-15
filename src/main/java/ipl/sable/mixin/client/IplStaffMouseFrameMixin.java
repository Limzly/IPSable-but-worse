package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.config.client.items.SimItemConfigs;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.service.SimConfigService;
import dev.simulated_team.simulated.util.click_interactions.InteractCallback;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Simulated applies pitch around the player's world-space view axis. For a body shown through a
 * portal, that axis must be expressed in the body's native parent frame before it reaches the
 * server-side constraint; otherwise each small mouse movement injects a rotated-world command.
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsStaffMouseHandler",
    remap = false
)
public abstract class IplStaffMouseFrameMixin {

    /**
     * @author IPL-Sable
     * @reason Convert a through-portal pitch axis into the grabbed body's constraint frame.
     */
    @Overwrite(remap = false)
    public InteractCallback.Result onMouseMove(double yaw, double pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        PhysicsStaffClientHandler handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;

        if (!((IplPhysicsStaffClientHandlerStateAccessorMixin) (Object) handler)
            .ipl$isRotating()) {
            return new InteractCallback.Result(false);
        }

        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        if (session == null || minecraft.player == null) {
            return new InteractCallback.Result(true);
        }

        Vec3 axis = minecraft.player.calculateViewVector(0.0f, minecraft.player.getYRot() - 90.0f);
        SubLevel sub = session.dragSubLevel();
        if (sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub) {
            axis = IplStraddleStaffPick.unmapStaffInputAxis(minecraft.player, clientSub, axis);
        }

        Quaterniond orientation = session.dragOrientation();
        SimItemConfigs config = SimConfigService.INSTANCE.client().itemConfig;
        double sensitivity = config.physicsStaffRotateSensitivity.get();
        orientation.rotateLocalY(Math.toRadians(yaw) * sensitivity);
        orientation.premul(new Quaterniond(new AxisAngle4d(
            Math.toRadians(-pitch) * sensitivity, axis.x, axis.y, axis.z
        )));
        return new InteractCallback.Result(true);
    }
}
