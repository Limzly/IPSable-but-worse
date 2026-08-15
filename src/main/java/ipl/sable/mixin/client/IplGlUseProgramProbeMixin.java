package ipl.sable.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import ipl.sable.render.IplClipEquationCache;
import ipl.sable.render.IplProgramRegistry;
import ipl.sable.render.IplSubLevelUniformRegistry;
import ipl.sable.render.SubLevelClipUniformPatcher;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Writes IP's slot-0 clip equation onto every GL program at bind time -- the
 * actual fix for the chest-leak bug.
 *
 * <p><b>Why this exists:</b> IP's
 * {@code MixinRenderSystem_Clipping.onSetShader} hook writes
 * {@code iportal_ClippingEquation} only when a shader is bound through Mojang's
 * {@code RenderSystem.setShader(Supplier)} entry point. Under Iris + a shader
 * pack, the entity / block-entity batch is dispatched through Iris's
 * {@code ProgramManager} which calls {@link GlStateManager#_glUseProgram(int)}
 * directly without ever touching {@code setShader}. The
 * {@code iportal_ClippingEquation} uniform IS present on the linked Iris-style
 * {@code entities_*} programs (verified by glGetActiveUniform in
 * {@code IplShaderUniformProbeMixin}) -- but nothing writes to it, so it stays
 * at its default zero. The shader-side clip test
 * {@code dot(_ipl_worldPos, eq.xyz) + eq.w = 0} always passes, and chests /
 * banners / mob renderers visibly leak through portals.
 *
 * <p><b>How the fix works:</b> on every {@code _glUseProgram(programId)} call
 * while {@link FrontClipping#isClippingEnabled} is true, we look up the program's
 * {@code iportal_ClippingEquation} location (cached after first lookup) and
 * write the current camera-relative-world equation directly to it via
 * {@code glProgramUniform4f}. {@code glProgramUniform} writes to a specified
 * program regardless of whether it's the currently-bound program, so we don't
 * have to care about bind ordering -- the value is in place by the time any
 * draw call uses this program.
 *
 * <p>We use the <b>world-space</b> (camera-relative) equation
 * ({@code activeClipPlaneEquationBeforeModelView}) because the Iris-rewritten
 * {@code entities_*} / {@code terrain_*} shaders reconstruct world position via
 * {@code gbufferModelViewInverse * iris_ModelViewMat * (iris_Position + iris_ChunkOffset)}
 * (see the iris+sodium entry in {@code shader_transformation.yaml}) -- they do
 * the dot in camera-relative world space, which matches this equation form.
 *
 * <p><b>What we don't do:</b> we deliberately do NOT also write
 * {@code ipl_subLevelClipEquation} here. That uniform is owned by Sable's
 * sub-level rendering pipeline ({@link IplShaderClipMirrorMixin} +
 * {@code SubLevelClipUniformPatcher}) and writing it from a generic
 * {@code _glUseProgram} hook would conflict with the per-sub-level equation
 * the sub-level mixins install. Slot 1 stays under the existing mirror's
 * exclusive control.
 *
 * <p><b>Why no reset when clipping turns off:</b>
 * {@link FrontClipping#disableClipping()} calls
 * {@code GL11.glDisable(GL_CLIP_PLANE0)}, so even though stale equation values
 * may linger on programs across portal-through brackets, the rasterizer
 * ignores {@code gl_ClipDistance[0]} writes when clip-distance 0 is disabled.
 * The next portal-through frame overwrites with a fresh equation. So we never
 * need to write a "reset" equation -- saves a bind-time GL call when not
 * inside a portal bracket.
 */
@Mixin(value = GlStateManager.class, remap = false)
public class IplGlUseProgramProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-glUseProgram-probe");

    /**
     * Cache of resolved uniform locations per program id, so we only call
     * {@code glGetUniformLocation} once per program over the session. -1 in
     * either slot means "not present on this program" -- we still cache the
     * negative result to avoid re-querying.
     */
    @Unique
    private static final ConcurrentMap<Integer, int[]> IPL$LOC_CACHE = new ConcurrentHashMap<>();

    @Unique
    private static final ConcurrentMap<Integer, Boolean> IPL$LOGGED_PROGRAMS = new ConcurrentHashMap<>();

    @Inject(
        method = "_glUseProgram(I)V",
        at = @At("HEAD")
    )
    private static void ipl$writeClipEquationToProgram(int program, CallbackInfo ci) {
        if (program == 0) {
            return;
        }

        // Cache + log on first encounter REGARDLESS of clipping state, so the
        // log shows every program that carries iportal_ClippingEquation -- even
        // ones bound only when clipping is disabled. That's how we can see if
        // the chest's entity program is being bound at all (and if so, in what
        // clipping state). Without this, programs first bound outside
        // portal-through never appear in the log and we wrongly conclude they
        // aren't bound at all.
        int[] locs = IPL$LOC_CACHE.get(program);
        if (locs == null) {
            int iportalLoc = GL20.glGetUniformLocation(program, "iportal_ClippingEquation");
            int subLevelLoc = GL20.glGetUniformLocation(program, "ipl_subLevelClipEquation");
            // Element [1] of the array uniform (multi-straddle second cut). Base-name
            // lookups resolve element [0]; the second element has its own location.
            int subLevelLoc2 = GL20.glGetUniformLocation(program, "ipl_subLevelClipEquation[1]");
            locs = new int[]{iportalLoc, subLevelLoc, subLevelLoc2};
            IPL$LOC_CACHE.put(program, locs);

            // Register slot-1 carriers so SubLevelClipUniformPatcher.patchForSubLevel
            // can spray the equation to all known programs at bracket entry,
            // not just whatever shader is bound at that moment. Required for
            // programs (like the Iris-rewritten moving_block cog shader) that
            // were bound BEFORE the bracket started -- those never re-trigger
            // _glUseProgram during the bracket so this mixin's per-bind write
            // path doesn't reach them.
            IplSubLevelUniformRegistry.register(program, subLevelLoc, subLevelLoc2);

            // Only log programs that *could* be affected by IP's clip plane --
            // skip GUI / blit / etc. shaders that don't carry the uniform.
            if (iportalLoc >= 0 && IPL$LOGGED_PROGRAMS.putIfAbsent(program, Boolean.TRUE) == null) {
                IPL$LOG.info(
                    "[IPL-GLUSE-WRITE] programId={} iportalLoc={} subLevelLoc={} clippingEnabledOnFirstBind={}",
                    program,
                    iportalLoc,
                    subLevelLoc,
                    FrontClipping.isClippingEnabled
                );
            }
        }

        // Determine if we should write the equation + re-enable the clip
        // plane. Three cases qualify:
        //   1. IP's bracket is currently active (isClippingEnabled = true).
        //      We refresh the cache from IP's live equations -- this is the
        //      normal path for terrain and any draw inside IP's bracket.
        //   2. We've left IP's bracket but PortalRendering.isRendering() is
        //      still true -- this is the Iris-gbuffer-after-bracket case
        //      where the chest's entity shader binds. Use the cached
        //      equation from when (1) was last true.
        //   3. We're in a Sable sub-level BE bracket (currentSubLevelEq != null).
        //      This fires in main-render-of-source-dim (looking AT a portal,
        //      not through it) when SableSubLevelBlockEntityClipMixin wraps a
        //      sub-level's BE render. patchForSubLevel only uploads slot-1 to
        //      the shader bound at bracket entry (terrain_*); cogs and other
        //      BE sub-meshes that rebind to rendertype_cutout_mipped et al
        //      need this hook to fire so their slot-1 uniform also gets the
        //      sub-level equation. RenderDoc capture confirmed slot-1 stayed
        //      at the (0,0,0,1) init for cog draws without this branch -- so
        //      `dot(pos,0)+1 = 1 > 0` always passed and cogs leaked through.
        // Outside all three cases, leave the program's uniform alone (stale
        // value is harmless because we explicitly disable the plane below).
        boolean haveActive = FrontClipping.isClippingEnabled;
        boolean inPortalRender = PortalRendering.isRendering();
        boolean inSubLevelBracket = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld() != null;
        if (haveActive) {
            IplClipEquationCache.refreshFromActive();
        } else if (!inPortalRender && !inSubLevelBracket) {
            return;
        }

        // Defensive re-enable: RenderDoc revealed that Veil's bloom pass
        // (and likely other Veil post-effects) calls glDisable(GL_CLIP_DISTANCE0)
        // mid-portal-through and never restores it. Any draw that happens
        // after that and before IP's bracket ends -- in particular, the
        // block-entity batch for chests, banners, etc. -- runs with clip
        // distance 0 disabled, so gl_ClipDistance[0] writes from the vertex
        // shader are silently ignored. We re-assert the enable on every
        // program bind during portal-through so the clip plane is always
        // active by the time any draw call follows.
        //
        // GL_CLIP_PLANE0 == GL_CLIP_DISTANCE0 (both are 0x3000) so this matches
        // what FrontClipping.enableClipping does.
        if (haveActive || inPortalRender) {
            GL11.glEnable(GL11.GL_CLIP_PLANE0);
        }

        // Slot 1 (GL_CLIP_DISTANCE1) MUST only be enabled when a sub-level
        // bracket is active. Previously we re-enabled it on every bind that
        // had a slot-1 uniform, which leaked CD1 enables into the
        // portal-through nether-terrain render: those programs share
        // iris_gbuffers_terrain with sub-level draws, so they have slot-1,
        // and CD1 stayed on with a stale sub-level equation -> nether
        // terrain visibly clipped on the airship plane (cog_leak attempt
        // 8 regression). Gating on inSubLevelBracket keeps CD1 enabled
        // only while a bracket is live; outside the bracket CD1 is off
        // and slot-1 writes are rasterizer-ignored regardless of what's
        // in the uniform.
        if (locs[1] >= 0 && inSubLevelBracket) {
            GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
            // Slot 2 carries the SECOND sub-level cut. It used to be folded
            // into slot 1 via min() in the vertex shader, which the rasterizer
            // then interpolated linearly -- turning the concave crease between
            // the two planes into a straight chord and producing the small
            // triangular bend right at the portal plane. Each cut now owns its
            // own clip distance, so slot 2 must be enabled alongside slot 1.
            if (locs.length > 2 && locs[2] >= 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE2);
        }

        // Pick the equation form that matches the GLSL injection for this
        // program's shader. Vanilla entity / particle / portal_area shaders
        // do `dot((ModelViewMat * vec4(Position,1)).xyz, eq)` -- eye space --
        // so they need activeClipPlaneAfterModelView. Everything else
        // (vanilla terrain rendertype_*, Iris-rewritten terrain_* / entities_*)
        // does the dot in camera-relative world space and needs
        // activeClipPlaneEquationBeforeModelView. The shader name -> isEntity
        // mapping is populated by IplShaderUniformProbeMixin at
        // ShaderInstance.updateLocations() RETURN.
        //
        // Unknown programs default to world-space (false), which is the
        // safe choice: vanilla terrain dominates portal-through scenes, and
        // Iris-rewritten shaders -- which are world-space -- can land before
        // our probe runs if Iris's compile thread races ahead.
        boolean entityStyle = IplProgramRegistry.isEntityStyleProgram(program);
        double[] eq = entityStyle
            ? (haveActive ? FrontClipping.getActiveClipPlaneEquationAfterModelView()
                          : IplClipEquationCache.getEyeEq())
            : (haveActive ? FrontClipping.getActiveClipPlaneEquationBeforeModelView()
                          : IplClipEquationCache.getWorldEq());

        // Slot 0 (portal clip): write only if the program carries the uniform
        // AND we have an equation for it (we're in a portal context). In the
        // sub-level-bracket-only case, eq is typically null (no portal context
        // ever active this frame) -- skip the slot-0 write; the cached value
        // on the program from the last portal-through is harmless because
        // gl_ClipDistance[0] writes are ignored by the rasterizer when
        // CLIP_DISTANCE0 is disabled.
        //
        // glProgramUniform4f (GL 4.1+) writes to a specified program without
        // requiring it to be the currently bound program -- ideal for this
        // hook which fires at HEAD of _glUseProgram, before the bind actually
        // happens. Caller's GPU is GL 4.6 per earlier probe.
        int iportalLoc = locs[0];
        if (iportalLoc >= 0 && eq != null) {
            GL41.glProgramUniform4f(
                program, iportalLoc,
                (float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]
            );
        }

        // Slot 1 (sub-level clip): write ipl_subLevelClipEquation on programs
        // that carry it. Priority order:
        //
        //   1. Active sub-level bracket -- use the sub-level's own equation
        //      published by SubLevelClipUniformPatcher.patchForSubLevel.
        //      This is the case for cogs / animated sub-models / particles
        //      rendered as part of an airship's block entities, where the
        //      sub-level's clip plane (mirror-flip aware) is what should
        //      drive slot-1, NOT IP's portal plane.
        //   2. Otherwise -- mirror IP's portal-clip equation onto slot-1, so
        //      any leftover shader bound during portal-through gets clipped
        //      on both slots (the original IplShaderClipMirrorMixin
        //      behavior, but at program-bind time and with the broader
        //      PortalRendering.isRendering() fallback).
        //
        // The sub-level path matters for the "floating cog/lever sub-model on
        // an airship straddling a portal" case: the airship body clips
        // correctly via slot-1 driven by the chunked-terrain render, but
        // sub-component BE animation draws bind a different shader inside
        // SableSubLevelBlockEntityClipMixin's bracket. Without preferring
        // the sub-level equation here we'd overwrite it with the portal
        // mirror, leaving the sub-component clipped against the WRONG plane
        // (the portal plane instead of the sub-level plane).
        int subLevelLoc = locs[1];
        if (subLevelLoc >= 0) {
            // Sable terrain uses world/input space. A vanilla BE's PoseStack is
            // multiplied into ModelViewMat before the shader evaluates Position,
            // so entity-style programs evaluate the clip distance in eye space.
            float[] subEq = IplProgramRegistry.usesVanillaSubLevelInputSpace(program)
                ? SubLevelClipUniformPatcher.getCurrentSubLevelEqVanillaInput()
                : entityStyle ? SubLevelClipUniformPatcher.getCurrentSubLevelEqEye()
                : SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld();
            if (subEq == null && entityStyle) {
                subEq = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld();
            }
            if (subEq != null) {
                GL41.glProgramUniform4f(
                    program, subLevelLoc,
                    subEq[0], subEq[1], subEq[2], subEq[3]
                );
            }
            // Second cut (multi-straddle), same space selection; neutral when absent.
            int subLevelLoc2 = locs.length > 2 ? locs[2] : -1;
            if (subLevelLoc2 >= 0) {
                float[] subEq2 = IplProgramRegistry.usesVanillaSubLevelInputSpace(program)
                    ? SubLevelClipUniformPatcher.getCurrentSubLevelEqVanillaInput2()
                    : entityStyle ? SubLevelClipUniformPatcher.getCurrentSubLevelEqEye2()
                    : SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld2();
                if (subEq2 == null) {
                    GL41.glProgramUniform4f(program, subLevelLoc2, 0f, 0f, 0f, 1f);
                } else {
                    GL41.glProgramUniform4f(
                        program, subLevelLoc2,
                        subEq2[0], subEq2[1], subEq2[2], subEq2[3]
                    );
                }
            }
        }
    }
}
