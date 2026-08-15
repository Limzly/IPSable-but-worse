package ipl.sable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL32C;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** NeoForge-stage overlay of the selected ship's exact A-to-B swept block volume. */
public final class IplEnteringVolumeRenderer {

    private enum Mode { OFF, ON }
    private enum Phase { APPROACHING, STRADDLING, CROSSED }

    private static volatile Mode mode = Mode.OFF;
    private static volatile List<Face> shape = List.of();
    private static volatile List<BlockPos> blocks = List.of();
    private static volatile UUID shapeShip;
    private static volatile Map<UUID, Volume> volumes = Map.of();
    private static volatile UUID selectedShip;
    private static volatile long volumeExpiresAt;
    private static final StringBuilder INCOMING_SHAPE = new StringBuilder();
    private static final StringBuilder INCOMING_BLOCKS = new StringBuilder();
    private static boolean clipDistance0WasEnabled;
    private static boolean clipDistance1WasEnabled;
    private static boolean clipDistance2WasEnabled;

    /**
     * Suspends BOTH portal clip planes for the debug overlay.
     *
     * <p>The overlay is diagnostic geometry: it must show the swept volume the detector
     * actually computed, not the part of it that survives the portal's render clipping.
     * Inside a portal-through pass two clip planes are live at once -- ImmersivePortals'
     * own front clipping on {@code gl_ClipDistance[0]} and our sub-level cut on
     * {@code gl_ClipDistance[1]} -- and they keep opposite halves of (very nearly) the
     * same plane. Anything drawn through them, including this overlay, survives only in
     * the sliver where both agree, which is why the sweep visualisation appeared cut off
     * at the portal even though the volume behind it was complete. Turning the planes off
     * for this draw makes the overlay show the whole volume, which is the entire point of
     * having it.
     *
     * <p>The previous enable state is captured and restored rather than blindly
     * re-enabled: the overlay renders in passes where neither plane, one, or both may be
     * active, and leaving a plane on that the pass never turned on would clip the world
     * geometry drawn after us.
     */
    private static final RenderStateShard.TexturingStateShard NO_PORTAL_CLIP =
        new RenderStateShard.TexturingStateShard(
            "ipl_entering_volume_no_portal_clip",
            () -> {
                clipDistance0WasEnabled = GL32C.glIsEnabled(GL32C.GL_CLIP_DISTANCE0);
                clipDistance1WasEnabled = GL32C.glIsEnabled(GL32C.GL_CLIP_DISTANCE1);
                clipDistance2WasEnabled = GL32C.glIsEnabled(GL32C.GL_CLIP_DISTANCE2);
                if (clipDistance0WasEnabled) GL32C.glDisable(GL32C.GL_CLIP_DISTANCE0);
                if (clipDistance1WasEnabled) GL32C.glDisable(GL32C.GL_CLIP_DISTANCE1);
                if (clipDistance2WasEnabled) GL32C.glDisable(GL32C.GL_CLIP_DISTANCE2);
            },
            () -> {
                if (clipDistance0WasEnabled) GL32C.glEnable(GL32C.GL_CLIP_DISTANCE0);
                if (clipDistance1WasEnabled) GL32C.glEnable(GL32C.GL_CLIP_DISTANCE1);
                if (clipDistance2WasEnabled) GL32C.glEnable(GL32C.GL_CLIP_DISTANCE2);
            });

    private static final RenderType FOG_RENDER_TYPE = RenderType.create(
        "ipl_entering_volume_swept_triangles", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES,
        RenderType.TRANSIENT_BUFFER_SIZE, false, true, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setTexturingState(NO_PORTAL_CLIP)
            .createCompositeState(false));
    private static final int VOXELS_PER_BLOCK = 6;
    private static final int MAX_SWEEP_SAMPLES = 3_240;

    private IplEnteringVolumeRenderer() {}

