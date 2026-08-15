package ipl.sable.atlas;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableEventPublishPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.mixin.IplPhysicsSystemFusionAccess;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Atlas M1 (spec v3 §2.7): the fused physics step. Under the atlas natives all
 * dimensions share ONE Rapier world, so the per-level {@code tickPipelinePhysics}
 * loop must run once per SERVER tick for all levels together — the first unpaused
 * system whose pipeline tick fires becomes the owner and drives every system's
 * substep phases in {@code getAllLevels()} order, with exactly one native step per
 * substep (armed on the LAST pipeline so every pipeline's contraption poses are
 * pushed before the world steps).
 *
 * <p>Per-level force timing: the owner level (normally the overworld, which ticks
 * first) keeps exactly stock timing. Other levels' per-substep force passes run at
 * owner time — their own gameplay ticks later in the same server tick observe
 * fresh post-step poses, and any forces their gameplay queues land next tick
 * (one-tick lag, accepted in the M1 design).
 *
 * <p>Per-dimension physics PAUSE degrades under one world: a paused level's phases
 * (forces, pose readback, events) are skipped, but its bodies keep simulating with
 * the world. Logged once when observed.
 */
public final class IplFusedStep {

    private static final Logger LOG = LogManager.getLogger("IPL-ATLAS");

    /** Whether the currently executing RapierPhysicsPipeline.physicsTick may step natively. */
    public static boolean STEP_ARMED = false;

    private static long lastFusedTick = Long.MIN_VALUE;
    private static boolean loggedPauseSkew = false;
    private static boolean loggedSubstepSkew = false;

    private IplFusedStep() {}

    /**
     * Called from the HEAD of every system's {@code tickPipelinePhysics}. Returns
     * {@code true} if this call has been handled (always — the caller cancels): the
     * first system per server tick drives the fused loop, later systems no-op.
     */
    public static void onTickPipelinePhysics(SubLevelPhysicsSystem system) {
        MinecraftServer server = system.getLevel().getServer();
        long tick = server.getTickCount();
        if (tick == lastFusedTick) {
            return; // already fused this server tick
        }
        lastFusedTick = tick;
        driveFused(server);
    }

    private record Entry(ServerLevel level, SubLevelPhysicsSystem sys, ServerSubLevelContainer container) {}

    private static void driveFused(MinecraftServer server) {
        List<Entry> systems = new ArrayList<>();
        boolean anyPaused = false;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            SubLevelPhysicsSystem sys = SubLevelPhysicsSystem.get(level);
            if (sys == null) {
                continue;
            }
            if (sys.getPaused()) {
                anyPaused = true;
                continue;
            }
            systems.add(new Entry(level, sys, container));
        }
        if (systems.isEmpty()) {
            return;
        }
        if (anyPaused && !loggedPauseSkew) {
            loggedPauseSkew = true;
            LOG.info("[IPL-ATLAS] a dimension has physics paused: under the fused world its "
                + "phases are skipped but its bodies keep simulating (logged once)");
        }

        SubLevelPhysicsSystem owner = systems.get(0).sys();
        int substeps = owner.getConfig().substepsPerTick;
        for (Entry e : systems) {
            if (e.sys().getConfig().substepsPerTick != substeps && !loggedSubstepSkew) {
                loggedSubstepSkew = true;
                LOG.warn("[IPL-ATLAS] substepsPerTick differs between dimensions; using the "
                    + "owner's value {} (logged once)", substeps);
            }
        }
        double dt = 1.0 / 20.0 / substeps;

        for (Entry e : systems) {
            e.sys().getPipeline().prePhysicsTicks();
        }

        for (int s = 0; s < substeps; s++) {
            final int substep = s;
            for (Entry e : systems) {
                // Stock semantics: currentSubstep is the loop variable, set BEFORE the
                // pre-phases (getPartialPhysicsTick feeds updateMergedMassData).
                ((IplPhysicsSystemFusionAccess) e.sys()).ipl$setCurrentSubstep(substep);
                runGuarded(e, () -> preSubstepPhases(e, dt));
            }

            // Every pipeline runs its full physicsTick (contraption poses + wakeups);
            // only the LAST call's native step is armed, so all charts' kinematic
            // poses are current when the one world step runs.
            SubLevelPhysicsSystem.IN_PHYSICS_STEP = true;
            for (int i = 0; i < systems.size(); i++) {
                Entry e = systems.get(i);
                boolean last = i == systems.size() - 1;
                runGuarded(e, () -> {
                    STEP_ARMED = last;
                    try {
                        e.sys().getPipeline().physicsTick(dt);
                    } finally {
                        STEP_ARMED = false;
                    }
                });
            }
            SubLevelPhysicsSystem.IN_PHYSICS_STEP = false;

            for (Entry e : systems) {
                runGuarded(e, () -> postSubstepPhases(e, dt));
            }
        }

        for (Entry e : systems) {
            ((IplPhysicsSystemFusionAccess) e.sys()).ipl$setCurrentSubstep(substeps);
            runGuarded(e, () -> e.sys().getPipeline().postPhysicsTicks());
        }

        // Atlas M6: ship-anchored portals follow their ships' just-published poses;
        // straddle sessions then pick the moved portals up via the M5a refresh.
        ipl.sable.transit.IplShipPortalAnchor.tickAll(server);
    }

    private static void preSubstepPhases(Entry e, double dt) {
        SubLevelPhysicsSystem sys = e.sys();
        SubLevelPhysicsSystem.currentlySteppingSystem = sys;
        ServerSubLevelContainer container = e.container();

        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.prePhysicsTickBegin();
        }
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.updateMergedMassData((float) sys.getPartialPhysicsTick());
        }
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.prePhysicsTick(sys, sys.getPhysicsHandle(subLevel), dt);
        }
        SableEventPublishPlatform.INSTANCE.prePhysicsTick(sys, dt);
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.applyQueuedForces(sys, sys.getPhysicsHandle(subLevel), dt);
        }
    }

    private static void postSubstepPhases(Entry e, double dt) {
        SubLevelPhysicsSystem sys = e.sys();
        SubLevelPhysicsSystem.currentlySteppingSystem = sys;
        IplPhysicsSystemFusionAccess access = (IplPhysicsSystemFusionAccess) sys;

        e.container().processSubLevelRemovals();
        access.ipl$updateAllPoses(e.container());

        var wakeUps = access.ipl$queuedWakeUps();
        for (var object : wakeUps) {
            object.wakeUp();
        }
        wakeUps.clear();

        SableEventPublishPlatform.INSTANCE.postPhysicsTick(sys, dt);
    }

    /** Mirror stock's crash-report framing, naming the dimension whose phases failed. */
    private static void runGuarded(Entry e, Runnable phases) {
        SubLevelPhysicsSystem.currentlySteppingSystem = e.sys();
        try {
            phases.run();
        } catch (Exception ex) {
            CrashReport crashReport = CrashReport.forThrowable(ex, "Sable ticking physics (IPL fused step)");
            CrashReportCategory category = crashReport.addCategory("Current physics state");
            category.setDetail("Dimension", e.level().dimension());
            throw new ReportedException(crashReport);
        }
    }
}
