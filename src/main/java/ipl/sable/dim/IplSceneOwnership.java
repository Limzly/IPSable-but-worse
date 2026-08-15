package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas body ownership.
 *
 * <p>Atlas keeps each hosted sub-level's real body with its real level:
 * {@code ipl_sable:sublevels}. Parent dimensions receive Rapier image colliders, not moved
 * bodies. This preserves Sable's native invariant for every third-party caller: block entity,
 * plot, physics pipeline, rope/joint registry, and body all name the same real dimension.
 *
 * <ul>
 *   <li>{@link #owningLevel}: always the sub-level's real storage level.</li>
 *   <li>{@link #bodyHome}: retained as liveness bookkeeping for native teardown guards.</li>
 *   <li>{@link #reconcile}: maintains parent-chart images; it never migrates a real body.</li>
 * </ul>
 *
 */
public final class IplSceneOwnership {

    /** Where each hosted body currently lives (recorded by the routed pipeline add/remove). */
    private static final Map<UUID, ServerLevel> bodyHome = new HashMap<>();
    /** Parent chart -> current hosted bodies. Rebuilt once from the hosting container. */
    private static final Map<ServerLevel, List<ServerSubLevel>> bodiesByParent = new HashMap<>();

    private IplSceneOwnership() {}

    public static boolean isEnabled() {
        return true;
    }

    /** The level whose Rapier chart owns this real body: always its actual Sable level. */
    public static ServerLevel owningLevel(ServerSubLevel sub) {
        return (ServerLevel) sub.getLevel();
    }

    @Nullable
    public static RapierPhysicsPipeline pipelineOf(@Nullable ServerLevel level) {
        if (level == null) return null;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        return container.physicsSystem().getPipeline() instanceof RapierPhysicsPipeline rapier
            ? rapier : null;
    }

    @Nullable
    public static RapierPhysicsPipeline owningPipeline(ServerSubLevel sub) {
        return pipelineOf(owningLevel(sub));
    }

    /**
     * Teardown-safe native scene handle for {@code level}'s pipeline, or 0 when there is
     * no live scene. Liveness is checked via the raw scene FIELD — the sceneHandle invoker
     * dereferences the scene and NPEs once the pipeline is torn down (server-stop closes
     * levels overworld-first, so cross-level native ops must always check).
     */
    public static long liveSceneHandle(@Nullable ServerLevel level) {
        RapierPhysicsPipeline pipeline = pipelineOf(level);
        if (pipeline == null) return 0;
        ipl.sable.mixin.IplRapierPipelineAccess access =
            (ipl.sable.mixin.IplRapierPipelineAccess) pipeline;
        return access.ipl$scene() == null ? 0 : access.ipl$sceneHandle();
    }

    // ------------------------------------------------------------------
    // Body-home bookkeeping (called from the guard mixin's add/remove routing).
    // ------------------------------------------------------------------

    public static void recordBodyAdded(ServerSubLevel sub, ServerLevel home) {
        bodyHome.put(sub.getUniqueId(), home);
    }

    public static void recordBodyRemoved(ServerSubLevel sub) {
        bodyHome.remove(sub.getUniqueId());
    }

    @Nullable
    public static ServerLevel getBodyHome(ServerSubLevel sub) {
        return bodyHome.get(sub.getUniqueId());
    }

    /** Hosted bodies belonging to this parent chart; empty until the hosting container ticks. */
    public static List<ServerSubLevel> hostedInParent(ServerLevel parent) {
        List<ServerSubLevel> bodies = bodiesByParent.get(parent);
        return bodies == null ? List.of() : bodies;
    }

    /** Server stopping: drop all state. */
    public static void clearAll() {
        ipl.sable.atlas.IplAtlasBodyImages.clearAll();
        ipl.sable.atlas.IplHostedTerrainGate.clearAll();
        ipl.sable.atlas.IplParentFrames.clearAll();
        bodyHome.clear();
        bodiesByParent.clear();
    }

    // ------------------------------------------------------------------
    // Migration + reconciliation.
    // ------------------------------------------------------------------

    /**
     * Legacy compatibility entry point. Atlas deliberately never migrates real bodies across
     * charts: crossing a portal changes the parent image, not the body's owning dimension.
     */
    public static void migrate(ServerSubLevel sub, ServerLevel fromLevel, ServerLevel toLevel) {
        recordBodyAdded(sub, (ServerLevel) sub.getLevel());
    }

    /**
     * Maintain the parent-chart image of every real hosted body. Parent flips only replace
     * that image; no third-party state is re-homed or context-routed.
     */
    public static void reconcile(ServerSubLevelContainer hostingContainer) {
        if (!isEnabled()) return;

        Map<ServerLevel, List<ServerSubLevel>> rebuilt = new HashMap<>();
        for (ServerSubLevel sub : hostingContainer.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            if (!IplDimAgnostic.isHosted(sub)) continue;

            recordBodyAdded(sub, (ServerLevel) sub.getLevel());
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
            if (parent != null) {
                rebuilt.computeIfAbsent(parent, ignored -> new java.util.ArrayList<>()).add(sub);
            }
        }
        bodiesByParent.clear();
        bodiesByParent.putAll(rebuilt);
        ipl.sable.atlas.IplAtlasBodyImages.reconcileAll(hostingContainer.getAllSubLevels());
        ipl.sable.atlas.IplParentFrames.reconcile(hostingContainer.getAllSubLevels());
        ipl.sable.atlas.IplHostedTerrainGate.retainLive(hostingContainer.getAllSubLevels());
    }

}
