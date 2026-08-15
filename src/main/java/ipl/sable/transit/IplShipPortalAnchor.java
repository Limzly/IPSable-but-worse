package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas M6 (spec v3 §2.8, "portals on physics structures"): SHIP-ANCHORED PORTALS.
 *
 * <p>An anchored portal's origin end is glued to a sub-level at a ship-local pose.
 * Every server tick, immediately after the fused step publishes fresh physics poses,
 * the portal entity is re-posed from the ship: origin = shipPose(localPos),
 * orientation = shipRot ∘ localOrient. The rotation TRANSFORM is re-derived so the
 * (static) destination end stays fixed: with D₀ = R_t(0) ∘ O(0) locked at anchor
 * time, R_t(now) = D₀ ∘ O(now)⁻¹. {@link PortalExtension#rectifyClusterPortals}
 * then propagates everything to the flipped/reverse/parallel cluster members and
 * syncs clients.
 *
 * <p>Physics follows for free: straddle sessions re-derive their isometry from the
 * portal each tick (M5a) and push it to the image collider; rims re-anchor from the
 * same movement check; the router and entity layers read the refreshed mapping.
 * Within a tick the isometry is frozen — the kinematic-frame fiat (§2.8): traversal
 * impulses do not back-react on the anchor ship.
 *
 * <p>V1 scope: origin end on a ship, destination end static; anchors are runtime
 * state (not persisted across restarts); the anchor ship straddling its OWN portal
 * is physics-safe (engine same-parent filter) but not a supported gameplay loop.
 */
public final class IplShipPortalAnchor {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-ship-portal");

    private record Anchor(
        UUID shipId,
        ResourceKey<Level> portalDim,
        /**
         * PLOT-space portal origin — mapped through the ship's FULL pose transform
         * each tick, exactly like block positions. Never store a pose-position-
         * relative offset: {@code pose.position()} is center-of-mass-derived, and
         * any COM update (fluids, block changes) would silently re-base the offset
         * and drift the aperture off its frame.
         */
        Vector3d plotPos,
        DQuaternion localOrient,
        /** D₀ = R_t(0) ∘ O(0): the dest end's orientation lock. */
        DQuaternion destLock
    ) {}

    /** Portal UUID → anchor. Server-thread only. */
    private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();

    /**
     * Anchors restored from the world SavedData whose ship hasn't resolved yet →
     * remaining grace ticks. Boot ordering: restore runs on the first server tick,
     * typically before portal chunks load AND before Sable's hosting container
     * finishes restoring its sub-levels — releasing on the first failed lookup would
     * silently drop every persisted anchor. While pending, the anchor neither drives
     * nor releases; on first resolve the carrier side effects and client sync apply.
     */
    private static final Map<UUID, Integer> RESTORE_PENDING = new HashMap<>();
    private static final int RESTORE_GRACE_TICKS = 1200; // 60s

    // ------------------------------------------------------------------
    // Persistence: a SavedData on the overworld — STATEFUL, not derived-at-save
    // from entity serialization. Vanilla owns WHEN to write (autosave, pause save,
    // stop save); we own only WHAT. This kills the whole save-ordering trap class
    // (the portal-NBT attempt lost every anchor because our stop cleanup cleared
    // the map before stopServer's save serialized the portal entities) and makes
    // restore independent of portal chunk-load timing.

    private static final String SAVED_DATA_NAME = "ipl_sable_ship_portal_anchors";
    /** A/B kill switch while the feature is young: -Dipl.sable.anchorPersistence=false */
    private static final boolean PERSISTENCE_ENABLED =
        !"false".equals(System.getProperty("ipl.sable.anchorPersistence"));
    /** Which server instance the on-disk anchors were restored for. */
    private static MinecraftServer restoredFor = null;

    public static final class AnchorSavedData extends net.minecraft.world.level.saveddata.SavedData {

        /** Parsed-but-not-yet-applied anchor tags from disk (applied on first tickAll). */
        private net.minecraft.nbt.ListTag loaded = new net.minecraft.nbt.ListTag();

        public static AnchorSavedData get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(
                new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                    AnchorSavedData::new,
                    (nbt, registries) -> {
                        AnchorSavedData data = new AnchorSavedData();
                        data.loaded = nbt.getList("anchors", 10).copy();
                        return data;
                    },
                    null),
                SAVED_DATA_NAME);
        }

        @Override
        public net.minecraft.nbt.CompoundTag save(
            net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries
        ) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (Map.Entry<UUID, Anchor> entry : ANCHORS.entrySet()) {
                Anchor a = entry.getValue();
                net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
                t.putUUID("portalId", entry.getKey());
                t.putUUID("shipId", a.shipId());
                t.putString("dim", a.portalDim().location().toString());
                t.putDouble("plotX", a.plotPos().x);
                t.putDouble("plotY", a.plotPos().y);
                t.putDouble("plotZ", a.plotPos().z);
                putQuat(t, "lo", a.localOrient());
                putQuat(t, "dl", a.destLock());
                list.add(t);
            }
            tag.put("anchors", list);
            return tag;
        }
    }

    /** Applied lazily on the first tickAll of a server instance. */
    private static void restoreFromDisk(MinecraftServer server) {
        AnchorSavedData data = AnchorSavedData.get(server.overworld());
        for (int i = 0; i < data.loaded.size(); i++) {
            try {
                net.minecraft.nbt.CompoundTag t = data.loaded.getCompound(i);
                UUID portalId = t.getUUID("portalId");
                ANCHORS.put(portalId, new Anchor(
                    t.getUUID("shipId"),
                    ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(t.getString("dim"))),
                    new Vector3d(
                        t.getDouble("plotX"), t.getDouble("plotY"), t.getDouble("plotZ")),
                    getQuat(t, "lo"),
                    getQuat(t, "dl")));
                RESTORE_PENDING.put(portalId, RESTORE_GRACE_TICKS);
                LOG.info("[IPL-SHIP-PORTAL] restored anchor for portal {} (ship {}, pending resolve)",
                    portalId, t.getUUID("shipId"));
            } catch (Throwable th) {
                LOG.error("[IPL-SHIP-PORTAL] bad persisted anchor entry {}", i, th);
            }
        }
        data.loaded = new net.minecraft.nbt.ListTag();
    }

    /** Mark the anchor set changed — vanilla persists it at the next save point. */
    private static void markDirty(MinecraftServer server) {
        if (!PERSISTENCE_ENABLED || server == null) return;
        AnchorSavedData.get(server.overworld()).setDirty();
    }

    private static void putQuat(net.minecraft.nbt.CompoundTag tag, String prefix, DQuaternion q) {
        tag.putDouble(prefix + "X", q.getX());
        tag.putDouble(prefix + "Y", q.getY());
        tag.putDouble(prefix + "Z", q.getZ());
        tag.putDouble(prefix + "W", q.getW());
    }

    private static DQuaternion getQuat(net.minecraft.nbt.CompoundTag tag, String prefix) {
        return new DQuaternion(
            tag.getDouble(prefix + "X"), tag.getDouble(prefix + "Y"),
            tag.getDouble(prefix + "Z"), tag.getDouble(prefix + "W"));
    }

    private IplShipPortalAnchor() {}

    /** Server stopped: drop runtime state (persisted state lives in the world's SavedData). */
    public static void clearAll() {
        ANCHORS.clear();
        RESTORE_PENDING.clear();
        restoredFor = null;
    }

    public static boolean isAnchored(UUID portalId) {
        return ANCHORS.containsKey(portalId);
    }

    /**
     * A ship must NEVER traverse (or straddle) its OWN anchored portal: the
     * carrier's forward motion constantly "crosses" the deck aperture, and a
     * self-transit would teleport the ship through a portal riding on itself.
     * The transit controller skips the (carrier, portal) pair entirely.
     */
    public static boolean isAnchorShip(UUID portalId, UUID shipId) {
        Anchor a = ANCHORS.get(portalId);
        return a != null && a.shipId().equals(shipId);
    }

    /**
     * Cluster-aware self-traversal test — USE THIS for session/transit gating.
     * Only the cluster PRIMARY lives in the anchor map, but bi-faced portals
     * (nether portals, datapack custom-gen) have a flipped twin on the SAME
     * plane with the opposite normal: gating by UUID alone lets the carrier
     * open a straddle session against its own portal's other face — an image
     * collider of itself through its own deck aperture (self-collision chaos).
     *
     * <p>Resolution goes through the extension's persisted cluster UUIDs, NOT the
     * lazy entity references: on world load the refs stay null until IP re-binds
     * the cluster, and in that window a ref-based guard waved the carrier's own
     * flipped face through — the rejoin wobble. The UUID fields are read straight
     * from the portal's NBT, so a loaded portal always has them. Entity refs are
     * kept as a fallback for runtime-created clusters mid-bind.
     */
    public static boolean isAnchorShip(Portal portal, UUID shipId) {
        if (isAnchorShip(portal.getUUID(), shipId)) return true;
        PortalExtension ext = PortalExtension.get(portal);
        return (ext.flippedPortalId != null && isAnchorShip(ext.flippedPortalId, shipId))
            || (ext.reversePortalId != null && isAnchorShip(ext.reversePortalId, shipId))
            || (ext.parallelPortalId != null && isAnchorShip(ext.parallelPortalId, shipId))
            || (ext.flippedPortal != null && isAnchorShip(ext.flippedPortal.getUUID(), shipId))
            || (ext.reversePortal != null && isAnchorShip(ext.reversePortal.getUUID(), shipId))
            || (ext.parallelPortal != null && isAnchorShip(ext.parallelPortal.getUUID(), shipId));
    }

    public static int count() {
        return ANCHORS.size();
    }

    /** UUIDs of all portals anchored to {@code shipId} (cluster primaries only). */
    public static java.util.List<UUID> anchoredPortalsOf(UUID shipId) {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Anchor> entry : ANCHORS.entrySet()) {
            if (entry.getValue().shipId().equals(shipId)) out.add(entry.getKey());
        }
        return out;
    }

    /**
     * Anchor {@code portal} to the sub-level whose bounds contain its origin (or the
     * nearest within 8 blocks). Returns a human-readable result message.
     */
    public static String anchor(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return "server side only";
        ServerSubLevel ship = findShipAt(level, portal.getOriginPos(), ANCHOR_MARGIN);
        if (ship == null) {
            return "no sub-level under the portal origin (stand the portal on the ship)";
        }
        return anchorToShip(portal, ship);
    }

    /**
     * Anchor {@code portal} to a KNOWN ship (generated-on-ship nether portals) —
     * same math as the command path, no proximity search. Requires the portal's
     * CURRENT world pose to correspond to the ship's CURRENT pose (true when the
     * portal was just posed from this ship, or the ship hasn't moved since).
     */
    public static String anchorToShip(Portal portal, ServerSubLevel ship) {
        Pose3dc pose = ship.logicalPose();
        Quaterniond shipRot = new Quaterniond(pose.orientation());
        Vec3 plotOrigin = pose.transformPositionInverse(portal.getOriginPos());

        DQuaternion shipD = new DQuaternion(shipRot.x, shipRot.y, shipRot.z, shipRot.w);
        DQuaternion o0 = portal.getOrientationRotation();
        DQuaternion localOrient = shipD.getConjugated().hamiltonProduct(o0);
        return register(portal, ship, plotOrigin, localOrient);
    }

    /**
     * Anchor with an EXACT plot-space origin — for portals whose world entity pose
     * is STALE relative to the ship. Assembly capture is the canonical case: the
     * capture waits for the rehome, and the assembled ship (a live physics body)
     * falls/drifts meanwhile while the not-yet-anchored portal entity stays at its
     * lit world position — deriving the anchor from that pose bakes the drift in
     * (observed: aperture offset ~3.5 blocks from its frame). The assembly
     * transform is a PURE TRANSLATION, so the caller knows the plot origin
     * exactly, and the portal's unchanged world orientation IS its plot-frame
     * orientation. The portal is snapped onto the ship's current pose immediately.
     */
    public static String anchorToShipAtPlot(Portal portal, ServerSubLevel ship, Vec3 plotOrigin) {
        // Plot frame == the portal's (stale) world frame up to translation, so the
        // plot-frame orientation is the portal's current orientation verbatim.
        DQuaternion localOrient = portal.getOrientationRotation();
        String result = register(portal, ship, plotOrigin, localOrient);
        Anchor anchor = ANCHORS.get(portal.getUUID());
        if (anchor != null) {
            drivePortal(portal, ship, anchor); // snap out of the stale world pose now
        }
        return result;
    }

    private static String register(
        Portal portal, ServerSubLevel ship, Vec3 plotOrigin, DQuaternion localOrient
    ) {
        if (!(portal.level() instanceof ServerLevel level)) return "server side only";
        Vector3d localPos = new Vector3d(plotOrigin.x, plotOrigin.y, plotOrigin.z);
        DQuaternion o0 = portal.getOrientationRotation();
        DQuaternion rt0 = portal.getRotation() == null ? DQuaternion.identity : portal.getRotation();
        DQuaternion destLock = rt0.hamiltonProduct(o0);

        ANCHORS.put(portal.getUUID(), new Anchor(
            ship.getUniqueId(), level.dimension(), localPos, localOrient, destLock));
        markDirty(level.getServer());
        applyCarrierSideEffects(portal, ship, true);
        // A normal command anchor does not need to re-pose an already aligned portal,
        // so it previously waited for the manager's later scan to get a rim. Weld both
        // carrier faces now, including a completely stationary ship.
        followCarrierRims(portal);
        syncToClients(level.getServer(), portal.getUUID());
        LOG.info("[IPL-SHIP-PORTAL] anchored portal {} to ship {} at plot ({}, {}, {})",
            portal.getUUID(), ship.getUniqueId(),
            String.format("%.2f", localPos.x), String.format("%.2f", localPos.y),
            String.format("%.2f", localPos.z));
        return "anchored portal to ship " + ship.getUniqueId();
    }

    /** Detach the portal; it stays wherever the ship last carried it. */
    public static String unanchor(Portal portal) {
        Anchor removed = ANCHORS.remove(portal.getUUID());
        RESTORE_PENDING.remove(portal.getUUID());
        if (removed == null) return "portal was not anchored";
        applyCarrierSideEffects(portal, null, false);
        if (portal.level() instanceof ServerLevel sl) {
            markDirty(sl.getServer());
            syncClearToClients(sl.getServer(), portal.getUUID());
        }
        return "unanchored";
    }

    /**
     * Anchored-portal side effects, applied to the whole same-level cluster
     * (the flipped twin shares the plane and has its own rim):
     *  - rim-vs-carrier exclusion: the containment rim must never push the hull
     *    that surrounds the aperture;
     *  - client lerp: IP's DefaultPortalAnimation eases synced state changes over
     *    10 ticks — half a second of aperture lag on a moving ship. Anchored
     *    portals get 1 tick (smooth at 20 TPS updates, no visible delay);
     *    restored to the IP default on unanchor.
     */
    private static void applyCarrierSideEffects(Portal portal, ServerSubLevel ship, boolean on) {
        int carrierId = on ? dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(ship) : -1;
        java.util.function.Consumer<Portal> perPortal = p -> {
            if (p.level() != portal.level()) return; // reverse/parallel sit at the dest
            if (on) {
                IplPortalRimManager.setCarrierExclusion(p.getUUID(), carrierId, true);
                p.animation.defaultAnimation.durationTicks = 1;
            } else {
                IplPortalRimManager.setCarrierExclusion(p.getUUID(), -1, false);
                p.animation.defaultAnimation.durationTicks = 10;
            }
        };
        perPortal.accept(portal);
        // Resolve the flipped face by persisted UUID, not just the lazy entity ref:
        // on a restored anchor the refs may still be unbound, and a missed flipped
        // face here leaves its rim WITHOUT the carrier exclusion — permanent
        // rim-vs-hull wobble after rejoin.
        PortalExtension ext = PortalExtension.get(portal);
        Portal flipped = ext.flippedPortal;
        if (flipped == null && ext.flippedPortalId != null
            && portal.level() instanceof ServerLevel sl
            && sl.getEntity(ext.flippedPortalId) instanceof Portal boundByUuid) {
            flipped = boundByUuid;
        }
        if (flipped != null) perPortal.accept(flipped);
    }

    /**
     * Is this portal — or any persisted cluster member — anchored to ANY ship?
     * Used by the rim manager to hold off rim creation until the anchor's carrier
     * exclusion has been applied (restored anchors resolve their ship ticks after
     * the portal loads; a rim spawned in that window grinds against its own hull).
     */
    public static boolean isAnchoredCluster(Portal portal) {
        if (ANCHORS.containsKey(portal.getUUID())) return true;
        PortalExtension ext = PortalExtension.get(portal);
        return (ext.flippedPortalId != null && ANCHORS.containsKey(ext.flippedPortalId))
            || (ext.reversePortalId != null && ANCHORS.containsKey(ext.reversePortalId))
            || (ext.parallelPortalId != null && ANCHORS.containsKey(ext.parallelPortalId))
            || (ext.flippedPortal != null && ANCHORS.containsKey(ext.flippedPortal.getUUID()))
            || (ext.reversePortal != null && ANCHORS.containsKey(ext.reversePortal.getUUID()))
            || (ext.parallelPortal != null && ANCHORS.containsKey(ext.parallelPortal.getUUID()));
    }

    /**
     * True for the anchored origin face and its same-level flipped face. These are the
     * carrier's physical aperture faces; reverse and parallel faces are destination-world
     * portals and keep their ordinary world block frame.
     */
    public static boolean isCarrierPortalFace(Portal portal) {
        Anchor direct = ANCHORS.get(portal.getUUID());
        if (direct != null) return true;
        PortalExtension ext = PortalExtension.get(portal);
        Anchor flipped = ext.flippedPortalId == null ? null : ANCHORS.get(ext.flippedPortalId);
        if (flipped == null && ext.flippedPortal != null) {
            flipped = ANCHORS.get(ext.flippedPortal.getUUID());
        }
        return flipped != null && portal.level().dimension().equals(flipped.portalDim());
    }

    /**
     * Drive all anchored portals from their ships' fresh physics poses. Called at the
     * end of the fused step (IplFusedStep), when this tick's poses are final.
     */
    private static int syncCounter = 0;

    public static void tickAll(MinecraftServer server) {
        if (PERSISTENCE_ENABLED && restoredFor != server) {
            restoredFor = server;
            restoreFromDisk(server);
        }
        if (ANCHORS.isEmpty()) return;
        // Late-joiner bootstrap: the anchor payload is tiny; rebroadcast on a slow
        // cadence instead of tracking per-player join state.
        if ((syncCounter++ % 100) == 0) {
            for (UUID portalId : ANCHORS.keySet().toArray(new UUID[0])) {
                syncToClients(server, portalId);
            }
        }

        Iterator<Map.Entry<UUID, Anchor>> it = ANCHORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Anchor> entry = it.next();
            Anchor a = entry.getValue();

            ServerLevel level = server.getLevel(a.portalDim());
            if (level == null) continue;
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null) continue; // unloaded — keep the anchor, skip this tick
            if (!(entity instanceof Portal portal) || portal.isRemoved()) {
                LOG.info("[IPL-SHIP-PORTAL] portal {} gone — anchor dropped", entry.getKey());
                syncClearToClients(server, entry.getKey());
                it.remove();
                markDirty(server);
                continue;
            }

            ServerSubLevel ship = findShip(server, a.shipId());
            if (ship == null || ship.isRemoved()) {
                Integer grace = RESTORE_PENDING.get(entry.getKey());
                if (grace != null && grace > 0) {
                    RESTORE_PENDING.put(entry.getKey(), grace - 1);
                    continue; // restored anchor, ship still loading — wait, don't drive
                }
                RESTORE_PENDING.remove(entry.getKey());
                LOG.info("[IPL-SHIP-PORTAL] ship {} gone — portal {} released",
                    a.shipId(), entry.getKey());
                applyCarrierSideEffects(portal, null, false);
                syncClearToClients(server, entry.getKey());
                it.remove();
                markDirty(server);
                continue;
            }
            if (RESTORE_PENDING.remove(entry.getKey()) != null) {
                // Restored anchor's ship just resolved: finish what anchorToShip
                // would have done live (deferred — the physics scene and container
                // aren't available during entity NBT read at boot).
                applyCarrierSideEffects(portal, ship, true);
                syncToClients(server, entry.getKey());
                LOG.info("[IPL-SHIP-PORTAL] restored anchor {} resolved to ship {}",
                    entry.getKey(), a.shipId());
            }

            Pose3dc pose = ship.logicalPose();
            Quaterniond shipRot = new Quaterniond(pose.orientation());
            Vec3 originNow = pose.transformPosition(
                new Vec3(a.plotPos().x, a.plotPos().y, a.plotPos().z));
            DQuaternion shipD = new DQuaternion(shipRot.x, shipRot.y, shipRot.z, shipRot.w);
            DQuaternion oNow = shipD.hamiltonProduct(a.localOrient());

            // Cheap static-ship skip: pose unchanged within epsilon.
            DQuaternion oCur = portal.getOrientationRotation();
            boolean moved = portal.getOriginPos().distanceToSqr(originNow) > 1.0e-10
                || Math.abs(oCur.getX() * oNow.getX() + oCur.getY() * oNow.getY()
                    + oCur.getZ() * oNow.getZ() + oCur.getW() * oNow.getW()) < 1.0 - 1.0e-10;
            if (!moved) continue;

            drivePortal(portal, ship, a);
        }
    }

    /** Re-pose {@code portal} from the ship's CURRENT pose (tick driver + anchor snap). */
    private static void drivePortal(Portal portal, ServerSubLevel ship, Anchor a) {
        Pose3dc pose = ship.logicalPose();
        Quaterniond shipRot = new Quaterniond(pose.orientation());
        Vec3 originNow = pose.transformPosition(
            new Vec3(a.plotPos().x, a.plotPos().y, a.plotPos().z));
        DQuaternion shipD = new DQuaternion(shipRot.x, shipRot.y, shipRot.z, shipRot.w);
        DQuaternion oNow = shipD.hamiltonProduct(a.localOrient());
        DQuaternion rtNow = a.destLock().hamiltonProduct(oNow.getConjugated());

        portal.setOriginPos(originNow);
        portal.setOrientationRotation(oNow);
        portal.setRotation(rtNow);
        portal.reloadAndSyncToClientNextTick();
        PortalExtension.get(portal).rectifyClusterPortals(portal, true);
        // Weld the physical rim at the same point that welds the portal aperture to
        // the ship. Rectification moves the same-level flipped face too.
        followCarrierRims(portal);
    }

    /** Create/update both same-level carrier-face rims from their rectified portal poses. */
    private static void followCarrierRims(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return;
        IplPortalRimManager.followDrivenPortal(level, portal);
        PortalExtension ext = PortalExtension.get(portal);
        Portal flipped = ext.flippedPortal;
        if (flipped == null && ext.flippedPortalId != null
            && level.getEntity(ext.flippedPortalId) instanceof Portal byId) {
            flipped = byId;
        }
        if (flipped != null && flipped.level() == portal.level()) {
            IplPortalRimManager.followDrivenPortal(level, flipped);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Acceptance margin (blocks) between the portal origin and the ship's BOUNDS —
     * measured to the box surface, not the center, so large hulls qualify. Generous
     * by default: a deck-mounted portal floats a rim's width above the hull, and the
     * origin of a wand-made portal can sit a few blocks off the frame.
     */
    private static final double ANCHOR_MARGIN =
        Double.parseDouble(System.getProperty("ipl.sable.shipPortal.anchorMargin", "8.0"));

    /**
     * Atlas M6 WELD: mirror the anchor to clients — the client drives the portal's
     * pose per FRAME from the ship's interpolated render pose, pixel-locking the
     * aperture to the hull (see ipl.sable.client.IplClientShipPortalAnchor).
     */
    private static void syncToClients(MinecraftServer server, UUID portalId) {
        Anchor a = ANCHORS.get(portalId);
        if (a == null || server == null) return;
        ServerLevel level = server.getLevel(a.portalDim());
        String flippedId = "";
        String reverseId = "";
        String parallelId = "";
        if (level != null && level.getEntity(portalId) instanceof Portal portal) {
            PortalExtension ext = PortalExtension.get(portal);
            if (ext.flippedPortal != null) flippedId = ext.flippedPortal.getUUID().toString();
            if (ext.reversePortal != null) reverseId = ext.reversePortal.getUUID().toString();
            if (ext.parallelPortal != null) parallelId = ext.parallelPortal.getUUID().toString();
        }
        String localPos = a.plotPos().x + "," + a.plotPos().y + "," + a.plotPos().z;
        String localOrient = a.localOrient().getX() + "," + a.localOrient().getY() + ","
            + a.localOrient().getZ() + "," + a.localOrient().getW();
        String destLock = a.destLock().getX() + "," + a.destLock().getY() + ","
            + a.destLock().getZ() + "," + a.destLock().getW();
        for (net.minecraft.server.level.ServerPlayer player
                : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplClientShipPortalAnchor.RemoteCallables.set",
                portalId.toString(), flippedId, reverseId, parallelId,
                a.shipId().toString(), localPos, localOrient, destLock);
        }
    }

    private static void syncClearToClients(MinecraftServer server, UUID portalId) {
        if (server == null) return;
        for (net.minecraft.server.level.ServerPlayer player
                : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplClientShipPortalAnchor.RemoteCallables.clear",
                portalId.toString());
        }
    }

    /**
     * Find the ship nearest {@code pos} whose EFFECTIVE PARENT is {@code level}.
     * Hosted ships' gameplay state lives in the HOSTING dimension's container (the
     * dim-agnostic architecture), so scanning only the portal's own level finds
     * nothing — the same disease as Sable's nearby-sub-level commands. Enumerate
     * every container and filter by parent instead.
     */
    private static ServerSubLevel findShipAt(ServerLevel level, Vec3 pos, double margin) {
        ServerSubLevel best = null;
        double bestDist = margin * margin;
        for (ServerLevel any : level.getServer().getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(any);
            if (container == null) continue;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved()) continue;
                ServerLevel parent = ipl.sable.dim.IplDimAgnostic.isHosted(sub)
                    ? ipl.sable.dim.IplDimAgnostic.getServerParentLevel(sub)
                    : (sub.getLevel() instanceof ServerLevel sl ? sl : null);
                if (parent != level) continue;
                var bb = sub.boundingBox();
                // Distance from the origin to the closest point of the ship's AABB
                // (zero when inside): a portal hovering just above a large deck is
                // near the SURFACE while being far from the center.
                double dx = Math.max(0.0, Math.max(bb.minX() - pos.x, pos.x - bb.maxX()));
                double dy = Math.max(0.0, Math.max(bb.minY() - pos.y, pos.y - bb.maxY()));
                double dz = Math.max(0.0, Math.max(bb.minZ() - pos.z, pos.z - bb.maxZ()));
                double d = dx * dx + dy * dy + dz * dz;
                if (d == 0.0) return sub;
                if (d < bestDist) {
                    bestDist = d;
                    best = sub;
                }
            }
        }
        return best;
    }

    private static ServerSubLevel findShip(MinecraftServer server, UUID shipId) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub.getUniqueId().equals(shipId)) return sub;
            }
        }
        return null;
    }
}