    /** Called from NeoForge for every world pass, including the root pass outside portal eye-space. */
    public static void render(
        PoseStack poseStack, MultiBufferSource.BufferSource buffers, Camera camera, ClientLevel level
    ) {
        if (mode == Mode.OFF || selectedShip == null) return;
        // IP leaves LevelRenderer.level at the root world during recursive portal passes.
        // Root pass uses the supplied level; nested pass must use IP's active world instead.
        ClientLevel activeLevel = WorldRenderInfo.isRendering()
            ? WorldRenderInfo.getTopRenderInfo().world : level;
        if (activeLevel == null) return;

        // AFTER_PARTICLES reuses the cleared level pose stack. Position vertices are world
        // coordinates relative to its camera origin; IP keeps the matching portal-pass camera.
        poseStack.pushPose();
        Vec3 eye = camera.getPosition();
        poseStack.translate(-eye.x, -eye.y, -eye.z);
        Matrix4f matrix = poseStack.last().pose();
        Volume volume = System.nanoTime() <= volumeExpiresAt ? volumes.get(selectedShip) : null;
        boolean draw = volume != null && volume.dimension().equals(activeLevel.dimension().location());
        VertexConsumer vertices = buffers.getBuffer(FOG_RENDER_TYPE);
        if (draw && selectedShip.equals(shapeShip) && !shape.isEmpty()) renderFill(vertices, matrix, shape, volume);
        if (draw && selectedShip.equals(shapeShip) && volume.buffer() != null && !blocks.isEmpty()) {
            renderBuffer(vertices, matrix, blocks, volume.buffer());
        }
        if (draw && volume.portal() != null) renderPortal(vertices, matrix, volume);
        poseStack.popPose();
        buffers.endBatch(FOG_RENDER_TYPE);
    }

    public static RenderType fillRenderType() {
        return FOG_RENDER_TYPE;
    }

    /**
     * World-aligned 1/6-block voxels trace the swept exterior mesh. This is deliberately
     * separate from detector math: visual cells make a fast/curved A-to-B surface readable.
     */
    private static void renderFill(
        VertexConsumer vertices, Matrix4f matrix, List<Face> faces, Volume volume
    ) {
        int[] fillColor = color(volume);
        Set<Voxel> voxels = new HashSet<>();
        for (Face face : faces) {
            Vec3[] before = transform(face.vertices(), volume.before());
            Vec3[] after = transform(face.vertices(), volume.after());
            rasterizeSweepFace(voxels, before, after);
        }
        for (Voxel voxel : voxels) {
            cubeShell(vertices, matrix, voxel, voxels, color(volume, voxel, fillColor));
        }
        if (volume.netherPortal() && volume.mapping() != null) {
            renderMappedFarSide(vertices, matrix, voxels, volume);
        }
    }

    private static int[] color(Volume volume) {
        if (volume.apertureHit()) {
            return volume.towardDestination() ? new int[] {55, 255, 100} : new int[] {255, 205, 35};
        }
        Phase phase = volume.phase();
        return switch (phase) {
            case APPROACHING -> new int[] {255, 55, 55};
            case STRADDLING -> new int[] {255, 80, 65};
            case CROSSED -> new int[] {255, 110, 80};
        };
    }

    /** Purple marks the source-world projection of the actual mapped far-side volume. */
    private static int[] color(Volume volume, Voxel voxel, int[] fallback) {
        return fallback;
    }

    private static void renderMappedFarSide(
        VertexConsumer vertices, Matrix4f matrix, Set<Voxel> voxels, Volume volume
    ) {
        int[] purple = {180, 70, 255};
        Transform mapping = volume.mapping();
        for (Voxel voxel : voxels) {
            double scale = 1.0 / VOXELS_PER_BLOCK;
            Vec3 center = new Vec3((voxel.x + 0.5) * scale, (voxel.y + 0.5) * scale, (voxel.z + 0.5) * scale);
            // Keep only original geometry physically beyond the source portal plane,
            // then map it back from destination coordinates into entry-world coordinates.
            if (center.subtract(volume.portal().origin()).dot(volume.portal().normal()) >= -0.025) continue;
            mappedCubeShell(vertices, matrix, voxel, voxels, mapping, purple);
        }
    }

