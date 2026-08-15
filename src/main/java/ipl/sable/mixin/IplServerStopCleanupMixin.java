package ipl.sable.mixin;

import ipl.sable.transit.SableTransitController;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears hosted transit state when the server stops — at stopServer RETURN, not HEAD:
 * the final world save runs INSIDE stopServer, and the ship-portal anchor map must
 * still be populated when portal entities serialize (the persistence tag is derived
 * from it; clearing at HEAD silently saved every portal unanchored). By stop time
 * nothing ticks these maps, so late clearing is safe for all of them.
 */
@Mixin(MinecraftServer.class)
public abstract class IplServerStopCleanupMixin {

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void ipl$cleanupOnStop(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        try {
            SableTransitController.onServerStopping(server);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("ipl-sable-transit")
                .warn("[IPL-TRANSIT] server-stop cleanup threw", t);
        }
    }
}
