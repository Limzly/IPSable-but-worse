package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Client-side, non-creating lookup of the {@code ipl_sable:sublevels} {@code ClientLevel} and
 * its sub-level container. Referenced from common code only behind {@code level.isClientSide}
 * branches, so this class never loads on a dedicated server.
 *
 * <p>Deliberately does NOT call {@code ClientWorldLoader.getWorld} — that would create the
 * remote world from inside hot query paths. The sublevels client world is created by IP's
 * packet redirection the moment the first hosted-sub-level sync arrives; until then there is
 * simply nothing hosted to query.
 */
public final class IplClientHostedLookup {

    private IplClientHostedLookup() {}

    @Nullable
    public static ClientLevel getHostingClientLevelOrNull() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return null;
        }
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            if (world.dimension() == SableSubLevelDimension.SUBLEVELS) {
                return world;
            }
        }
        return null;
    }

    @Nullable
    public static SubLevelContainer getHostingContainerOrNull() {
        ClientLevel hosting = getHostingClientLevelOrNull();
        return hosting == null ? null : SubLevelContainer.getContainer((net.minecraft.world.level.Level) hosting);
    }

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger("ipl-hosted-gather");

    /** Rate limiter for the gather diagnostic (ms wall clock). */
    private static long lastGatherLogMs = 0;

    /**
     * Hosted sub-levels that should appear in {@code level} — i.e. whose synced
     * {@code parentLevel} is that level and whose render data exists. Used by the render
     * mixins, which run once per {@code LevelRenderer} instance: the main pass and each IP
     * portal pass each query with their own level, giving per-dimension visibility for free.
     */
    public static java.util.List<dev.ryanhcode.sable.sublevel.ClientSubLevel> getHostedSubLevelsFor(
        @Nullable ClientLevel level
    ) {
        return IplStraddleRenderCache.hosted(level, () -> ipl$getHostedSubLevelsUncached(level));
    }

    private static java.util.List<dev.ryanhcode.sable.sublevel.ClientSubLevel> ipl$getHostedSubLevelsUncached(
        @Nullable ClientLevel level
    ) {
        if (level == null) {
            return java.util.List.of();
        }
        if (level.dimension() == SableSubLevelDimension.SUBLEVELS) {
            return java.util.List.of(); // never render inside the hosting dim's own pass
        }
        SubLevelContainer container = getHostingContainerOrNull();

        java.util.List<dev.ryanhcode.sable.sublevel.ClientSubLevel> out = null;
        int total = 0, parentOther = 0, nullRenderData = 0;
        if (container != null) {
            for (dev.ryanhcode.sable.sublevel.SubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved()) continue;
                if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) continue;
                total++;
                if (((ipl.sable.duck.IplSubLevelDuck) sub).ipl$getParentLevel() != level) {
                    parentOther++;
                    continue;
                }
                if (clientSub.getRenderData() == null) {
                    nullRenderData++;
                    continue;
                }
                if (out == null) out = new java.util.ArrayList<>(4);
                out.add(clientSub);
            }
        }

        // Diagnostic: prove the render hooks fire and show what the gather sees. Rate-limited;
        // remove once the dim-agnostic render path is stable.
        long now = System.currentTimeMillis();
        if (now - lastGatherLogMs > 5000) {
            lastGatherLogMs = now;
            int firstMatchedChunks = -1;
            Object firstPose = null;
            if (out != null && !out.isEmpty()) {
                firstMatchedChunks = out.get(0).getPlot().getLoadedChunks().size();
                firstPose = out.get(0).logicalPose().position();
            }
            LOG.info("[IPL-HOSTED-GATHER] level={} containerPresent={} total={} matched={} parentOther={} nullRenderData={} firstChunks={} firstPos={}",
                level.dimension().location(), container != null, total,
                out == null ? 0 : out.size(), parentOther, nullRenderData,
                firstMatchedChunks, firstPose);
        }

        return out == null ? java.util.List.of() : out;
    }

    /**
     * A straddling hosted sub-level's appearance in the DESTINATION dimension: the same
     * compiled geometry, drawn at the portal-mapped pose, clipped at the mapped portal
     * plane (keeping exactly the through-the-portal portion).
     */
    public record StraddleProjection(
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
        qouteall.imm_ptl.core.portal.Portal portal,
        dev.ryanhcode.sable.companion.math.Pose3d mappedPose,
        qouteall.q_misc_util.my_util.Plane destPlane
    ) {}

    /**
     * Straddle projections that should be drawn into {@code destLevel}'s render pass:
     * hosted sub-levels in SOME OTHER parent dim currently straddling a portal whose
     * destination is {@code destLevel}.
     */
    public static java.util.List<StraddleProjection> getStraddleProjectionsInto(ClientLevel destLevel) {
        return IplStraddleRenderCache.projections(
            destLevel, () -> ipl$getStraddleProjectionsUncached(destLevel));
    }

    @Nullable
    public static StraddleProjection getStraddleProjectionFor(ClientSubLevel clientSub) {
        if (!(((ipl.sable.duck.IplSubLevelDuck) clientSub).ipl$getParentLevel() instanceof ClientLevel)) {
            return null;
        }
        ipl.sable.render.SourceClipPortalFinder.ClipDecision decision =
            ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlane(clientSub);
        if (decision == null || decision.portal() == null) return null;

        Portal portal = decision.portal();
        Pose3d mapped = ipl$computeMappedPose(clientSub.renderPose(), portal);
        net.minecraft.world.phys.Vec3 srcPos = decision.plane().pos();
        net.minecraft.world.phys.Vec3 srcNormal = decision.plane().normal();
        qouteall.q_misc_util.my_util.Plane destPlane = new qouteall.q_misc_util.my_util.Plane(
            portal.transformPoint(srcPos), portal.transformLocalVec(srcNormal).scale(-1.0));
        return new StraddleProjection(clientSub, portal, mapped, destPlane);
    }

    private static java.util.List<StraddleProjection> ipl$getStraddleProjectionsUncached(ClientLevel destLevel) {
        if (destLevel == null) {
            return java.util.List.of();
        }
        if (destLevel.dimension() == SableSubLevelDimension.SUBLEVELS) {
            return java.util.List.of();
        }
        SubLevelContainer container = getHostingContainerOrNull();
        if (container == null) {
            return java.util.List.of();
        }

        java.util.List<StraddleProjection> out = null;
        for (dev.ryanhcode.sable.sublevel.SubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) continue;
            if (clientSub.getRenderData() == null) continue;

            net.minecraft.world.level.Level parent =
                ((ipl.sable.duck.IplSubLevelDuck) sub).ipl$getParentLevel();
            if (parent == null) continue;
            if (parent.dimension() == SableSubLevelDimension.SUBLEVELS) continue; // parent unset

            // ONE projection per session portal (multi-straddle): a ship crossing two
            // portals into this level projects two images, each with its own dest cut.
            for (ipl.sable.render.SourceClipPortalFinder.ClipDecision decision :
                    ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlanes(clientSub)) {
                if (decision.portal() == null) continue;
                if (decision.portal().getDestDim() != destLevel.dimension()) continue;

                qouteall.imm_ptl.core.portal.Portal portal = decision.portal();

                // Render the mapped half even when source and destination are the same level.
                dev.ryanhcode.sable.companion.math.Pose3d mapped =
                    ipl$computeMappedPose(clientSub.renderPose(), portal);

                // Clip plane: the source plane keeps the source half; through the portal
                // the kept half flips — n_dest = -R(n_src), anchored at the mapped point.
                net.minecraft.world.phys.Vec3 srcPos = decision.plane().pos();
                net.minecraft.world.phys.Vec3 srcNormal = decision.plane().normal();
                net.minecraft.world.phys.Vec3 destPos = portal.transformPoint(srcPos);
                net.minecraft.world.phys.Vec3 destNormal =
                    portal.transformLocalVec(srcNormal).scale(-1.0);
                qouteall.q_misc_util.my_util.Plane destPlane =
                    new qouteall.q_misc_util.my_util.Plane(destPos, destNormal);

                if (out == null) out = new java.util.ArrayList<>(2);
                out.add(new StraddleProjection(clientSub, portal, mapped, destPlane));
            }
        }
        return out == null ? java.util.List.of() : out;
    }

    /** Whether this hosted sub-level currently straddles a portal (client judgment). */
    public static boolean isClientStraddling(dev.ryanhcode.sable.sublevel.SubLevel sub) {
        if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) return false;
        return ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlane(clientSub) != null;
    }

    /**
     * Full isometry mapping {@code sub}'s source frame into {@code destLevel} — the
     * client collision/interaction mapping. Rotation-capable; scale stays gated at 1.
     */
    @Nullable
    public static ipl.sable.transit.IplStraddlePoseMap.StraddleMapping getClientStraddleMappingInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level destLevel
    ) {
        if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) return null;

        ipl.sable.render.SourceClipPortalFinder.ClipDecision decision =
            ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlane(clientSub);
        if (decision == null || decision.portal() == null) return null;
        qouteall.imm_ptl.core.portal.Portal portal = decision.portal();
        if (portal.getDestDim() != destLevel.dimension()) return null;

        if (Math.abs(portal.getScaling() - 1.0) > 1e-9) return null; // scaled seams unsupported
        return ipl.sable.transit.IplStraddlePoseMap.StraddleMapping.of(portal);
    }

    /**
     * Visit EVERY straddle (portal, mapping) of {@code sub} whose DEST is
     * {@code contextLevel} (multi-straddle collision family). Scale-gated per portal.
     */
    public static void forEachClientStraddleInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level contextLevel,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal,
            ipl.sable.transit.IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) return;
        for (qouteall.imm_ptl.core.portal.Portal portal :
                IplStraddleSessionStore.resolveAllPortals(clientSub)) {
            if (portal.getDestDim() != contextLevel.dimension()) continue;
            if (Math.abs(portal.getScaling() - 1.0) > 1e-9) continue;
            visitor.accept(portal,
                ipl.sable.transit.IplStraddlePoseMap.StraddleMapping.of(portal));
        }
    }

    /** Same, for sessions the ship exits FROM {@code contextLevel} (its parent side). */
    public static void forEachClientStraddleFrom(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level contextLevel,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal,
            ipl.sable.transit.IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) return;
        if (ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) != contextLevel) return;
        for (qouteall.imm_ptl.core.portal.Portal portal :
                IplStraddleSessionStore.resolveAllPortals(clientSub)) {
            if (Math.abs(portal.getScaling() - 1.0) > 1e-9) continue;
            visitor.accept(portal,
                ipl.sable.transit.IplStraddlePoseMap.StraddleMapping.of(portal));
        }
    }

    /**
     * The straddle portal from the client-side finder (scale-gated like the mapping),
     * or null. Feeds the entity-collision block clip, which needs the aperture geometry
     * the bare {@code StraddleMapping} doesn't carry.
     */
    @Nullable
    public static qouteall.imm_ptl.core.portal.Portal getClientStraddlePortal(
        dev.ryanhcode.sable.sublevel.SubLevel sub
    ) {
        if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) return null;
        ipl.sable.render.SourceClipPortalFinder.ClipDecision decision =
            ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlane(clientSub);
        if (decision == null || decision.portal() == null) return null;
        if (Math.abs(decision.portal().getScaling() - 1.0) > 1e-9) return null;
        return decision.portal();
    }

    /** Client port of SableTransitOps.computeMappedPose (pose through the portal transform). */
    private static dev.ryanhcode.sable.companion.math.Pose3d ipl$computeMappedPose(
        dev.ryanhcode.sable.companion.math.Pose3dc sourcePose,
        qouteall.imm_ptl.core.portal.Portal portal
    ) {
        net.minecraft.world.phys.Vec3 srcPos = new net.minecraft.world.phys.Vec3(
            sourcePose.position().x(), sourcePose.position().y(), sourcePose.position().z());
        net.minecraft.world.phys.Vec3 destPos = portal.transformPoint(srcPos);

        qouteall.q_misc_util.my_util.DQuaternion portalRot = portal.getRotationD();
        org.joml.Quaterniond destOrient = new org.joml.Quaterniond(
            portalRot.x, portalRot.y, portalRot.z, portalRot.w)
            .mul(new org.joml.Quaterniond(sourcePose.orientation()));

        dev.ryanhcode.sable.companion.math.Pose3d destPose =
            new dev.ryanhcode.sable.companion.math.Pose3d();
        destPose.position().set(destPos.x, destPos.y, destPos.z);
        destPose.orientation().set(destOrient);
        destPose.rotationPoint().set(sourcePose.rotationPoint());
        destPose.scale().set(sourcePose.scale());
        return destPose;
    }
}