    /** Retained server trail pose, rendered as un-subdivided occupied 1x1 block cubes. */
    private static void renderBuffer(
        VertexConsumer vertices, Matrix4f matrix, List<BlockPos> blocks, Transform transform
    ) {
        Set<BlockPos> occupied = new HashSet<>(blocks);
        int[] color = {145, 90, 255};
        for (BlockPos block : blocks) {
            bufferCubeShell(vertices, matrix, block, occupied, transform, color);
        }
    }

    /** Draw exact finite aperture plus directed normal, independently from the swept shell. */
    private static void renderPortal(VertexConsumer vertices, Matrix4f matrix, Volume volume) {
        PortalOverlay portal = volume.portal();
        int[] sourceColor = volume.towardDestination() ? new int[] {55, 255, 100} : new int[] {65, 145, 255};
        int[] destinationColor = volume.towardDestination() ? new int[] {255, 205, 35} : new int[] {125, 70, 255};
        Vec3 w = portal.axisW().scale(portal.width() * 0.5);
        Vec3 h = portal.axisH().scale(portal.height() * 0.5);
        Vec3 origin = portal.origin();
        Vec3[] plane = {
            origin.subtract(w).subtract(h), origin.add(w).subtract(h),
            origin.add(w).add(h), origin.subtract(w).add(h)
        };
        Vec3 sourceOffset = portal.normal().scale(0.025);
        Vec3 destinationOffset = sourceOffset.scale(-1.0);
        quad(vertices, matrix, shifted(plane, sourceOffset), sourceColor, 72);
        quad(vertices, matrix, shifted(plane, destinationOffset), destinationColor, 72);

        // A thin directed marker makes the selected face unambiguous: green means the
        // swept occupied volume travelled into this face, yellow means it travelled out.
        Vec3 end = origin.subtract(portal.normal().scale(1.5));
        Vec3 side = portal.axisW().scale(0.035);
        Vec3 up = portal.axisH().scale(0.035);
        quad(vertices, matrix, new Vec3[] {
            origin.subtract(side).subtract(up), origin.add(side).subtract(up),
            end.add(side).add(up), end.subtract(side).add(up)
        }, sourceColor, 220);
    }

    private static Vec3[] shifted(Vec3[] points, Vec3 offset) {
        Vec3[] shifted = new Vec3[points.length];
        for (int i = 0; i < points.length; i++) shifted[i] = points[i].add(offset);
        return shifted;
    }

    private static Vec3[] transform(Vec3[] local, Transform transform) {
        Vec3[] world = new Vec3[local.length];
        for (int i = 0; i < local.length; i++) {
            Vec3 point = local[i];
            world[i] = transform.origin().add(transform.x().scale(point.x))
                .add(transform.y().scale(point.y)).add(transform.z().scale(point.z));
        }
        return world;
    }

    private static void rasterizeSweepFace(Set<Voxel> voxels, Vec3[] before, Vec3[] after) {
        double maxDistance = 0.0;
        for (int i = 0; i < 4; i++) {
            maxDistance = Math.max(maxDistance, before[i].distanceTo(after[i]));
        }
        int steps = Math.max(1, (int) Math.ceil(maxDistance * VOXELS_PER_BLOCK));
        steps = Math.min(steps, Math.max(1, MAX_SWEEP_SAMPLES / (VOXELS_PER_BLOCK * VOXELS_PER_BLOCK)));
        for (int step = 0; step <= steps; step++) {
            double time = (double) step / steps;
            for (int u = 0; u < VOXELS_PER_BLOCK; u++) {
                for (int v = 0; v < VOXELS_PER_BLOCK; v++) {
                    addVoxel(voxels, bilerp(before, after, time,
                        (u + 0.5) / VOXELS_PER_BLOCK, (v + 0.5) / VOXELS_PER_BLOCK));
                }
            }
        }
    }

    private static Vec3 bilerp(Vec3[] before, Vec3[] after, double time, double u, double v) {
        Vec3 a = before[0].lerp(after[0], time);
        Vec3 b = before[1].lerp(after[1], time);
        Vec3 c = before[2].lerp(after[2], time);
        Vec3 d = before[3].lerp(after[3], time);
        return a.lerp(b, u).lerp(d.lerp(c, u), v);
    }

