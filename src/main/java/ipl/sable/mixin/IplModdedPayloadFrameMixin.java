package ipl.sable.mixin;

import ipl.sable.dim.IplPacketFrameBridge;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Supplier;

/**
 * The universal packet bridge's dispatch seam. Every registrar-registered serverbound
 * payload handler's main-thread body runs through
 * {@link ServerPayloadContext#enqueueWork} (NeoForge wraps MAIN-thread handlers in it at
 * dispatch; network-thread handlers use it explicitly for world work) — wrapping the
 * scheduled task in a deferred world-frame there covers every addon's packets with ONE
 * injection. The frame arms itself on the handler's first hosted-plot resolution (see
 * {@code IplWorldFrameContext.notifyPlotResolved}); handlers that never touch a ship pay
 * two thread-local writes.
 *
 * <p>Canonical case: Simulated's assembler packet — handler finds the assembler BE at
 * plot coords (arms the frame), then its terrain scan around the ship routes to the
 * parent dimension via {@code IplHostedWorldFrameRouterMixin} instead of reading the
 * hosting void.
 */
@Mixin(value = ServerPayloadContext.class, remap = false)
public abstract class IplModdedPayloadFrameMixin {

    @ModifyVariable(
        method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private Runnable ipl$frameRunnable(Runnable task) {
        return IplPacketFrameBridge.wrap(task);
    }

    @ModifyVariable(
        method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private Supplier<Object> ipl$frameSupplier(Supplier<Object> task) {
        return IplPacketFrameBridge.wrap(task);
    }
}
