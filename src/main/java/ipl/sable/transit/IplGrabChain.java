package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplStaffDragSessionControl;
import ipl.sable.mixin.IplStaffServerHandlerAccessorMixin;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative grab chain: THE coordinate frame of an active staff drag.
 *
 * <p>Replaces the old {@code IplStaffPortalDragState} and every heuristic it carried —
 * distance-to-box goal choosers, sticky switch margins, held-portal pins, single-transit
 * records, nearest-portal-after-dimension-change guessing. All of that inferred frames
 * from geometry snapshots; this class records frames from the EVENTS that change them:
 *
 * <ul>
 *   <li><b>Grab start</b> seeds the chain from the pick-time portal path (the client
 *       raycast walked exact portals; their UUIDs arrive by RPC and the server
 *       re-snapshots each portal's transform itself).</li>
 *   <li><b>Player teleport</b> (from {@code ServerTeleportationManager}, which knows the
 *       exact portal — same-dimension hops included) prepends the portal's INVERSE link
 *       and rotates the stored player-relative cursor vector, so the folded goal is
 *       continuous through the hop by construction.</li>
 *   <li><b>Body transit</b> (from {@code SableTransitController} after a successful
 *       parent flip) appends the crossed portal FORWARD and reframes the held
 *       orientation through the portal rotation — same-dimension transits included.</li>
 * </ul>
 *
 * <p>Adjacent links that compose to the identity annihilate, so walking or dragging back
 * through a portal pops the chain instead of growing it, and any sequential multi-portal
 * recursion (in, out, through another, all the way back) is just chain algebra.
 *
 * <p>The PD goal mapping is a pure fold of the absolute cursor point through the chain
 * ({@link #mapGoal}). Straddle sessions never affect the goal: while a body straddles,
 * its native pose is authoritative and the folded goal simply continues past the portal
 * plane; image colliders own the far side.
 *
 * <p><b>Orientation ack protocol.</b> A body transit is server-initiated, so drag packets
 * composed by the client BEFORE it learned of the transit can arrive AFTER the server
 * reframed the held orientation, stomping it with a stale value for a tick. Each transit
 * therefore records a pending rotation under a new revision; incoming packet orientations
 * are premultiplied by the unacknowledged pending rotations until the client acknowledges
 * the revision (it does so while processing the ordered rebase RPC). TCP ordering makes
 * this exact, not heuristic. Player teleports need no ack: they are client-initiated, so
 * every packet after the client's own teleport is already in the new frame.
 */
public final class IplGrabChain {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-grab-chain");

    private static final Map<UUID, Frame> FRAMES = new HashMap<>();

    /**
     * A grabbed body inside a portal loop appends one link per crossing, forever. The whole
     * chain is broadcast to every client as ONE string, so an unbounded chain eventually
     * exceeds the packet string limit and the connection dies with an EncoderException on
     * clientbound/custom_payload instead of the drag merely degrading. Bound it.
     */
    private static final int MAX_CHAIN_LINKS = 32;

    private IplGrabChain() {}

    // ------------------------------------------------------------------
    // Lifecycle.
    // ------------------------------------------------------------------

    /** Ensure a frame exists for this drag (idempotent; the seed RPC usually created it). */
    public static void begin(ServerPlayer player, UUID subId) {
        Frame existing = FRAMES.get(player.getUUID());
        if (existing == null || !existing.subId.equals(subId)) {
            FRAMES.put(player.getUUID(), new Frame(subId, player.getUUID()));
            broadcast(player.server, player.getUUID());
        }
    }

    public static void end(MinecraftServer server, UUID playerId) {
        if (FRAMES.remove(playerId) != null) {
            broadcastClear(server, playerId);
        }
    }

    public static void clearAll() {
        FRAMES.clear();
    }

    /** Players whose active staff session owns this body (rehome handoff + rebase targets). */
    public static Set<UUID> getDraggingPlayers(UUID subId) {
        Set<UUID> players = new HashSet<>();
        for (Map.Entry<UUID, Frame> entry : FRAMES.entrySet()) {
            if (entry.getValue().subId.equals(subId)) players.add(entry.getKey());
        }
        return players;
    }

    // ------------------------------------------------------------------
    // Frame-changing events.
    // ------------------------------------------------------------------

    /**
     * The player crossed a portal (any pair of dimensions, including the same one).
     * Called by {@code ServerTeleportationManager} with the exact portal crossed.
     */
    public static void onPlayerTeleported(ServerPlayer player, Portal portal) {
        Frame frame = FRAMES.get(player.getUUID());
        if (frame == null) return;

        frame.chain = IplGrabLink.prepend(frame.chain, IplGrabLink.inverse(portal));
        frame.revision++;

        // The stored cursor vector was composed from the pre-teleport look direction. IP
        // rotates the client camera through the portal, so the next packet's vector is the
        // portal rotation applied to the old one; apply the same rotation to the stored
        // value so the physics substeps between now and that packet stay continuous.
        Quaterniond rotation = rotationOf(portal);
        IplStaffDragSessionControl session = liveSession(player.server, player.getUUID());
        if (session != null) {
            session.ipl$rotateRelativeGoal(rotation);
        }

        LOG.info("[IPL-GRAB-CHAIN] player {} crossed portal {} ({} -> {}), depth={}",
            player.getUUID(), portal.getUUID(),
            portal.level().dimension().location(), portal.getDestDim().location(),
            frame.chain.size());
        broadcast(player.server, player.getUUID());
    }

    /**
     * The grabbed body completed a transit through {@code portal} (parent flip executed).
     * Applies to every player currently dragging it: append the forward link, reframe the
     * live constraint orientation, arm the pending-rotation window, and send the ordered
     * rebase RPC so the client rotates its own drag orientation and acknowledges.
     */
    public static void onBodyTransit(MinecraftServer server, UUID subId, Portal portal) {
        IplGrabLink link = IplGrabLink.forward(portal);
        Quaterniond rotation = rotationOf(portal);
        boolean identityRotation = isIdentity(rotation);

        for (Frame frame : FRAMES.values()) {
            if (!frame.subId.equals(subId)) continue;

            frame.chain = capChain(IplGrabLink.append(frame.chain, link), subId);
            frame.revision++;

            if (!identityRotation) {
                frame.pendingRotations.add(new PendingRotation(frame.revision, new Quaterniond(rotation)));
            }
            // The body is now in portal destination frame while its native motor still
            // holds the prior source-frame target. Reframe it NOW, including identity-
            // rotation portals, rather than letting the next physics substep correct it.
            IplStaffDragSessionControl session = liveSession(server, frame.playerId);
            if (session != null) {
                session.ipl$reframeAfterTransit(portal);
            }

            ServerPlayer player = server.getPlayerList().getPlayer(frame.playerId);
            if (player != null) {
                qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                    player,
                    "ipl.sable.client.IplGrabChainClient.RemoteCallables.dragRebase",
                    subId.toString(), Long.toString(frame.revision), link.encode()
                );
            }
            LOG.debug("[IPL-GRAB-CHAIN] body {} transited portal {} for player {}, depth={} rev={}",
                subId, portal.getUUID(), frame.playerId, frame.chain.size(), frame.revision);
            broadcast(server, frame.playerId);
        }
    }

    // ------------------------------------------------------------------
    // Goal + packet mapping.
    // ------------------------------------------------------------------

    /**
     * Fold the absolute cursor goal (player world frame) through the chain into the
     * grabbed body's parent frame. Pure function of recorded events — no geometry
     * inference. On a desync (chain endpoints disagree with reality) the goal passes
     * through unchanged and the desync is logged: visible, never silently guessed around.
     */
    public static Vec3 mapGoal(ServerPlayer player, SubLevel sub, Vec3 goal) {
        Frame frame = FRAMES.get(player.getUUID());
        if (frame == null || !frame.subId.equals(sub.getUniqueId())) return goal;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
        if (parent == null) return goal;

        if (frame.chain.isEmpty()) {
            if (!player.level().dimension().equals(parent.dimension())) {
                frame.logDesync(LOG, "empty chain but player {} is in {} while body parent is {}",
                    player.getUUID(), player.level().dimension().location(),
                    parent.dimension().location());
            }
            return goal;
        }

        IplGrabLink first = frame.chain.get(0);
        IplGrabLink last = frame.chain.get(frame.chain.size() - 1);
        if (!first.fromDim().equals(player.level().dimension())
            || !last.toDim().equals(parent.dimension())) {
            frame.logDesync(LOG, "chain endpoints {}->{} disagree with player {} / parent {}",
                first.fromDim().location(), last.toDim().location(),
                player.level().dimension().location(), parent.dimension().location());
            return goal;
        }

        return IplGrabLink.fold(frame.chain, goal);
    }

    /**
     * {@link #mapGoal} for a DIRECTION (a velocity): the chain's rotations only, with no
     * translation. Used by the drag motor's velocity lead, which needs the goal's rate of
     * change expressed in the body's parent frame rather than the player's.
     */
    public static Vec3 mapGoalDirection(ServerPlayer player, SubLevel sub, Vec3 direction) {
        Frame frame = FRAMES.get(player.getUUID());
        if (frame == null || !frame.subId.equals(sub.getUniqueId())) return direction;
        if (frame.chain.isEmpty()) return direction;
        if (!frame.chain.get(0).fromDim().equals(player.level().dimension())) return direction;
        return IplGrabLink.foldDirection(frame.chain, direction);
    }

    /**
     * Premultiply an incoming packet orientation by the rotations of transits the client
     * has not acknowledged yet (see the class doc). Identity when nothing is pending.
     */
    public static Quaterniond adjustIncomingOrientation(UUID playerId, Quaterniond incoming) {
        Frame frame = FRAMES.get(playerId);
        if (frame == null || frame.pendingRotations.isEmpty()) return incoming;
        Quaterniond adjusted = new Quaterniond(incoming);
        for (PendingRotation pending : frame.pendingRotations) {
            adjusted.premul(pending.rotation());
        }
        return adjusted;
    }

    // ------------------------------------------------------------------
    // Client RPC entry points.
    // ------------------------------------------------------------------

    public static final class RemoteCallables {

        /**
         * Grab start: the client's pick raycast walked these portals, in order, from the
         * player's world to the hit. Only identities are trusted — the server resolves
         * each portal itself and snapshots server-side geometry. An unresolvable or
         * discontinuous path yields an empty chain plus a log line, never a guess.
         */
        public static void seedChain(ServerPlayer player, String subUuid, String encodedPath) {
            try {
                UUID subId = UUID.fromString(subUuid);
                Frame frame = new Frame(subId, player.getUUID());
                frame.chain = resolveSeed(player, encodedPath);
                FRAMES.put(player.getUUID(), frame);
                LOG.info("[IPL-GRAB-CHAIN] seeded chain for player {} sub {} depth={}",
                    player.getUUID(), subId, frame.chain.size());
                broadcast(player.server, player.getUUID());
            } catch (RuntimeException e) {
                LOG.error("[IPL-GRAB-CHAIN] bad seed from {}", player.getUUID(), e);
            }
        }

        /** Client processed the rebase RPC for this revision; stop compensating packets. */
        public static void ackRebase(ServerPlayer player, String revision) {
            Frame frame = FRAMES.get(player.getUUID());
            if (frame == null) return;
            try {
                long acked = Long.parseLong(revision);
                frame.pendingRotations.removeIf(pending -> pending.revision() <= acked);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * Resolve a seed path of {@code portalUuid@dimensionId} steps into links. A step with
     * an {@code @inv} suffix is an image grab: the click landed on that session portal's
     * IMAGE, so the link is the portal's inverse (image frame back to the body frame). The
     * dimension is always the portal ENTITY's own level; continuity is checked against the
     * side the step enters from.
     */
    private static List<IplGrabLink> resolveSeed(ServerPlayer player, String encodedPath) {
        if (encodedPath == null || encodedPath.isEmpty()) return List.of();
        List<IplGrabLink> chain = new ArrayList<>(4);
        ResourceKey<Level> at = player.level().dimension();
        for (String step : encodedPath.split("\\|")) {
            boolean inverse = step.endsWith("@inv");
            String body = inverse ? step.substring(0, step.length() - "@inv".length()) : step;
            int sep = body.indexOf('@');
            if (sep < 0) return failSeed(player, encodedPath, "malformed step " + step);
            UUID portalId = UUID.fromString(body.substring(0, sep));
            ResourceKey<Level> dim = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(body.substring(sep + 1)));
            ServerLevel level = player.server.getLevel(dim);
            Portal portal = level == null ? null : findPortal(level, portalId);
            if (portal == null) return failSeed(player, encodedPath, "unresolvable portal " + portalId);
            IplGrabLink link = inverse ? IplGrabLink.inverse(portal) : IplGrabLink.forward(portal);
            if (!link.fromDim().equals(at)) {
                return failSeed(player, encodedPath, "discontinuous at " + step);
            }
            chain.add(link);
            at = link.toDim();
        }
        return List.copyOf(chain);
    }

    private static List<IplGrabLink> failSeed(ServerPlayer player, String path, String reason) {
        LOG.warn("[IPL-GRAB-CHAIN] seed rejected for {} ({}): {}", player.getUUID(), reason, path);
        return List.of();
    }

    @Nullable
    private static Portal findPortal(ServerLevel level, UUID portalId) {
        Entity entity = level.getEntity(portalId);
        if (entity instanceof Portal portal && !portal.isRemoved()) return portal;
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (portal.getUUID().equals(portalId) && !portal.isRemoved()) return portal;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    @Nullable
    private static IplStaffDragSessionControl liveSession(MinecraftServer server, UUID playerId) {
        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(server);
        if (hosting == null) return null;
        PhysicsStaffServerHandler handler = PhysicsStaffServerHandler.get(hosting);
        Object session = ((IplStaffServerHandlerAccessorMixin) (Object) handler)
            .ipl$getDraggingSessions().get(playerId);
        return session instanceof IplStaffDragSessionControl control ? control : null;
    }

    /** Keep the newest links: they describe the frame the body is actually in right now. */
    private static List<IplGrabLink> capChain(List<IplGrabLink> chain, UUID subId) {
        if (chain.size() <= MAX_CHAIN_LINKS) return chain;
        LOG.warn("[IPL-GRAB-CHAIN] body {} exceeded {} chain links; dropping the oldest",
            subId, MAX_CHAIN_LINKS);
        return List.copyOf(new ArrayList<>(
            chain.subList(chain.size() - MAX_CHAIN_LINKS, chain.size())));
    }

    private static Quaterniond rotationOf(Portal portal) {
        qouteall.q_misc_util.my_util.DQuaternion q = portal.getRotationD();
        return q == null ? new Quaterniond() : new Quaterniond(q.x, q.y, q.z, q.w);
    }

    private static boolean isIdentity(Quaterniond q) {
        return Math.abs(q.x) < IplGrabLink.ROTATION_EPSILON
            && Math.abs(q.y) < IplGrabLink.ROTATION_EPSILON
            && Math.abs(q.z) < IplGrabLink.ROTATION_EPSILON
            && Math.abs(Math.abs(q.w) - 1.0) < IplGrabLink.ROTATION_EPSILON;
    }

    /** Broadcast this player's chain snapshot to every client (beams are visible to all). */
    private static void broadcast(MinecraftServer server, UUID playerId) {
        if (server == null) return;
        Frame frame = FRAMES.get(playerId);
        if (frame == null) return;
        String encoded = IplGrabLink.encodeChain(frame.chain);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                viewer,
                "ipl.sable.client.IplGrabChainClient.RemoteCallables.snapshot",
                playerId.toString(), frame.subId.toString(),
                Long.toString(frame.revision), encoded
            );
        }
    }

    private static void broadcastClear(MinecraftServer server, UUID playerId) {
        if (server == null) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                viewer,
                "ipl.sable.client.IplGrabChainClient.RemoteCallables.clear",
                playerId.toString()
            );
        }
    }

    private record PendingRotation(long revision, Quaterniond rotation) {}

    private static final class Frame {
        final UUID subId;
        final UUID playerId;
        List<IplGrabLink> chain = List.of();
        long revision;
        final List<PendingRotation> pendingRotations = new ArrayList<>(2);
        private long lastDesyncLogMs;

        Frame(UUID subId, UUID playerId) {
            this.subId = subId;
            this.playerId = playerId;
        }

        void logDesync(Logger log, String message, Object... args) {
            long now = System.currentTimeMillis();
            if (now - lastDesyncLogMs < 2000) return;
            lastDesyncLogMs = now;
            log.warn("[IPL-GRAB-CHAIN] desync: " + message, args);
        }
    }
}
