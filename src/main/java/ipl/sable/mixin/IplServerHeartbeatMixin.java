package ipl.sable.mixin;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Independent server-tick heartbeat. Logs {@code [IPL-HEARTBEAT] tick=N} every 5s
 * from {@link MinecraftServer#tickServer(BooleanSupplier)}'s TAIL. Lets us tell apart
 * three failure modes when the user reports a "hang":
 *
 * <ul>
 *   <li>Both this heartbeat and Sable's {@code IPL-MIRROR-SKIP} per-5s counter still
 *       firing: server is alive and ticking normally; whatever the user is seeing is
 *       a client-only stall or render issue.</li>
 *   <li>This heartbeat firing but Sable's {@code IPL-MIRROR-SKIP} stopped: server is
 *       alive, but Sable's {@code ServerSubLevelContainer.tick} has stopped being
 *       called (or its loop body broke). Sable-specific halt -- investigate the
 *       Sable tick path / mass tracker / removal-skip wrap.</li>
 *   <li>Both stopped: the {@link MinecraftServer} thread itself is frozen (deadlock
 *       or infinite loop). At that point a jstack on the JVM is the right next step
 *       -- the thread named {@code Server thread} will show the precise stuck frame.</li>
 * </ul>
 *
 * <p>Why TAIL of {@code tickServer}: we want one heartbeat per "complete tick" not
 * per intermediate ticking step. HEAD would fire even if the tick body throws or
 * deadlocks halfway through; TAIL only fires after a tick fully completed, which is
 * the more useful liveness signal.
 */
@Mixin(MinecraftServer.class)
public abstract class IplServerHeartbeatMixin {

    @Unique
    private static final Logger IPL$HEARTBEAT_LOG = LoggerFactory.getLogger("ipl-heartbeat");

    @Unique
    private static long ipl$tickCount = 0L;

    @Unique
    private static long ipl$lastReportNanos = 0L;

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void ipl$heartbeat(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // Feed the stall watchdog: records the server thread + a progress
        // timestamp each completed tick. If a future tick hangs, the watchdog
        // daemon dumps this thread's stuck stack (see IplServerWatchdog).
        ipl.sable.IplServerWatchdog.onTick(
            Thread.currentThread(), (MinecraftServer) (Object) this);

        ipl$tickCount++;
        long now = System.nanoTime();
        if (ipl$lastReportNanos == 0L) {
            ipl$lastReportNanos = now;
            return;
        }
        if (now - ipl$lastReportNanos >= 5_000_000_000L) {
            ipl$lastReportNanos = now;
            IPL$HEARTBEAT_LOG.info("[IPL-HEARTBEAT] tick={}", ipl$tickCount);
        }
    }
}
