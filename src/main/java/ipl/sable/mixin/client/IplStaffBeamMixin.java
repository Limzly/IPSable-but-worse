package ipl.sable.mixin.client;

import ipl.sable.client.IplStaffBeamRoutes;
import ipl.sable.client.IplStaffPortalBeamRenderer;
import net.createmod.catnip.outliner.LineOutline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Draw-only half of Simulated's noisy-node beam renderer. */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam",
    remap = false
)
public abstract class IplStaffBeamMixin {

    private static volatile java.lang.reflect.Field ipl$previousPosition;
    private static volatile java.lang.reflect.Field ipl$position;

    @Shadow(remap = false) private LineOutline line;
    @Shadow(remap = false) private List<?> nodes;
    @Shadow(remap = false) private double currentNodeRadius;
    @Shadow(remap = false) private double length;

    /**
     * Node density comes from {@code length} inside {@code update()}, which runs every
     * client tick — not only when a segment was drawn. Stock seeds and maintains it from
     * raw endpoint subtraction, which is garbage across frames (a cross-dimension grab
     * produced a wrong node count: right start, right end, wrong beam). Feed the TRUE
     * physical route length (published by the route builder, seeded from the pick ray at
     * creation) before every node-count update. NaN means no route data — plain local
     * beams keep stock behavior.
     */
    @Inject(method = "update", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$feedTrueLength(CallbackInfo ci) {
        double known = IplStaffBeamRoutes.knownLengthFor(this);
        if (!Double.isNaN(known)) {
            this.length = known;
        }
    }

    /**
     * @author IPL-Sable
     * @reason Render one CONTINUOUS polyline in the actual IP pass frame.
     *
     * <p><b>What was wrong.</b> The beam used to be drawn as a set of independent
     * per-world segments. Each segment re-derived its own node walk from the global
     * fraction range it happened to cover, and it explicitly SKIPPED every node whose
     * fraction fell outside that range. The two nodes closest to an aperture were
     * therefore always dropped, and the piece was closed with a straight chord to the
     * aperture point. Two independent chords met at the portal with unrelated noise
     * phase, which is the visible kink: the beam looked like several beams that happen
     * to touch, and the join point visibly jumped as the fraction boundaries moved.
     *
     * <p><b>What it does now.</b> {@link IplStaffBeamRoutes#runs} samples the WHOLE route
     * once in global arc length and emits a vertex at every sample and at every aperture,
     * so a run arrives as a finished list of points that only has to be stroked. The
     * closing vertex of one run and the opening vertex of the next are the same physical
     * point on opposite portal faces and carry the SAME fraction, so the noise offset
     * matches in magnitude and differs only by the portal rotation the geometry itself
     * received. There is no chord, no skipped node and no phase reset: continuity is a
     * property of the construction, not of a tolerance.
     */
    @Overwrite(remap = false)
    private void render(
        Vec3 source, Vec3 target,
        com.mojang.blaze3d.vertex.PoseStack stack,
        SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    ) {
        if (!IplStaffPortalBeamRenderer.isPhysicalBeamPass()) return;
        IplStaffBeamRoutes.Run run = IplStaffPortalBeamRenderer.getActiveRun();
        if (run == null) return;

        // update() derives node count and noise radius from this.length; keep it fed with
        // the true physical beam length on the render path too.
        this.length = run.totalLength();

        List<IplStaffBeamRoutes.Vertex> vertices = run.vertices();
        if (this.nodes.size() < 2 || vertices.size() < 2 || run.totalLength() <= 1.0e-9) {
            this.line.set(source, target).render(stack, buffer, camera, partialTick);
            return;
        }

        Vec3 previous = ipl$displace(vertices.get(0), partialTick);
        for (int i = 1; i < vertices.size(); i++) {
            Vec3 point = ipl$displace(vertices.get(i), partialTick);
            this.line.set(previous, point).render(stack, buffer, camera, partialTick);
            previous = point;
        }
    }

    /**
     * A vertex plus its noise offset. The offset is sampled by GLOBAL fraction, so the
     * same physical point sampled from either side of a portal gets the same offset, and
     * it is rotated by the folded portal chain so it lands in this run's frame.
     */
    private Vec3 ipl$displace(IplStaffBeamRoutes.Vertex vertex, float partialTick) {
        return vertex.point().add(IplStaffBeamRoutes.rotate(
            vertex.noiseRotation(), ipl$noiseAt(vertex.fraction(), partialTick)));
    }

    private Vec3 ipl$noiseAt(double fraction, float partialTick) {
        double scaled = Math.clamp(fraction, 0.0, 1.0) * this.nodes.size();
        int left = (int) Math.floor(scaled);
        int right = Math.min(this.nodes.size(), left + 1);
        Vec3 a = ipl$nodeOffset(left, partialTick);
        Vec3 b = ipl$nodeOffset(right, partialTick);
        return a.lerp(b, scaled - left).scale(this.currentNodeRadius);
    }

    private Vec3 ipl$nodeOffset(int index, float partialTick) {
        if (index <= 0 || index >= this.nodes.size()) return Vec3.ZERO;
        Object node = this.nodes.get(index);
        return ipl$nodePosition(node, "previousPosition").lerp(
            ipl$nodePosition(node, "position"), partialTick);
    }

    private static Vec3 ipl$nodePosition(Object node, String fieldName) {
        try {
            java.lang.reflect.Field field = fieldName.equals("previousPosition")
                ? ipl$previousPosition : ipl$position;
            if (field == null) {
                field = node.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                if (fieldName.equals("previousPosition")) {
                    ipl$previousPosition = field;
                } else {
                    ipl$position = field;
                }
            }
            return (Vec3) field.get(node);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Simulated PhysicsBeam node layout changed", exception);
        }
    }

}
