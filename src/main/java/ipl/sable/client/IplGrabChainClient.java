package ipl.sable.client;

import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import ipl.sable.transit.IplGrabLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of {@link ipl.sable.transit.IplGrabChain}: per dragging player, the
 * portal chain from that player's eye to the grabbed body's parent frame.
 *
 * <p>Two sources, one truth:
 * <ul>
 *   <li><b>Server snapshots</b> (broadcast on every chain change) are authoritative and
 *       cover every player's beam, not just the local one.</li>
 *   <li><b>Local prediction</b> keeps the LOCAL player's chain correct within the same
 *       frame an event happens: the pick path is seeded at grab, the client teleport
 *       manager reports the exact portal the moment the camera crosses it, and the
 *       ordered rebase RPC appends body transits. Snapshots adopt over prediction only
 *       when their revision advances past the last one applied locally, so a stale
 *       broadcast can never rewind a fresher local chain.</li>
 * </ul>
 *
 * <p>No geometry inference anywhere: every mutation names its exact portal.
 */
public final class IplGrabChainClient {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-grab-chain-client");

    private record ChainState(UUID subId, long revision, List<IplGrabLink> chain) {}

    /** Every dragging player's chain, from server snapshots. */
    private static final ConcurrentMap<UUID, ChainState> SNAPSHOTS = new ConcurrentHashMap<>();

    /** Local player's predicted chain (null when the local player is not dragging). */
    @Nullable
    private static volatile ChainState local;

    private IplGrabChainClient() {}

    // ------------------------------------------------------------------
    // Reads.
    // ------------------------------------------------------------------

