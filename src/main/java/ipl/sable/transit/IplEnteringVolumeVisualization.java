package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Debug transport for one selected ship's exact detector A/B poses. */
public final class IplEnteringVolumeVisualization {

    public enum Mode { OFF, ON }

    private static volatile Mode mode = Mode.OFF;
    private static volatile UUID targetShip;
    private static volatile long targetRevision = Long.MIN_VALUE;
    private static final Map<UUID, Snapshot> LIVE = new LinkedHashMap<>();
    private static final Map<UUID, dev.ryanhcode.sable.companion.math.Pose3d> BUFFERS = new HashMap<>();

    private IplEnteringVolumeVisualization() {}

    public static Mode mode() {
        return mode;
    }

    public static boolean setMode(MinecraftServer server, ServerPlayer player, Mode next) {
        UUID selected = null;
        ServerSubLevel selectedShip = null;
        if (next == Mode.ON) {
            HitResult hit = player.pick(64.0, 0.0F, false);
            if (!(hit instanceof BlockHitResult blockHit)) return false;
            SubLevel sub = dev.ryanhcode.sable.Sable.HELPER.getContaining(player.serverLevel(), blockHit.getLocation());
            if (!(sub instanceof ServerSubLevel ship) || ship.isRemoved()) return false;
            selected = ship.getUniqueId();
            selectedShip = ship;
            targetRevision = IplPortalVolumeCache.revision(ship);
        }
        mode = next;
        targetShip = selected;
        LIVE.clear();
        BUFFERS.clear();
        if (next == Mode.OFF) targetRevision = Long.MIN_VALUE;
        if (server == null) return true;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(viewer,
                "ipl.sable.client.IplEnteringVolumeRenderer.RemoteCallables.setMode",
                next.name(), selected == null ? "" : selected.toString(), "");
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(viewer,
                "ipl.sable.client.IplEnteringVolumeRenderer.RemoteCallables.volumes", "");
        }
        if (selectedShip != null) sendShape(server, selectedShip);
        if (selectedShip != null) sendBlocks(server, selectedShip);
        return true;
    }

    public static void beginTick() {
        if (mode == Mode.ON) LIVE.clear();
    }

    /** A source-frame debug buffer cannot remain visible while the body is portal-mapped. */
    public static void clearBuffer(ServerSubLevel ship) {
        UUID id = ship.getUniqueId();
        BUFFERS.remove(id);
        Snapshot live = LIVE.get(id);
        if (live != null && !live.bufferTransform().isEmpty()) {
            LIVE.put(id, live.withBufferTransform(""));
        }
    }

    /** Start the next source-frame buffer immediately after a session has fully ended. */
    public static void replaceBuffer(ServerSubLevel ship) {
        if (mode != Mode.ON || !ship.getUniqueId().equals(targetShip)) return;
        UUID id = ship.getUniqueId();
        var next = new dev.ryanhcode.sable.companion.math.Pose3d(ship.logicalPose());
        // Put replaces old buffer before its reference is released, so render state has
        // no empty interval between a complete portal exit and the next tick.
        BUFFERS.put(id, next);
        Snapshot live = LIVE.get(id);
        if (live != null) LIVE.put(id, live.withBufferTransform(encodeTransform(next)));
    }

    /** Records detector result and exact finite aperture, retaining only selected ship. */
    public static void record(
        ServerSubLevel ship, Portal portal, PortalCrossingDetector.CrossingState state
    ) {
        if (mode != Mode.ON || !ship.getUniqueId().equals(targetShip)
            || !(portal.level() instanceof ServerLevel level)) return;
        Snapshot next = snapshot(ship, level, state.phase(), portal,
            state.sweptIntersectsPortalAperture(), state.sweptTowardDestination(),
            IplAtlasStraddleSession.hasSessionKey(new StraddleKey(ship.getUniqueId(), portal.getUUID())));
        Snapshot previous = LIVE.get(ship.getUniqueId());
        // One tick can inspect both faces. Keep the informative hit instead of letting
        // the subsequent non-hit face erase it from the overlay.
        if (previous == null || previous.portal().isEmpty() || (!previous.apertureHit() && next.apertureHit())
            || (!previous.towardDestination() && next.towardDestination())) {
            LIVE.put(ship.getUniqueId(), next);
        }
    }

    /** Refreshes endpoints even with no portal nearby, so the selected real volume follows ship. */
    public static void captureTick(ServerSubLevel ship, ServerLevel parent) {
        if (mode != Mode.ON || !ship.getUniqueId().equals(targetShip)) return;
        refreshShapeIfEdited(ship, parent.getServer());
        if (IplAtlasStraddleSession.hasSession(ship.getUniqueId())) {
            clearBuffer(ship);
        } else {
            // Older retained pose is visibly behind the live body. `lastPose` is often
            // identical to the rendered interpolated pose and made the debug buffer vanish.
            var buffered = PortalCrossingDetector.bufferedPose(ship);
            replaceBufferAt(ship, buffered == null ? ship.lastPose() : buffered);
        }
        LIVE.put(ship.getUniqueId(), snapshot(
            ship, parent, PortalCrossingDetector.CrossingPhase.APPROACHING, null, false, false, false));
    }

    public static void flush(MinecraftServer server) {
        if (mode == Mode.ON && server != null) broadcast(server);
    }

    private static Snapshot snapshot(
        ServerSubLevel ship, ServerLevel level, PortalCrossingDetector.CrossingPhase phase,
        Portal portal, boolean apertureHit, boolean towardDestination, boolean mappedSession
    ) {
        return new Snapshot(ship.getUniqueId(), phase, level.dimension().location().toString(),
            encodeTransform(ship.lastPose()), encodeTransform(ship.logicalPose()),
            apertureHit, towardDestination, portal == null ? "" : encodePortal(portal),
            portal != null && (portal.getDestDim().equals(net.minecraft.world.level.Level.NETHER)
                || level.dimension().equals(net.minecraft.world.level.Level.NETHER)),
            mappedSession && portal != null ? encodeMapping(portal) : "",
            BUFFERS.containsKey(ship.getUniqueId()) ? encodeTransform(BUFFERS.get(ship.getUniqueId())) : "");
    }

    private static void replaceBufferAt(
        ServerSubLevel ship, dev.ryanhcode.sable.companion.math.Pose3dc pose
    ) {
        BUFFERS.put(ship.getUniqueId(), new dev.ryanhcode.sable.companion.math.Pose3d(pose));
    }

    private static void broadcast(MinecraftServer server) {
        StringBuilder payload = new StringBuilder(LIVE.size() * 256);
        for (Snapshot snapshot : LIVE.values()) {
            if (payload.length() > 0) payload.append('|');
            payload.append(snapshot.shipId()).append(',').append(snapshot.phase().name()).append(',')
                .append(snapshot.dimension()).append(',').append(snapshot.previousTransform())
                .append('/').append(snapshot.currentTransform()).append('~')
                .append(snapshot.apertureHit() ? '1' : '0').append('~')
                .append(snapshot.towardDestination() ? '1' : '0').append('~')
                .append(snapshot.portal()).append('~')
                .append(snapshot.netherPortal() ? '1' : '0').append('~')
                .append(snapshot.mappingTransform()).append('~')
                .append(snapshot.bufferTransform());
        }
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(viewer,
                "ipl.sable.client.IplEnteringVolumeRenderer.RemoteCallables.volumes", payload.toString());
        }
    }

    /** Sends only complete face records, so a large exterior mesh cannot exceed an RPC string limit. */
    private static void sendShape(MinecraftServer server, ServerSubLevel ship) {
        List<String> pieces = splitShape(encodeShape(ship));
        for (int i = 0; i < pieces.size(); i++) {
            boolean last = i == pieces.size() - 1;
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(viewer,
                    "ipl.sable.client.IplEnteringVolumeRenderer.RemoteCallables.shapePart", pieces.get(i), last);
            }
        }
    }

    /** Full occupied blocks are debug-only: client draws the retained pose buffer as 1x1 cubes. */
    private static void sendBlocks(MinecraftServer server, ServerSubLevel ship) {
        List<String> pieces = splitBlocks(encodeBlocks(ship));
        for (int i = 0; i < pieces.size(); i++) {
            boolean last = i == pieces.size() - 1;
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(viewer,
                    "ipl.sable.client.IplEnteringVolumeRenderer.RemoteCallables.blocksPart", pieces.get(i), last);
            }
        }
    }

    private static void refreshShapeIfEdited(ServerSubLevel ship, MinecraftServer server) {
        long revision = IplPortalVolumeCache.revision(ship);
        if (revision == targetRevision) return;
        targetRevision = revision;
        sendShape(server, ship);
        sendBlocks(server, ship);
    }

    private static List<String> splitShape(String shape) {
        final int maxPartLength = 12_000;
        if (shape.isEmpty()) return List.of("");
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder(maxPartLength);
        for (String face : shape.split(";")) {
            int extra = part.isEmpty() ? face.length() : face.length() + 1;
            if (!part.isEmpty() && part.length() + extra > maxPartLength) {
                parts.add(part.toString());
                part.setLength(0);
            }
            if (!part.isEmpty()) part.append(';');
            part.append(face);
        }
        if (!part.isEmpty()) parts.add(part.toString());
        return parts;
    }

    private static List<String> splitBlocks(String blocks) {
        final int maxPartLength = 12_000;
        if (blocks.isEmpty()) return List.of("");
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder(maxPartLength);
        for (String block : blocks.split(";")) {
            int extra = part.isEmpty() ? block.length() : block.length() + 1;
            if (!part.isEmpty() && part.length() + extra > maxPartLength) {
                parts.add(part.toString());
                part.setLength(0);
            }
            if (!part.isEmpty()) part.append(';');
            part.append(block);
        }
        if (!part.isEmpty()) parts.add(part.toString());
        return parts;
    }

    private static String encodeTransform(dev.ryanhcode.sable.companion.math.Pose3dc pose) {
        return point(pose.transformPosition(Vec3.ZERO)) + ';'
            + point(pose.transformPosition(new Vec3(1, 0, 0))) + ';'
            + point(pose.transformPosition(new Vec3(0, 1, 0))) + ';'
            + point(pose.transformPosition(new Vec3(0, 0, 1)));
    }

    private static String point(Vec3 point) {
        return point.x + "," + point.y + "," + point.z;
    }

    /** `origin;axisW;axisH;normal;width;height` contains no packet-level separators. */
    private static String encodePortal(Portal portal) {
        return point(portal.getOriginPos()) + ';' + point(portal.getAxisW()) + ';'
            + point(portal.getAxisH()) + ';' + point(portal.getNormal()) + ';'
            + portal.getWidth() + ';' + portal.getHeight();
    }

    /** Source-to-destination isometry for purple debug geometry in source world coordinates. */
    private static String encodeMapping(Portal portal) {
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
        return point(mapping.mapPoint(Vec3.ZERO)) + ';' + point(mapping.mapVec(new Vec3(1, 0, 0))) + ';'
            + point(mapping.mapVec(new Vec3(0, 1, 0))) + ';' + point(mapping.mapVec(new Vec3(0, 0, 1)));
    }

    private static String encodeBlocks(ServerSubLevel ship) {
        StringBuilder out = new StringBuilder();
        for (BlockPos block : IplPortalVolumeCache.blocks(ship)) {
            if (out.length() > 0) out.append(';');
            out.append(block.getX()).append(',').append(block.getY()).append(',').append(block.getZ());
        }
        return out.toString();
    }

    /** Exact external cube faces. Each four-point face makes four unambiguous A-to-B bridge quads. */
    private static String encodeShape(ServerSubLevel ship) {
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(ship);
        Set<BlockPos> occupied = new HashSet<>(blocks);
        StringBuilder out = new StringBuilder(blocks.size() * 96);
        for (BlockPos block : blocks) {
            for (Direction side : Direction.values()) {
                if (occupied.contains(block.relative(side))) continue;
                if (out.length() > 0) out.append(';');
                appendFace(out, block, side);
            }
        }
        return out.toString();
    }

    private static void appendFace(StringBuilder out, BlockPos block, Direction side) {
        int x = block.getX(), y = block.getY(), z = block.getZ();
        int[][] corners = switch (side) {
            case DOWN -> new int[][] {{x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}, {x, y, z + 1}};
            case UP -> new int[][] {{x, y + 1, z}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y + 1, z}};
            case NORTH -> new int[][] {{x, y, z}, {x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y, z}};
            case SOUTH -> new int[][] {{x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}};
            case WEST -> new int[][] {{x, y, z}, {x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}};
            case EAST -> new int[][] {{x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}};
        };
        for (int i = 0; i < corners.length; i++) {
            if (i > 0) out.append('|');
            out.append(corners[i][0]).append(',').append(corners[i][1]).append(',').append(corners[i][2]);
        }
    }

    private record Snapshot(UUID shipId, PortalCrossingDetector.CrossingPhase phase, String dimension,
                            String previousTransform, String currentTransform, boolean apertureHit,
                            boolean towardDestination, String portal, boolean netherPortal,
                            String mappingTransform, String bufferTransform) {
        Snapshot withBufferTransform(String value) {
            return new Snapshot(shipId, phase, dimension, previousTransform, currentTransform,
                apertureHit, towardDestination, portal, netherPortal, mappingTransform, value);
        }
    }
}
