package ipl.sable.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep queued-rehome snapshots in one source frame until the visual handoff commits. */
@Pseudo
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public abstract class IplPendingHandoffSnapshotMixin {

    @Inject(method = "receiveSnapshot", at = @At("HEAD"), cancellable = true, remap = false,
        require = 0)
    private void ipl$keepPendingSnapshotsInSourceFrame(
        ClientSubLevel subLevel, int gameTick, Pose3dc pose, PacketReceiveMode receiveMode,
        CallbackInfo ci
    ) {
        Pose3dc sourceFramePose = IplParentDimSync.mapPendingDestinationSnapshotBack(
            subLevel.getUniqueId(), pose);
        if (sourceFramePose == pose) return;
        subLevel.getInterpolator().receiveSnapshot(gameTick, sourceFramePose);
        ci.cancel();
    }
}