    private static void addVoxel(Set<Voxel> voxels, Vec3 point) {
        voxels.add(new Voxel(
            (int) Math.floor(point.x * VOXELS_PER_BLOCK),
            (int) Math.floor(point.y * VOXELS_PER_BLOCK),
            (int) Math.floor(point.z * VOXELS_PER_BLOCK)
        ));
    }

    /** Draw only boundary faces. Adjacent voxel faces are the main source of alpha stacking. */
    private static void cubeShell(
        VertexConsumer vertices, Matrix4f matrix, Voxel voxel, Set<Voxel> voxels, int[] color
    ) {
        double scale = 1.0 / VOXELS_PER_BLOCK;
        double x = voxel.x * scale, y = voxel.y * scale, z = voxel.z * scale;
        Vec3[] p = {
            new Vec3(x, y, z), new Vec3(x + scale, y, z),
            new Vec3(x + scale, y + scale, z), new Vec3(x, y + scale, z),
            new Vec3(x, y, z + scale), new Vec3(x + scale, y, z + scale),
            new Vec3(x + scale, y + scale, z + scale), new Vec3(x, y + scale, z + scale)
        };
        if (!voxels.contains(new Voxel(voxel.x - 1, voxel.y, voxel.z))) quad(vertices, matrix, new Vec3[] {p[0], p[3], p[7], p[4]}, color, 72);
        if (!voxels.contains(new Voxel(voxel.x + 1, voxel.y, voxel.z))) quad(vertices, matrix, new Vec3[] {p[1], p[5], p[6], p[2]}, color, 72);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y - 1, voxel.z))) quad(vertices, matrix, new Vec3[] {p[0], p[4], p[5], p[1]}, color, 72);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y + 1, voxel.z))) quad(vertices, matrix, new Vec3[] {p[3], p[2], p[6], p[7]}, color, 72);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z - 1))) quad(vertices, matrix, new Vec3[] {p[0], p[1], p[2], p[3]}, color, 72);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z + 1))) quad(vertices, matrix, new Vec3[] {p[4], p[7], p[6], p[5]}, color, 72);
    }

    private static void bufferCubeShell(
        VertexConsumer vertices, Matrix4f matrix, BlockPos block, Set<BlockPos> occupied,
        Transform transform, int[] color
    ) {
        Vec3[] p = transform(new Vec3[] {
            new Vec3(block.getX(), block.getY(), block.getZ()), new Vec3(block.getX() + 1, block.getY(), block.getZ()),
            new Vec3(block.getX() + 1, block.getY() + 1, block.getZ()), new Vec3(block.getX(), block.getY() + 1, block.getZ()),
            new Vec3(block.getX(), block.getY(), block.getZ() + 1), new Vec3(block.getX() + 1, block.getY(), block.getZ() + 1),
            new Vec3(block.getX() + 1, block.getY() + 1, block.getZ() + 1), new Vec3(block.getX(), block.getY() + 1, block.getZ() + 1)
        }, transform);
        if (!occupied.contains(block.west())) quad(vertices, matrix, new Vec3[] {p[0], p[3], p[7], p[4]}, color, 54);
        if (!occupied.contains(block.east())) quad(vertices, matrix, new Vec3[] {p[1], p[5], p[6], p[2]}, color, 54);
        if (!occupied.contains(block.below())) quad(vertices, matrix, new Vec3[] {p[0], p[4], p[5], p[1]}, color, 54);
        if (!occupied.contains(block.above())) quad(vertices, matrix, new Vec3[] {p[3], p[2], p[6], p[7]}, color, 54);
        if (!occupied.contains(block.north())) quad(vertices, matrix, new Vec3[] {p[0], p[1], p[2], p[3]}, color, 54);
        if (!occupied.contains(block.south())) quad(vertices, matrix, new Vec3[] {p[4], p[7], p[6], p[5]}, color, 54);
    }

    private static void mappedCubeShell(
        VertexConsumer vertices, Matrix4f matrix, Voxel voxel, Set<Voxel> voxels,
        Transform inverse, int[] color
    ) {
        double scale = 1.0 / VOXELS_PER_BLOCK;
        double x = voxel.x * scale, y = voxel.y * scale, z = voxel.z * scale;
        Vec3[] p = transform(new Vec3[] {
            new Vec3(x, y, z), new Vec3(x + scale, y, z), new Vec3(x + scale, y + scale, z), new Vec3(x, y + scale, z),
            new Vec3(x, y, z + scale), new Vec3(x + scale, y, z + scale),
            new Vec3(x + scale, y + scale, z + scale), new Vec3(x, y + scale, z + scale)
        }, inverse);
        if (!voxels.contains(new Voxel(voxel.x - 1, voxel.y, voxel.z))) quad(vertices, matrix, new Vec3[] {p[0], p[3], p[7], p[4]}, color, 62);
        if (!voxels.contains(new Voxel(voxel.x + 1, voxel.y, voxel.z))) quad(vertices, matrix, new Vec3[] {p[1], p[5], p[6], p[2]}, color, 62);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y - 1, voxel.z))) quad(vertices, matrix, new Vec3[] {p[0], p[4], p[5], p[1]}, color, 62);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y + 1, voxel.z))) quad(vertices, matrix, new Vec3[] {p[3], p[2], p[6], p[7]}, color, 62);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z - 1))) quad(vertices, matrix, new Vec3[] {p[0], p[1], p[2], p[3]}, color, 62);
        if (!voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z + 1))) quad(vertices, matrix, new Vec3[] {p[4], p[7], p[6], p[5]}, color, 62);
    }

    /** Two filled triangles are one bridge quad. The internal diagonal is deliberately not outlined. */
    private static void quad(VertexConsumer vertices, Matrix4f matrix, Vec3[] points, int[] color, int alpha) {
        vertex(vertices, matrix, points[0], color, alpha);
        vertex(vertices, matrix, points[1], color, alpha);
        vertex(vertices, matrix, points[2], color, alpha);
        vertex(vertices, matrix, points[0], color, alpha);
        vertex(vertices, matrix, points[2], color, alpha);
        vertex(vertices, matrix, points[3], color, alpha);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Vec3 point, int[] color, int alpha) {
        vertices.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
            .setColor(color[0], color[1], color[2], alpha);
    }

    public static final class RemoteCallables {
        public static void setMode(String value, String ignoredShipId, String encodedShape) {
            mode = Mode.valueOf(value);
            selectedShip = mode == Mode.OFF || ignoredShipId == null || ignoredShipId.isEmpty()
                ? null : UUID.fromString(ignoredShipId);
            // Turning the overlay ON no longer wipes the mesh: the server always follows
            // this call with the chunked shapePart/blocksPart stream, and clearing here
            // meant that any part which failed to land left the overlay showing a
            // partially accumulated -- i.e. visibly truncated -- mesh with nothing to
            // fall back on. Only OFF clears. The incoming buffers are still reset so a
            // half-received stream from a previous session cannot prefix the new one.
            if (mode == Mode.OFF) {
                shape = List.of();
                blocks = List.of();
            }
            shapeShip = selectedShip;
            INCOMING_SHAPE.setLength(0);
            INCOMING_BLOCKS.setLength(0);
            volumes = Map.of();
            volumeExpiresAt = 0L;
        }

        /** Replaces the selected ship's occupied-cube exterior after an edit. */
        public static void shape(String encodedShape) {
            shape = decodeShape(encodedShape);
            shapeShip = selectedShip;
        }

        /** Appends one packet-safe part of server's selected-ship exterior mesh. */
        public static void shapePart(String piece, boolean last) {
            if (!INCOMING_SHAPE.isEmpty() && !piece.isEmpty()) INCOMING_SHAPE.append(';');
            INCOMING_SHAPE.append(piece);
            if (!last) return;
            shape = decodeShape(INCOMING_SHAPE.toString());
            shapeShip = selectedShip;
            INCOMING_SHAPE.setLength(0);
        }

        /** Appends packet-safe occupied block records for the 1x1 retained-pose buffer. */
        public static void blocksPart(String piece, boolean last) {
            if (!INCOMING_BLOCKS.isEmpty() && !piece.isEmpty()) INCOMING_BLOCKS.append(';');
            INCOMING_BLOCKS.append(piece);
            if (!last) return;
            blocks = decodeBlocks(INCOMING_BLOCKS.toString());
            INCOMING_BLOCKS.setLength(0);
        }

        /** `ship,phase,dimension,previous/current~hit~direction~portal~nether~mapping~buffer`, pipe-separated. */
        public static void volumes(String payload) {
            if (payload == null || payload.isEmpty()) {
                // A portal pass may receive the clear before the next server-tick payload.
                // Keep the exact prior A/B shell briefly instead of flashing it for one frame.
                return;
            }
            try {
                Map<UUID, Volume> next = new java.util.HashMap<>();
                for (String entry : payload.split("\\|")) {
                    String[] fields = entry.split(",", 4);
                    String[] detail = fields[3].split("~", 7);
                    String[] transforms = detail[0].split("/", 2);
                    UUID id = UUID.fromString(fields[0]);
                    Volume target = new Volume(ResourceLocation.parse(fields[2]), Phase.valueOf(fields[1]),
                        decodeTransform(transforms[0]), decodeTransform(transforms[1]),
                        detail.length > 1 && "1".equals(detail[1]),
                        detail.length > 2 && "1".equals(detail[2]),
                        detail.length > 3 && !detail[3].isEmpty() ? decodePortal(detail[3]) : null,
                        detail.length > 4 && "1".equals(detail[4]),
                        detail.length > 5 && !detail[5].isEmpty() ? decodeTransform(detail[5]) : null,
                        detail.length > 6 && !detail[6].isEmpty() ? decodeTransform(detail[6]) : null);
                    // The debug geometry must be the detector's exact server-tick A->B sweep.
                    // Interpolating endpoint transforms turns it into a different, invented segment.
                    next.put(id, target);
                }
                volumes = Map.copyOf(next);
                volumeExpiresAt = System.nanoTime() + 250_000_000L;
            } catch (RuntimeException ignored) {}
        }
    }

    private static List<Face> decodeShape(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        try {
            List<Face> faces = new ArrayList<>();
            for (String encodedFace : encoded.split(";")) {
                String[] encodedVertices = encodedFace.split("\\|");
                if (encodedVertices.length < 3) continue;
                Vec3[] vertices = new Vec3[encodedVertices.length];
                for (int i = 0; i < vertices.length; i++) vertices[i] = decodePoint(encodedVertices[i]);
                faces.add(new Face(vertices));
            }
            return List.copyOf(faces);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<BlockPos> decodeBlocks(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        try {
            List<BlockPos> blocks = new ArrayList<>();
            for (String entry : encoded.split(";")) {
                String[] xyz = entry.split(",");
                blocks.add(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
            }
            return List.copyOf(blocks);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static Transform decodeTransform(String encoded) {
        String[] points = encoded.split(";");
        Vec3 origin = decodePoint(points[0]);
        return new Transform(origin, decodePoint(points[1]).subtract(origin),
            decodePoint(points[2]).subtract(origin), decodePoint(points[3]).subtract(origin));
    }

    private static PortalOverlay decodePortal(String encoded) {
        String[] fields = encoded.split(";");
        return new PortalOverlay(decodePoint(fields[0]), decodePoint(fields[1]), decodePoint(fields[2]),
            decodePoint(fields[3]), Double.parseDouble(fields[4]), Double.parseDouble(fields[5]));
    }

    private static Vec3 decodePoint(String encoded) {
        String[] values = encoded.split(",");
        return new Vec3(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]));
    }

    private record Face(Vec3[] vertices) {}
    private record Voxel(int x, int y, int z) {}
    private record Transform(Vec3 origin, Vec3 x, Vec3 y, Vec3 z) {}
    private record PortalOverlay(Vec3 origin, Vec3 axisW, Vec3 axisH, Vec3 normal, double width, double height) {}
    private record Volume(ResourceLocation dimension, Phase phase, Transform before, Transform after,
                          boolean apertureHit, boolean towardDestination, PortalOverlay portal,
                          boolean netherPortal, Transform mapping, Transform buffer) {}
}