    /** The chain for {@code ownerId}'s grab of {@code subId}, or empty when unknown. */
    public static List<IplGrabLink> chainFor(UUID ownerId, UUID subId) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(ownerId)) {
            ChainState mine = local;
            if (mine != null && mine.subId().equals(subId)) return mine.chain();
        }
        ChainState snapshot = SNAPSHOTS.get(ownerId);
        if (snapshot != null && snapshot.subId().equals(subId)) return snapshot.chain();
        return List.of();
    }

    /** The local player's active grab chain, or empty. */
    public static List<IplGrabLink> localChain(UUID subId) {
        ChainState mine = local;
        return mine != null && mine.subId().equals(subId) ? mine.chain() : List.of();
    }

    // ------------------------------------------------------------------
    // Local events.
    // ------------------------------------------------------------------

    /**
     * Grab start: seed the local chain from the pick-time portal path and hand the same
     * path (identities only) to the server, which re-snapshots geometry itself.
     *
     * @param pickPath        portals the pick ray traversed FORWARD, in order
     * @param imageGrabPortal when the click landed on a straddle IMAGE, the session portal
     *                        whose inverse links the image frame back to the body's parent
     */
    public static void seedLocal(UUID subId, List<Portal> pickPath, @Nullable Portal imageGrabPortal) {
        List<IplGrabLink> chain = List.of();
        StringBuilder encodedPath = new StringBuilder();
        for (Portal portal : pickPath) {
            chain = IplGrabLink.append(chain, IplGrabLink.forward(portal));
            if (encodedPath.length() > 0) encodedPath.append('|');
            encodedPath.append(portal.getUUID()).append('@')
                .append(portal.level().dimension().location());
        }
        if (imageGrabPortal != null) {
            chain = IplGrabLink.append(chain, IplGrabLink.inverse(imageGrabPortal));
            if (encodedPath.length() > 0) encodedPath.append('|');
            encodedPath.append(imageGrabPortal.getUUID()).append('@')
                .append(imageGrabPortal.level().dimension().location()).append("@inv");
        }
        local = new ChainState(subId, 0L, chain);
        McRemoteProcedureCallClient.tellServerToInvoke(
            "ipl.sable.transit.IplGrabChain.RemoteCallables.seedChain",
            subId.toString(), encodedPath.toString()
        );
    }

    /** Drag ended locally. The server clears its side from its own stop hook. */
    public static void clearLocal() {
        local = null;
    }

    /**
     * The local player just crossed {@code portal} (called by the client teleportation
     * manager, same-dimension hops included). Zero-latency prediction; the matching
     * server snapshot lands a round-trip later and adopts an identical chain.
     */
    public static void onLocalPlayerTeleported(Portal portal) {
        ChainState mine = local;
        if (mine == null) return;
        local = new ChainState(
            mine.subId(), mine.revision(),
            IplGrabLink.prepend(mine.chain(), IplGrabLink.inverse(portal))
        );
        LOG.info("[IPL-GRAB-CHAIN-C] local player crossed {} depth={}",
            portal.getUUID(), local.chain().size());
    }

    // ------------------------------------------------------------------
    // Server RPC entry points.
    // ------------------------------------------------------------------

    public static final class RemoteCallables {

        /** Authoritative chain snapshot for one dragging player. */
        public static void snapshot(String playerUuid, String subUuid, String revision, String encodedChain) {
            try {
                UUID playerId = UUID.fromString(playerUuid);
                UUID subId = UUID.fromString(subUuid);
                long rev = Long.parseLong(revision);
                ChainState state = new ChainState(subId, rev, IplGrabLink.decodeChain(encodedChain));
                SNAPSHOTS.put(playerId, state);

                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && player.getUUID().equals(playerId)) {
                    ChainState mine = local;
                    // Adopt over prediction only when the server has seen at least every
                    // event we already applied locally (revision advances monotonically).
                    if (mine == null || !mine.subId().equals(subId) || rev >= mine.revision()) {
                        local = state;
                    }
                }
            } catch (RuntimeException e) {
                LOG.error("[IPL-GRAB-CHAIN-C] bad snapshot for {}", playerUuid, e);
            }
        }

        public static void clear(String playerUuid) {
            try {
                UUID playerId = UUID.fromString(playerUuid);
                SNAPSHOTS.remove(playerId);
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && player.getUUID().equals(playerId)) {
                    local = null;
                }
            } catch (RuntimeException ignored) {
            }
        }

        /**
         * The grabbed body transited a portal while the local player drags it. Ordered
         * after the pose handoff on the same channel: append the link, rotate the local
         * drag orientation through the portal rotation (same-dimension included — the
         * old code skipped it there and threw away the held rotation), and acknowledge
         * so the server stops compensating this client's in-flight packets.
         */
        public static void dragRebase(String subUuid, String revision, String encodedLink) {
            try {
                UUID subId = UUID.fromString(subUuid);
                IplGrabLink link = IplGrabLink.decode(encodedLink);
                if (link == null) {
                    LOG.error("[IPL-GRAB-CHAIN-C] undecodable rebase link for {}", subUuid);
                    return;
                }

                ChainState mine = local;
                long rev = Long.parseLong(revision);
                if (mine != null && mine.subId().equals(subId)) {
                    local = new ChainState(subId, Math.max(mine.revision(), rev),
                        IplGrabLink.append(mine.chain(), link));
                }

                PhysicsStaffClientHandler handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
                PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
                if (session != null && session.dragSubLevel().getUniqueId().equals(subId)) {
                    session.dragOrientation().set(
                        new Quaterniond(link.rotation()).mul(session.dragOrientation()));
                }

                McRemoteProcedureCallClient.tellServerToInvoke(
                    "ipl.sable.transit.IplGrabChain.RemoteCallables.ackRebase", revision
                );
                LOG.info("[IPL-GRAB-CHAIN-C] rebase sub={} rev={} depth={}",
                    subId, rev, local == null ? -1 : local.chain().size());
            } catch (RuntimeException e) {
                LOG.error("[IPL-GRAB-CHAIN-C] bad rebase for {}", subUuid, e);
            }
        }
    }
}
