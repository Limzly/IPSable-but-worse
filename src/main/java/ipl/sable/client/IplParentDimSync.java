package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import ipl.sable.mixin.client.IplClientSubLevelRenderPoseAccessor;
import ipl.sable.mixin.client.IplSnapshotInterpolatorAccessor;
import ipl.sable.mixin.client.IplSubLevelLastPoseAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Client receiver for the hosted sub-level parent-dim stamp, invoked via IP's
 * {@code McRemoteProcedureCall} right after each full sync of a hosted sub-level
 * (see {@code SableCrossDimTrackingMixin}'s bootstrap).
 *
 * <p>The client-side {@code ClientSubLevel} is allocated in the sublevels-dim container by
 * the redirected start-tracking packet; its duck {@code parentLevel} defaults to the hosting
 * level. This call points it at the actual parent {@code ClientLevel} so the renderer and
 * client-side collision know which dimension the airship appears in.
 */
public final class IplParentDimSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    /** RPC delivery can precede the redirected full-sync that creates the client sub-level. */
    private static final Map<UUID, java.util.ArrayDeque<PendingHandoff>> PENDING_HANDOFFS = new HashMap<>();

    /** Parent stamps that arrived before their client sub-level was created (retried per tick). */
    private static final Map<UUID, PendingParentStamp> PENDING_PARENT_STAMPS = new HashMap<>();

    private record PendingParentStamp(String parentDimId, long queuedAtMs) {}

    private IplParentDimSync() {}

    private record PendingHandoff(
        String parentDimId, String portalTransform, String portalNbtB64,
        boolean awaitingClientAllocation
    ) {}

    private static long ipl$lastDiagMs = 0;

    /**
     * Round-trip bring-up diagnostic: per hosted client ship, the exact links that can
     * die independently — parent duck, render pose, session store, portal resolution.
     * 5s cadence; remove after the declarative-straddle stack stabilizes.
     */
    public static void clientHeartbeat() {
        long now = System.currentTimeMillis();
        if (now - ipl$lastDiagMs < 5000) return;
        ipl$lastDiagMs = now;

        SubLevelContainer container = IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (!(sub instanceof ClientSubLevel clientSub) || sub.isRemoved()) continue;
            Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(sub);
            var pos = clientSub.renderPose().position();
            LOG.info("[IPL-CLIENT-DIAG] ship={} parent={} pose=({},{},{}) portal={}",
                sub.getUniqueId(),
                parent == null ? "NULL" : parent.dimension().location(),
                String.format("%.1f", pos.x()), String.format("%.1f", pos.y()),
                String.format("%.1f", pos.z()),
                IplStraddleSessionStore.debugPortalKind(clientSub));
        }
    }

    /** Retries handoffs that arrived before their client sub-level was created. */
    public static void applyPendingHandoffs() {
        if (!PENDING_HANDOFFS.isEmpty()) {
            Iterator<Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>>> iterator =
                PENDING_HANDOFFS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>> entry = iterator.next();
                java.util.ArrayDeque<PendingHandoff> pending = entry.getValue();
                try {
                    // A fresh destination full-sync already carries destination-frame poses.
                    // Wait for its parent stamp to consume this queue instead of mapping those
                    // initial poses through the portal a second time.
                    if (pending.peekFirst().awaitingClientAllocation()) continue;
                    // A client can receive several ordered parent flips before the redirected
                    // full-sync creates its hosted sub-level. Apply every transform in order:
                    // keeping only the latest transform maps an original-frame pose through a
                    // later portal and breaks portal chains/self-recursive portals.
                    while (!pending.isEmpty()) {
                        PendingHandoff next = pending.peekFirst();
                        RemoteCallables.beginHandoffVisual(entry.getKey(), next);
                        if (!RemoteCallables.applyHandoffWhenVisuallyClear(
                            entry.getKey(), next.parentDimId(), next.portalTransform(), next.portalNbtB64()
                        )) {
                            break;
                        }
                        pending.removeFirst();
                    }
                    if (pending.isEmpty()) iterator.remove();
                } catch (Throwable t) {
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove();
                    LOG.error("[IPL-PARENT-SYNC] failed deferred handoff for {}", entry.getKey(), t);
                }
            }
        }
        // Parent stamps must run after handoffs. A stamp only changes ownership; applying it
        // first would expose a source-frame delayed pose in its destination world for one tick.
        applyPendingParentStamps();
    }

    /**
     * While visual handoff waits for delayed source geometry to exit, incoming server snapshots
     * are already in the destination frame. Express them back in the current client frame so
     * Sable never interpolates a source pose directly toward a destination coordinate.
     */
    public static Pose3dc mapPendingDestinationSnapshotBack(UUID subLevelId, Pose3dc snapshot) {
        java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
        if (pending == null || pending.isEmpty() || pending.peekFirst().awaitingClientAllocation()) {
            return snapshot;
        }
        Pose3d mapped = new Pose3d(snapshot);
        for (java.util.Iterator<PendingHandoff> it = pending.descendingIterator(); it.hasNext();) {
            mapped = RemoteCallables.PortalMapping.decode(it.next().portalTransform())
                .mapPoseInverse(mapped);
        }
        return mapped;
    }

    /** Retries parent stamps that raced their StartTracking allocation. */
    private static void applyPendingParentStamps() {
        if (PENDING_PARENT_STAMPS.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingParentStamp>> iterator = PENDING_PARENT_STAMPS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingParentStamp> entry = iterator.next();
            try {
                if (now - entry.getValue().queuedAtMs() > 30_000) {
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove(); // ship never materialized client-side — stop retrying
                    continue;
                }
                SubLevel subLevel = RemoteCallables.findHostedSubLevel(
                    entry.getKey().toString(), entry.getValue().parentDimId());
                if (subLevel != null) {
                    RemoteCallables.setParentInternal(subLevel, entry.getValue().parentDimId());
                    LOG.info("[IPL-PARENT-SYNC] deferred parent stamp applied: {} parent={}",
                        entry.getKey(), entry.getValue().parentDimId());
                    iterator.remove();
                }
            } catch (Throwable t) {
                iterator.remove();
                LOG.error("[IPL-PARENT-SYNC] failed deferred parent stamp for {}", entry.getKey(), t);
            }
        }
    }

    public static final class RemoteCallables {

        public static void setParent(String subLevelUuid, String parentDimId) {
            try {
                UUID subLevelId = UUID.fromString(subLevelUuid);
                SubLevel subLevel = findHostedSubLevel(subLevelUuid, parentDimId);
                if (subLevel == null) {
                    // The RPC raced the StartTracking allocation. Without a retry the ship
                    // keeps a null client parent and the hosted render gather filters it out
                    // FOREVER — "the ship exists (physics, collision) but is invisible".
                    // Single-block ships (swivel tops, shattered blocks, rope connectors)
                    // never emit further updates that could heal it, so they stayed gone.
                    PENDING_PARENT_STAMPS.put(subLevelId,
                        new PendingParentStamp(parentDimId, System.currentTimeMillis()));
                    return;
                }
                if (discardPreAllocationHandoffs(subLevelId, parentDimId)) {
                    // This client did not have a source-frame body when the handoff arrived.
                    // Its just-created full sync is already in the final destination frame.
                    setParentInternal(subLevel, parentDimId);
                    IplStraddleSessionStore.clearHandoffVisual(subLevelId);
                    PENDING_PARENT_STAMPS.remove(subLevelId);
                    return;
                }
                if (PENDING_HANDOFFS.containsKey(subLevelId)) {
                    // A parent stamp can race the full-sync that a queued handoff waits for.
                    // Keep it behind the FIFO so ownership never becomes visible before its
                    // delayed render timeline has been mapped into that frame.
                    PENDING_PARENT_STAMPS.put(subLevelId,
                        new PendingParentStamp(parentDimId, System.currentTimeMillis()));
                    return;
                }
                setParentInternal(subLevel, parentDimId);
                PENDING_PARENT_STAMPS.remove(subLevelId);

                LOG.info("[IPL-PARENT-SYNC] sub-level {} parent={} (client)",
                    subLevelUuid, parentDimId);
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to apply parent stamp for {}", subLevelUuid, t);
            }
        }

        /**
         * Atomically switches an already-tracked ship from its source frame to its
         * destination frame. Unlike a normal parent stamp, this also resets Sable's
         * interpolation timeline into destination space. The server pose is deliberately not
         * used as the baseline: it is ahead of Sable's interpolation delay and would produce
         * a visible forward jump on every smooth crossing.
         */
        public static void handoff(
            String subLevelUuid, String parentDimId, String portalTransform, String portalNbtB64
        ) {
            try {
                UUID subLevelId = UUID.fromString(subLevelUuid);
                // Do not let a later RPC bypass an earlier one that raced client creation.
                // The body pose is still in the first portal's source frame until the FIFO
                // is drained, so every portal transform must compose in wire order.
                java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
                if (pending != null) {
                    pending.addLast(new PendingHandoff(parentDimId, portalTransform, portalNbtB64, false));
                    return;
                }
                SubLevel subLevel = findHostedSubLevel(subLevelUuid, parentDimId);
                if (!(subLevel instanceof ClientSubLevel)) {
                    // No source-frame client object exists. Its eventual full sync is already
                    // destination-frame, so never create a source-side visual tail for it.
                    PENDING_HANDOFFS.computeIfAbsent(subLevelId, ignored -> new java.util.ArrayDeque<>())
                        .addLast(new PendingHandoff(parentDimId, portalTransform, portalNbtB64, true));
                    return;
                }
                if (!applyHandoffWhenVisuallyClear(
                    subLevelId, parentDimId, portalTransform, portalNbtB64
                )) {
                    PendingHandoff handoff = new PendingHandoff(
                        parentDimId, portalTransform, portalNbtB64, false);
                    PENDING_HANDOFFS.computeIfAbsent(subLevelId, ignored -> new java.util.ArrayDeque<>())
                        .addLast(handoff);
                    beginHandoffVisual(subLevelId, handoff);
                }
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to hand off hosted sub-level {}", subLevelUuid, t);
            }
        }

        /**
         * Keep a server-completed handoff queued until Sable's delayed render volume has
         * fully cleared the source plane. Mapping it earlier turns the still-visible source
         * half into a destination pose, producing the end-edge cut and a small rehome jerk.
         */
        private static boolean applyHandoffWhenVisuallyClear(
            UUID subLevelId, String parentDimId, String portalTransform, String portalNbtB64
        ) {
            SubLevel subLevel = findHostedSubLevel(subLevelId.toString(), parentDimId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) return false;
            PortalMapping mapping = PortalMapping.decode(portalTransform);
            if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(clientSubLevel)
                instanceof ClientLevel sourceLevel)) return false;
            qouteall.imm_ptl.core.portal.Portal portal = IplStraddleSessionStore.resolveHandoffPortal(
                mapping.portalId(), portalNbtB64, sourceLevel);
            if (portal == null
                || !IplClientVisualTransitLatch.hasForwardApertureSweep(clientSubLevel, portal)) {
                return false;
            }
            if (!mapping.hasFullyClearedSourcePlane(clientSubLevel)) return false;
            applyHandoff(subLevelId, parentDimId, mapping);
            IplStraddleSessionStore.clearHandoffVisual(subLevelId);
            IplStraddleSessionStore.releaseHandoffPortal();
            return true;
        }

        /** Only the FIFO head receives a render tail; later recursive handoffs wait. */
        private static void beginHandoffVisual(UUID subLevelId, PendingHandoff handoff) {
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            IplStraddleSessionStore.beginHandoffVisual(
                subLevelId, mapping.portalId(), handoff.portalNbtB64());
        }

        private static boolean discardPreAllocationHandoffs(UUID subLevelId, String parentDimId) {
            java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
            if (pending == null || pending.isEmpty() || !pending.peekFirst().awaitingClientAllocation()) {
                return false;
            }
            PendingHandoff last = pending.peekLast();
            if (!last.parentDimId().equals(parentDimId)) return false;
            PENDING_HANDOFFS.remove(subLevelId);
            return true;
        }

        private static void applyHandoff(
            UUID subLevelId, String parentDimId, PortalMapping mapping
        ) {
            SubLevel subLevel = findHostedSubLevel(subLevelId.toString(), parentDimId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) return;
            IplClientVisualTransitLatch.clear(subLevelId);

            // Map both endpoints used by ClientSubLevel.renderPose(). Mapping only its
            // current interpolated sample then collapsing lastPose/logicalPose to it makes
            // native destination rendering stop for one client tick at portal exit.
            Pose3d mappedLastPose = mapping.mapPose(new Pose3d(clientSubLevel.lastPose()));
            Pose3d mappedLogicalPose = mapping.mapPose(new Pose3d(clientSubLevel.logicalPose()));

            SubLevelSnapshotInterpolator interpolator = clientSubLevel.getInterpolator();
            // Do not clear the delayed snapshot timeline. The first post-flip movement
            // packet would then replace the visual pose with a newer server pose, causing
            // a second jump. Mapping every buffered snapshot keeps interpolation continuous
            // until destination-space packets naturally extend the same timeline.
            synchronized (interpolator.buffer) {
                for (int i = 0; i < interpolator.buffer.size(); i++) {
                    SubLevelSnapshotInterpolator.Snapshot snapshot = interpolator.buffer.get(i);
                    interpolator.buffer.set(i, new SubLevelSnapshotInterpolator.Snapshot(
                        snapshot.gameTick(), mapping.mapPose(new Pose3d(snapshot.pose()))
                    ));
                }
            }
            ((IplSnapshotInterpolatorAccessor) interpolator).ipl$getRunningSnapshot()
                .set(mappedLogicalPose);
            ((IplSubLevelLastPoseAccessor) clientSubLevel).ipl$getLastPose().set(mappedLastPose);
            clientSubLevel.logicalPose().set(mappedLogicalPose);
            clientSubLevel.forceUpdateBounds();

            // Do not expose the new parent until every client pose is in destination
            // space. A pass already in progress may otherwise render a stale source
            // projection as well as the newly native destination sub-level.
            setParentInternal(clientSubLevel, parentDimId);
            // The retired split only bridges server session-end to this exact mapped pose.
            // Once the parent frame becomes visible, retaining its old portal would clip the
            // native destination draw with a source-frame plane.
            IplStraddleSessionStore.clearRetiredForHandoff(subLevelId);
            // Staff drag frame changes ride the dedicated grab-chain rebase RPC (ordered
            // after this handoff on the same channel); nothing staff-related to do here.
            // Straddle parity is server-synced state now (IplStraddleSessionStore); the
            // "crossed" session-end snapshot precedes this handoff on the ordered channel,
            // so there is no client-side latch left to clear here.
            IplStraddleRenderCache.invalidateActivePasses();
            // Rebuild Sable's cached render pose from the separately mapped endpoints.
            // Portal isometry commutes with the interpolation, so exit motion continues
            // exactly where its destination projection left it.
            ((IplClientSubLevelRenderPoseAccessor) clientSubLevel)
                .ipl$setLastRenderPosePartialTick(-1.0f);
            LOG.info("[IPL-PARENT-SYNC] handoff applied for {} -> parent {} pose=({},{},{})",
                subLevelId, parentDimId,
                String.format("%.1f", mappedLogicalPose.position().x()),
                String.format("%.1f", mappedLogicalPose.position().y()),
                String.format("%.1f", mappedLogicalPose.position().z()));
        }

        /**
         * IP only sends portal entities that are relevant to the client camera. A tracked
         * construction can cross a distant portal in one tick, so carry the portal transform
         * in the handoff itself rather than leaving its destination-frame switch dependent on
         * that entity being in the client's render list.
         */
        public record PortalMapping(
            net.minecraft.world.phys.Vec3 origin,
            net.minecraft.world.phys.Vec3 destination,
            Quaterniond rotation,
            double scale,
            net.minecraft.world.phys.Vec3 sourceNormal,
            UUID portalId
        ) {
            public static PortalMapping decode(String encoded) {
                String[] values = encoded.split(";", -1);
                if (values.length != 15) {
                    throw new IllegalArgumentException("invalid portal handoff transform");
                }
                double[] decoded = new double[14];
                for (int i = 0; i < decoded.length; i++) {
                    decoded[i] = Double.valueOf(values[i]);
                }
                return new PortalMapping(
                    new net.minecraft.world.phys.Vec3(decoded[0], decoded[1], decoded[2]),
                    new net.minecraft.world.phys.Vec3(decoded[3], decoded[4], decoded[5]),
                    new Quaterniond(decoded[6], decoded[7], decoded[8], decoded[9]), decoded[10],
                    new net.minecraft.world.phys.Vec3(decoded[11], decoded[12], decoded[13]),
                    UUID.fromString(values[14])
                );
            }

            Pose3d mapPose(Pose3d sourcePose) {
                Vector3d offset = new Vector3d(
                    sourcePose.position().x() - origin.x,
                    sourcePose.position().y() - origin.y,
                    sourcePose.position().z() - origin.z
                ).mul(scale);
                rotation.transform(offset);

                Pose3d destinationPose = new Pose3d(sourcePose);
                destinationPose.position().set(
                    destination.x + offset.x, destination.y + offset.y, destination.z + offset.z
                );
                destinationPose.orientation().set(new Quaterniond(rotation).mul(sourcePose.orientation()));
                return destinationPose;
            }

            public Pose3d mapPoseInverse(Pose3d destinationPose) {
                Vector3d offset = new Vector3d(
                    destinationPose.position().x() - destination.x,
                    destinationPose.position().y() - destination.y,
                    destinationPose.position().z() - destination.z
                );
                Quaterniond inverseRotation = new Quaterniond(rotation).invert();
                inverseRotation.transform(offset);
                offset.div(scale);

                Pose3d sourcePose = new Pose3d(destinationPose);
                sourcePose.position().set(
                    origin.x + offset.x, origin.y + offset.y, origin.z + offset.z
                );
                sourcePose.orientation().set(inverseRotation.mul(destinationPose.orientation()));
                return sourcePose;
            }

            boolean hasFullyClearedSourcePlane(ClientSubLevel sub) {
                var bounds = sub.getPlot().getBoundingBox();
                Pose3d pose = new Pose3d(sub.renderPose());
                Vec3 sourceToDest = sourceNormal.scale(-1.0);
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                    Vec3 point = pose.transformPosition(new Vec3(
                        x == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                        y == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                        z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
                    if (point.subtract(origin).dot(sourceToDest) < -1.0e-8) return false;
                }
                return true;
            }
        }

        private static ResourceKey<Level> parentKey(String parentDimId) {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(parentDimId));
        }

        private static SubLevel findHostedSubLevel(String subLevelUuid, String parentDimId) {
            ClientLevel hosting = ClientWorldLoader.getWorld(SableSubLevelDimension.SUBLEVELS);
            SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
            if (container == null) {
                LOG.warn("[IPL-PARENT-SYNC] sublevels client world has no container");
                return null;
            }

            SubLevel subLevel = container.getSubLevel(UUID.fromString(subLevelUuid));
            if (subLevel == null) {
                LOG.warn("[IPL-PARENT-SYNC] no hosted sub-level {} (parent {})",
                    subLevelUuid, parentDimId);
            }
            return subLevel;
        }

        static void setParentInternal(SubLevel subLevel, String parentDimId) {
            ClientLevel hosting = ClientWorldLoader.getWorld(SableSubLevelDimension.SUBLEVELS);
            ResourceKey<Level> parentKey = parentKey(parentDimId);
            ClientLevel parent = ClientWorldLoader.getWorld(parentKey);

            IplSubLevelDuck duck = (IplSubLevelDuck) subLevel;
            ClientLevel oldParent = duck.ipl$getParentLevel() instanceof ClientLevel old ? old : null;
            duck.ipl$setParentLevel(parent);
            duck.ipl$setHostingLevel(hosting);

            // Flywheel visuals live in the parent's visualization world — re-home them
            // with the flip so swivel bearings / throttle levers keep rendering after
            // a cross-portal transit.
            if (oldParent != parent) {
                ipl.sable.client.IplClientFlywheelReroute.onParentFlip(subLevel, oldParent, parent);
            }
        }
    }
}
