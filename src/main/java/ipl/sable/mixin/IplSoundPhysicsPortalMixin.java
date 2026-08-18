package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.List;

/**
 * Makes Sound Physics Aeronautics portal-aware.
 *
 * When a sound plays from a position that is near a portal connecting
 * to another dimension, this mixin transforms the sound position to
 * the portal surface in the player's dimension. This gives:
 * - Correct distance attenuation (player -> portal, not straight line to source)
 * - Correct occlusion for the player -> portal segment
 * - All SPA sound effects apply normally (reverb, absorption, etc.)
 *
 * The trade-off: occlusion for the portal -> source segment is lost
 * (sound comes through the portal unobstructed from that side). This
 * is a good first approximation and much better than the current
 * behavior where sounds from the wrong dimension play at full volume.
 *
 * Soft-applies via @Pseudo so it no-ops if SPA is not present.
 */
@Pseudo
@Mixin(targets = "com.sonicether.soundphysics.SoundPhysics", remap = false)
public class IplSoundPhysicsPortalMixin {

    /**
     * Intercept evaluateEnvironment and transform the sound position
     * if it's near a portal that connects to another dimension.
     *
     * The method signature in SPA is:
     *   private static Vec3 evaluateEnvironment(int sourceID, double posX, double posY, double posZ, ...)
     *
     * We modify args 1, 2, 3 (posX, posY, posZ) via @ModifyArgs.
     */
    @ModifyArgs(
        method = "evaluateEnvironment",
        at = @At("HEAD"),
        require = 0
    )
    private static void ip_transformSoundThroughPortal(Args args) {
        // Get the sound position from args
        // evaluateEnvironment signature: (int sourceID, double posX, double posY, double posZ, ...)
        double posX = args.get(1);
        double posY = args.get(2);
        double posZ = args.get(3);

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        Vec3 soundPos = new Vec3(posX, posY, posZ);
        Vec3 playerPos = client.player.position();

        // Don't transform if the sound is very close to the player
        if (soundPos.distanceToSqr(playerPos) < 1.0) return;

        // Find nearby portals that connect to a different dimension
        List<Portal> portals = IPMcHelper.getNearbyPortalList(
            client.level, playerPos, 64, p -> true
        );

        for (Portal portal : portals) {
            // Check if the portal connects to a different dimension
            if (portal.getDestDim() == null) continue;
            if (portal.getDestDim().equals(client.level.dimension())) continue;

            // Check if the sound position is on the destination side of the portal
            // (i.e., the sound is in the other dimension and visible through the portal)
            // We check if the sound is closer to the portal's destination position
            Vec3 portalOrigin = portal.getOriginPos();
            Vec3 portalDest = portal.getDestPos();

            // Transform the sound position through the portal to get the apparent position
            // in the player's dimension. If the sound is on the destination side, its
            // apparent position is at the portal surface.
            double distToOrigin = soundPos.distanceToSqr(portalOrigin);
            double distToDest = soundPos.distanceToSqr(portalDest);

            // If the sound is closer to the destination, it's from the other dimension
            if (distToDest < distToOrigin) {
                // Transform the sound position through the portal
                // This gives us where the sound appears to come from in the player's dimension
                // For sounds far from the portal, the apparent position is at the portal surface
                Vec3 apparentPos;

                // Get the direction from portal origin to sound (in destination dimension)
                Vec3 dirToSound = soundPos.subtract(portalDest);
                double soundDist = dirToSound.length();

                // The apparent position is at the portal surface, offset toward the player
                // by a small amount to ensure the sound is audible
                Vec3 portalNormal = portal.getContentDirection().getNormal();
                Vec3 portalCenter = portalOrigin;

                // Project the player position onto the portal plane
                Vec3 playerToPortal = portalCenter.subtract(playerPos);
                double playerPortalDist = playerToPortal.dot(portalNormal);

                // The apparent position is at the portal surface
                // Offset slightly toward the player so the sound is audible
                apparentPos = portalCenter.add(portalNormal.scale(0.1));

                // Adjust volume based on distance from portal to sound source
                // The farther the sound is from the portal, the quieter it should be
                // We do this by moving the apparent position away from the player
                // proportional to the source distance
                double attenuationFactor = Math.min(1.0, soundDist / 64.0);
                Vec3 awayFromPlayer = apparentPos.subtract(playerPos).normalize();
                apparentPos = apparentPos.add(awayFromPlayer.scale(attenuationFactor * soundDist * 0.5));

                // Set the transformed position back into the args
                args.set(1, apparentPos.x);
                args.set(2, apparentPos.y);
                args.set(3, apparentPos.z);
                return;
            }
        }
    }
}
