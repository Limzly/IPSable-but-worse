package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of the server's straddle sessions (see
 * {@code IplStraddleSessionSync}): per ship, the portals of its active straddle
 * sessions, replaced wholesale by each snapshot RPC.
 *
 * <p>This store carries ONLY parity — which portal face the ship is crossing, hence
 * which half is "still here". Plane geometry is rebuilt every frame from the resolved
 * portal's current transform, so moving portals stay smooth while parity can never
 * flicker: it changes exactly when the server's latch does.
 *
 * <p>Portal resolution prefers the LIVE entity in the ship's parent level, but each
 * snapshot also carries the portal's full NBT (global-portal-style), from which a
 * detached surrogate is built when the entity isn't synced yet — a cross-dimension
 * reverse session names a portal from the other dimension, and IP only syncs portal
 * entities relevant to the camera; without the surrogate, the handoff had a multi-tick
 * hole with no projection and no collision image.
 */
public final class IplStraddleSessionStore {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-store");

    private record SessionPortal(UUID portalId, String portalNbtB64) {}

    /** Ship → active session portals (session-start order; empty never stored). */
    private static final ConcurrentMap<UUID, List<SessionPortal>> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Sessions the server has retired but the delayed render pose has not finished leaving.
     * Atlas physics removes its image at the authoritative exit tick; rendering must keep the
     * complementary clipped instances until the client catches up or a parent handoff remaps
     * the pose atomically.
     */
    private static final ConcurrentMap<UUID, List<SessionPortal>> RETIRED = new ConcurrentHashMap<>();

    /** Detached surrogate portals built from snapshot NBT, by portal id. */
    private static final ConcurrentMap<UUID, Portal> SURROGATES = new ConcurrentHashMap<>();

    /** Server flips without a full client straddle keep this portal as a render-only tail. */
    private static final ConcurrentMap<UUID, SessionPortal> HANDOFF_VISUALS = new ConcurrentHashMap<>();

    private IplStraddleSessionStore() {}

