package ipl.sable.mixin.client;

import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridge block-change predictions across the hosting-dim seam.
 *
 * <p>When a player breaks/places a block on a hosted sub-level, the client-side prediction
 * is recorded in the PLAYER's level ({@code BlockStatePredictionHandler}); the server's
 * confirming block update, however, arrives dim-stamped to {@code ipl_sable:sublevels} and
 * is applied under THAT level. The player's prediction is never marked server-verified, so
 * the following ack packet rolls the block back to its pre-action state — visually undoing
 * the break (and writing the stale state into the shared plot chunk).
 *
 * <p>Fix: when the hosting client level receives a server-verified state at any position,
 * forward it to EVERY other client level's prediction handler. Originally only the
 * player's current level was bridged, but interactions routed through IP's cross-portal
 * block manipulation book their predictions on the REMOTE world's handler (and a
 * through-part near the portal aperture takes that path even when the player aims from
 * its own side) — an unverified prediction there rolled back into a ghost block. Plot
 * coordinates are globally unique, so fanning the confirmation out to all levels is
 * unambiguous.
 */
@Mixin(ClientLevel.class)
public abstract class IplPredictionBridgeMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void ipl$confirmPredictionOnAllLevels(
        BlockPos pos, BlockState state, int flags, CallbackInfo ci
    ) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (!IplDimAgnostic.isHostingLevel(self)) return;

        for (ClientLevel clientLevel : qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds()) {
            if (clientLevel == self) continue;
            ((IplClientLevelPredictionAccessor) clientLevel)
                .ipl$getBlockStatePredictionHandler()
                .updateKnownServerState(pos, state);
        }
    }
}
