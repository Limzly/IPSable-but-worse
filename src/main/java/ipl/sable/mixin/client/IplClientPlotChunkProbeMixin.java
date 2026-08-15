package ipl.sable.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY diagnostic for the dim-agnostic bring-up: log every plot-range chunk packet
 * (|coord| beyond 100k chunks — the plot grid lives around 1.28M) as it reaches the client
 * packet listener, together with the level it will be applied to. Distinguishes
 * "packet never arrived" / "arrived under the wrong level" / "arrived correctly but the
 * plot routing dropped it". Remove once hosted rendering is stable.
 */
@Mixin(ClientPacketListener.class)
public abstract class IplClientPlotChunkProbeMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void ipl$probePlotChunkArrival(
        ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci
    ) {
        int x = packet.getX();
        int z = packet.getZ();
        if (Math.abs(x) > 100_000 || Math.abs(z) > 100_000) {
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-gather").info(
                "[IPL-CHUNK-PROBE] plot-range chunk packet ({}, {}) handled under level={}",
                x, z, this.level == null ? "null" : this.level.dimension().location());
        }
    }
}