    /**
     * The authoritative straddle portal for this ship, or null when the server has no
     * session for it. Live entity in the parent level when synced; snapshot surrogate
     * otherwise.
     */
    @Nullable
    public static Portal resolvePortal(ClientSubLevel sub) {
        if (sub == null) return null;
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return null;
        }
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return null;
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            if (live != null) return live;
            Portal surrogate = surrogate(sessionPortal, level);
            if (surrogate != null) return surrogate;
        }
        return null;
    }

    public static boolean hasSession(UUID shipId) {
        return SESSIONS.containsKey(shipId);
    }

    /**
     * Resolves portal geometry carried by a parent-handoff RPC. The server may finish a
     * crossing before IP tracks the source portal for this viewer, so an exact detached
     * portal is required for the client AABB A→B proof.
     */
    @Nullable
    public static Portal resolveHandoffPortal(
        UUID portalId, String portalNbtB64, ClientLevel sourceLevel
    ) {
        Portal live = findPortal(sourceLevel, portalId);
        return live != null ? live : surrogate(new SessionPortal(portalId, portalNbtB64), sourceLevel);
    }

    /** Drop an unreferenced handoff surrogate after its pending frame switch commits. */
    public static void releaseHandoffPortal() {
        discardUnreferencedSurrogates();
    }

    /** Starts a handoff visual tail before client A→B confirmation. */
    public static void beginHandoffVisual(UUID shipId, UUID portalId, String portalNbtB64) {
        SessionPortal next = new SessionPortal(portalId, portalNbtB64);
        if (!next.equals(HANDOFF_VISUALS.put(shipId, next))) {
            IplStraddleRenderCache.invalidateActivePasses();
        }
    }

    /** Removes a completed or superseded handoff visual tail. */
    public static void clearHandoffVisual(UUID shipId) {
        if (HANDOFF_VISUALS.remove(shipId) != null) {
            discardUnreferencedSurrogates();
            IplStraddleRenderCache.invalidateActivePasses();
        }
    }

    /** Discards a tail whose client object never materialized. */
    public static void clearAllVisualState(UUID shipId) {
        HANDOFF_VISUALS.remove(shipId);
        discardUnreferencedSurrogates();
        IplClientVisualTransitLatch.clear(shipId);
        IplStraddleRenderCache.invalidateActivePasses();
    }

    /** ALL resolvable session portals for this ship, session-start order (multi-straddle). */
    public static List<Portal> resolveAllPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return List.of();
        }
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return List.of();
        if (portals.isEmpty()) return List.of();
        List<Portal> out = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    /** Render-only counterpart of {@link #resolvePortal}; includes a delayed exit tail. */
    @Nullable
    public static Portal resolveRenderPortal(ClientSubLevel sub) {
        if (sub == null) return null;
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return null;
        }
        List<Portal> portals = resolveRenderPortals(sub, level);
        return portals.isEmpty() ? null : portals.get(0);
    }

    /** Render-only counterpart of {@link #resolveAllPortals}; never use for collision. */
    public static List<Portal> resolveAllRenderPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return List.of();
        }
        return resolveRenderPortals(sub, level);
    }

    private static List<Portal> resolveRenderPortals(ClientSubLevel sub, ClientLevel level) {
        List<SessionPortal> portals = portalsForRender(sub, level);
        List<SessionPortal> active = SESSIONS.get(sub.getUniqueId());
        List<Portal> out = new ArrayList<>(portals.size() + 1);
        List<Portal> visibleActive = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved == null) continue;

            // A server session establishes parity, but its physics pose is ahead of Sable's
            // delayed render stream. Do not expose the split until this geometry reaches
            // the aperture; prediction below supplies the pre-RPC fast path.
            boolean isActive = active != null && containsPortal(active, sessionPortal.portalId());
            if (isActive && !IplClientVisualTransitLatch.isVisible(sub, resolved)) continue;
            if (isActive) visibleActive.add(resolved);
            out.add(resolved);
        }
        SessionPortal handoffPortal = HANDOFF_VISUALS.get(sub.getUniqueId());
        if (handoffPortal != null && !containsPortal(portals, handoffPortal.portalId())) {
            Portal live = findPortal(level, handoffPortal.portalId());
            Portal resolvedHandoff = live != null ? live : surrogate(handoffPortal, level);
            if (resolvedHandoff != null && IplClientVisualTransitLatch.isVisible(sub, resolvedHandoff)) {
                out.add(resolvedHandoff);
            }
        }
        return IplClientVisualTransitLatch.append(sub, level, visibleActive, out);
    }

    /** Diagnostic: how a ship's session portal currently resolves. */
    public static String debugPortalKind(ClientSubLevel sub) {
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        List<SessionPortal> retired = RETIRED.get(sub.getUniqueId());
        SessionPortal handoff = HANDOFF_VISUALS.get(sub.getUniqueId());
        if (portals == null && retired == null && handoff == null) return "no-session";
        Portal resolved = resolveRenderPortal(sub);
        int count = (portals == null ? 0 : portals.size()) + (retired == null ? 0 : retired.size())
            + (handoff == null ? 0 : 1);
        if (resolved == null) return "session-UNRESOLVED(" + count + ")";
        return SURROGATES.get(resolved.getUUID()) == resolved
            ? "surrogate:" + resolved.getUUID() : "live:" + resolved.getUUID();
    }

    /**
     * Drops a render-only exit tail once {@code renderPose} is wholly on the session's
     * native/source side. The portal normal points toward that side, so any negative corner
     * is still an image-side fragment which needs the old source clip and projection.
     */
    private static boolean stillRenderingThroughHalf(ClientSubLevel sub, Portal portal) {
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        net.minecraft.world.phys.Vec3 origin = portal.getOriginPos();
        net.minecraft.world.phys.Vec3 normal = portal.getNormal();
        for (int x = 0; x < 2; x++) {
            double px = x == 0 ? bounds.minX() : bounds.maxX() + 1.0;
            for (int y = 0; y < 2; y++) {
                double py = y == 0 ? bounds.minY() : bounds.maxY() + 1.0;
                for (int z = 0; z < 2; z++) {
                    double pz = z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0;
                    net.minecraft.world.phys.Vec3 world = sub.renderPose().transformPosition(
                        new net.minecraft.world.phys.Vec3(px, py, pz));
                    if (world.subtract(origin).dot(normal) < 0.0) return true;
                }
            }
        }
        return false;
    }

    /** Active sessions plus only the retired sessions still present in the delayed pose. */
    private static List<SessionPortal> portalsForRender(ClientSubLevel sub, ClientLevel level) {
        List<SessionPortal> active = SESSIONS.get(sub.getUniqueId());
        List<SessionPortal> retired = RETIRED.get(sub.getUniqueId());
        if (retired == null || retired.isEmpty()) {
            return active == null ? List.of() : active;
        }

        List<SessionPortal> visibleRetired = new ArrayList<>(retired.size());
        for (SessionPortal sessionPortal : retired) {
            Portal portal = findPortal(level, sessionPortal.portalId());
            if (portal == null) portal = surrogate(sessionPortal, level);
            if (portal != null && stillRenderingThroughHalf(sub, portal)) {
                visibleRetired.add(sessionPortal);
            }
        }
        if (visibleRetired.isEmpty()) {
            RETIRED.remove(sub.getUniqueId(), retired);
            discardUnreferencedSurrogates();
            return active == null ? List.of() : active;
        }
        if (visibleRetired.size() != retired.size()) {
            RETIRED.replace(sub.getUniqueId(), retired, List.copyOf(visibleRetired));
        }
        if (active == null || active.isEmpty()) return visibleRetired;

        List<SessionPortal> combined = new ArrayList<>(active.size() + visibleRetired.size());
        combined.addAll(active);
        for (SessionPortal sessionPortal : visibleRetired) {
            if (!containsPortal(active, sessionPortal.portalId())) combined.add(sessionPortal);
        }
        return combined;
    }

    private static boolean containsPortal(List<SessionPortal> portals, UUID portalId) {
        for (SessionPortal portal : portals) {
            if (portal.portalId().equals(portalId)) return true;
        }
        return false;
    }

    /** Called by the atomic mapped-pose parent handoff; old-frame tails are invalid there. */
    public static void clearRetiredForHandoff(UUID shipId) {
        if (RETIRED.remove(shipId) != null) discardUnreferencedSurrogates();
    }

    private static void discardUnreferencedSurrogates() {
        Set<UUID> referenced = new HashSet<>();
        collectReferencedPortals(SESSIONS, referenced);
        collectReferencedPortals(RETIRED, referenced);
        for (SessionPortal portal : HANDOFF_VISUALS.values()) referenced.add(portal.portalId());
        SURROGATES.keySet().retainAll(referenced);
    }

    private static void collectReferencedPortals(
        Map<UUID, List<SessionPortal>> sessions, Set<UUID> referenced
    ) {
        for (List<SessionPortal> list : sessions.values()) {
            for (SessionPortal sessionPortal : list) referenced.add(sessionPortal.portalId());
        }
    }

    @Nullable
    private static Portal findPortal(ClientLevel level, UUID portalId) {
        // ClientLevel's UUID entity index is protected; iteration is what the old
        // candidate collector did every frame anyway, and portals are few.
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Portal portal && !portal.isRemoved()
                && portal.getUUID().equals(portalId)) {
                return portal;
            }
        }
        // Dimension-stack seams are global portals: never in the entity list.
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (portal.getUUID().equals(portalId) && !portal.isRemoved()) return portal;
        }
        return null;
    }

    /** Global-portal-style reconstruction: type + NBT → detached Portal data carrier. */
    @Nullable
    private static Portal surrogate(SessionPortal sessionPortal, ClientLevel level) {
        Portal cached = SURROGATES.get(sessionPortal.portalId());
        if (cached != null && cached.level() == level) return cached;
        if (sessionPortal.portalNbtB64().isEmpty()) return null;
        try {
            String snbt = new String(
                Base64.getDecoder().decode(sessionPortal.portalNbtB64()), StandardCharsets.UTF_8);
            CompoundTag tag = TagParser.parseTag(snbt);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.parse(tag.getString("entity_type")));
            Entity entity = type.create(level);
            if (entity == null) return null;
            entity.load(tag);
            entity.setUUID(sessionPortal.portalId());
            Portal portal = (Portal) entity;
            portal.updateCache();
            SURROGATES.put(sessionPortal.portalId(), portal);
            return portal;
        } catch (Throwable t) {
            LOG.error("[IPL-STRADDLE-SYNC] surrogate build failed for {}",
                sessionPortal.portalId(), t);
            return null;
        }
    }

    public static final class RemoteCallables {

        /**
         * Full per-ship snapshot: ';'-joined {@code portalUuid:base64Nbt} entries,
         * empty = no sessions.
         */
        public static void snapshot(String shipUuid, String portalPayload) {
            try {
                UUID shipId = UUID.fromString(shipUuid);
                List<SessionPortal> previous = SESSIONS.get(shipId);
                List<SessionPortal> parsed = List.of();
                if (portalPayload == null || portalPayload.isEmpty()) {
                    SESSIONS.remove(shipId);
                } else {
                    List<SessionPortal> next = new ArrayList<>(2);
                    for (String part : portalPayload.split(";")) {
                        if (part.isEmpty()) continue;
                        int sep = part.indexOf(':');
                        if (sep < 0) {
                            next.add(new SessionPortal(UUID.fromString(part), ""));
                        } else {
                            next.add(new SessionPortal(
                                UUID.fromString(part.substring(0, sep)), part.substring(sep + 1)));
                        }
                    }
                    parsed = List.copyOf(next);
                    if (parsed.isEmpty()) {
                        SESSIONS.remove(shipId);
                    } else {
                        SESSIONS.put(shipId, parsed);
                    }
                }

                // Keep an ended split only until the delayed render pose clears its plane.
                // It covers both native eye-space and the portal render pass without keeping
                // a retired Atlas image collider alive on the server.
                if (previous != null) {
                    List<SessionPortal> retired = new ArrayList<>(previous.size());
                    List<SessionPortal> alreadyRetired = RETIRED.get(shipId);
                    if (alreadyRetired != null) retired.addAll(alreadyRetired);
                    for (SessionPortal old : previous) {
                        if (!containsPortal(parsed, old.portalId())
                            && !containsPortal(retired, old.portalId())) {
                            retired.add(old);
                        }
                    }
                    if (!retired.isEmpty()) RETIRED.put(shipId, List.copyOf(retired));
                }
                List<SessionPortal> oldRetired = RETIRED.get(shipId);
                if (oldRetired != null && !parsed.isEmpty()) {
                    List<SessionPortal> remaining = new ArrayList<>(oldRetired.size());
                    for (SessionPortal retired : oldRetired) {
                        if (!containsPortal(parsed, retired.portalId())) remaining.add(retired);
                    }
                    if (remaining.isEmpty()) RETIRED.remove(shipId, oldRetired);
                    else if (remaining.size() != oldRetired.size()) {
                        RETIRED.replace(shipId, oldRetired, List.copyOf(remaining));
                    }
                }
                discardUnreferencedSurrogates();

                // Straddle decisions are cached per frame; drop them so this snapshot
                // takes effect within the same client tick it arrives in.
                IplStraddleRenderCache.invalidateActivePasses();
            } catch (Throwable t) {
                LOG.error("[IPL-STRADDLE-SYNC] bad snapshot for {}", shipUuid, t);
            }
        }
    }
}
