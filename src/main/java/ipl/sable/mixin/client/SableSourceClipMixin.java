package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.render.IplProgramRegistry;
import ipl.sable.render.SourceClipDiag;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Phase 3a: source-side render clipping for Sable sub-levels straddling a portal.
 *
 * <p>When an airship is mid-crossing a portal, its world-space geometry extends past
 * the portal frame on the destination side. Without intervention the source-dim
 * render draws the whole airship -- the player sees a complete airship that appears
 * to clip through the portal frame instead of cleanly straddling it. The mirror in
 * the dest dim (rendered through the portal-through view) covers the dest side, but
 * the source side over-renders.
 *
 * <p>This mixin installs IP's {@link FrontClipping} clip plane around Sable's
 * {@code renderChunkedSubLevel} so geometry past the portal plane is discarded.
 * The plane's normal points to the source (camera) side: points where
 * {@code normal · p + c > 0} are kept. IP's
 * {@code MixinShaderInstance + ShaderCodeTransformation} already adds the
 * {@code iportal_ClippingEquation} uniform to the {@code rendertype_solid /
 * rendertype_cutout} shaders Sable uses for chunked rendering, so the clip equation
 * we install propagates into the actual fragment discard.
 *
 * <p>Slot 1 remains active for the mapped projection as well as the normal source
 * draw. IP slot 0 masks the portal aperture; this independent plane cuts the
 * sub-level's own source/destination split.
 *
 * <p>{@code @Pseudo} because Sable's class is a runtime dep, not compile-time.
 * {@code remap = false} because Sable's identifiers aren't part of the MC mappings.
 */
@Pseudo
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class SableSourceClipMixin {

    @Shadow(remap = false)
    public abstract ClientSubLevel getSubLevel();

    /**
     * Was FrontClipping's clip state enabled when we entered? If yes, IP is using it
     * for something (the entity render path enables clipping briefly even outside
     * portal-through render in some compat paths); we leave well enough alone. If
     * no, we owned the slot, so we tear our config down on RETURN.
     */
    @Unique
    private boolean ipl$installedClipThisCall;

    /** IP slot 0 was temporarily converted to Sable's plot-local input space. */
    @Unique
    private double[] ipl$portalClipEquationThisCall;

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void ipl$installClipPlaneIfStraddling(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        this.ipl$installedClipThisCall = false;
        this.ipl$portalClipEquationThisCall = null;

        // Sable's vanilla chunk vertices are plot-local until its model-view
        // matrix rotates them. IP's slot-0 equation is camera-relative world
        // space, so using it unchanged produces a rotated portal cut whenever
        // the airship is rotated. Convert only for this scoped Sable draw, then
        // restore it before the parent world's terrain uses the same shader.
        if (FrontClipping.isClippingEnabled
            && IplProgramRegistry.isVanillaSubLevelInputShader(shader.getName())) {
            double[] portalEquation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
            if (SubLevelClipUniformPatcher.patchPortalClipForVanillaSubLevel(
                shader, getSubLevel(), portalEquation
            )) {
                this.ipl$portalClipEquationThisCall = portalEquation;
            }
        }

        // The projection-driver branch supplies its own single plane; the source
        // branch may carry SEVERAL (multi-straddle) — the shader min()s two cuts.
        qouteall.q_misc_util.my_util.Plane primaryPlane;
        qouteall.q_misc_util.my_util.Plane secondaryPlane = null;
        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(getSubLevel());
        if (decision == null) {
            SourceClipDiag.onVanillaCall(false);
            return;
        }
        primaryPlane = decision.plane();
        if (ipl.sable.client.IplStraddleRenderState.getPlaneFor(getSubLevel()) == null) {
            java.util.List<SourceClipPortalFinder.ClipDecision> all =
                SourceClipPortalFinder.findStraddlingPortalPlanes(getSubLevel());
            if (all.size() > 1) {
                secondaryPlane = all.get(1).plane();
            }
        }

        // Independent clip planes: write to our ipl_subLevelClipEquation[2] uniform
        // (both min into gl_ClipDistance[1]), not IP's slot 0.
        SubLevelClipUniformPatcher.patchForSubLevel(getSubLevel(), primaryPlane, secondaryPlane);

        // Enable hardware respect for gl_ClipDistance[1] writes. GL_CLIP_DISTANCE1
        // is 0x3001 in modern GL; some IDEs / compat shims expose it as
        // GL30.GL_CLIP_DISTANCE1 or GL11.GL_CLIP_PLANE1 (same enum value).
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        // The second cut now owns gl_ClipDistance[2] instead of being min()'d
        // into slot 1. min() of two linear functions is only piecewise-linear,
        // and the rasterizer interpolates each clip distance linearly across a
        // primitive, so the concave crease between the two planes was replaced
        // by a straight chord -- the small triangular bend the mesh showed
        // right next to the portal plane. Two independent slots reproduce the
        // exact intersection of both half-spaces.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE2);

        this.ipl$installedClipThisCall = true;
        SourceClipDiag.onVanillaCall(true);
    }

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("RETURN"),
        remap = false,
        require = 0
    )
    private void ipl$teardownClipPlane(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        if (this.ipl$portalClipEquationThisCall != null) {
            SubLevelClipUniformPatcher.restorePortalClip(shader, this.ipl$portalClipEquationThisCall);
            this.ipl$portalClipEquationThisCall = null;
        }

        if (!this.ipl$installedClipThisCall) return;
        this.ipl$installedClipThisCall = false;

        // Always disable GL_CLIP_DISTANCE1 on bracket exit -- it's OUR slot,
        // not IP's (IP uses CD0 for portal clip). The previous conditional
        // "leave on if FrontClipping.isClippingEnabled" was a confusion
        // between slot-0 and slot-1 enable states: FrontClipping is IP's
        // CD0 plane, and tying our CD1 disable to it caused a regression
        // where stale sub-level equations stayed live on programs bound
        // during portal-through (notably iris_gbuffers_terrain rendering
        // the nether terrain, which inherited the airship plane and
        // visibly clipped on it).
        //
        // With CD1 unconditionally disabled on bracket exit, the bracket's
        // enable is the SOLE source of "slot-1 active" GL state, and the
        // IplGlUseProgramProbeMixin per-bind re-enable is gated on
        // inSubLevelBracket so it only re-enables while a bracket is live.
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        SubLevelClipUniformPatcher.clearAndUpload();
    }

}
