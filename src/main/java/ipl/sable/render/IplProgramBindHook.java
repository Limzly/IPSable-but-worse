package ipl.sable.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared GL-program-bind hook logic.
 *
 * <p>Called from multiple mixin sites because Iris 1.8 binds programs through
 * two distinct chokepoints:
 *
 * <ul>
 *   <li>{@link GlStateManager#_glUseProgram(int)} — vanilla MC's cached
 *       state-manager wrapper. Some Iris paths and Mojang's own setShader
 *       fast path go through it.</li>
 *   <li>{@code com.mojang.blaze3d.shaders.ProgramManager.glUseProgram(int)} —
 *       vanilla MC's <em>uncached</em> helper that {@code ShaderInstance.apply()}
 *       (and Iris's {@code ExtendedShader.apply()},
 *       {@code Program.use()/unbind()}) all funnel through. This path does
 *       NOT touch {@code GlStateManager._glUseProgram}, so a mixin on the
 *       latter alone misses every Iris shaderpack-rewritten program.</li>
 * </ul>
 *
 * <p>Empirical proof (cog_leak8 capture analysis): Iris's
 * {@code ExtendedShader} renders the leaking cog through a program that
 * never appeared in our previous-only {@code _glUseProgram}-hooked
 * registry. The bind goes through {@code ProgramManager.glUseProgram}.
 * Hooking both chokepoints (and calling this helper from both) covers
 * the cog-leaking program plus everything Mojang dispatches the
 * traditional way.
 *
 * <p>Idempotency: {@link #onBind(int)} is safe to call multiple times for
 * the same bind. {@link #IPL$LOC_CACHE} dedupes uniform-location lookups
 * and {@link IplSubLevelUniformRegistry} dedupes registry inserts;
 * uniform writes via {@code glProgramUniform4f} are idempotent (same value
 * writes a no-op). So if both mixins fire for one logical bind, we just
 * do the work twice — no correctness hazard.
 */
public final class IplProgramBindHook {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-program-bind");

    /**
     * Cache of resolved uniform locations per program id, so we only call
     * {@code glGetUniformLocation} once per program over the session. -1 in
     * either slot means "not present on this program" — we still cache the
     * negative result to avoid re-querying.
     */
    public static final ConcurrentMap<Integer, int[]> IPL$LOC_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Integer, Boolean> IPL$LOGGED_PROGRAMS = new ConcurrentHashMap<>();

    private IplProgramBindHook() {}

    /**
     * Called by any program-bind chokepoint hook. {@code program} is the GL
     * program name being bound (about to be bound at HEAD, just-bound at
     * RETURN — we don't depend on the actual GL binding having taken effect
     * because all uniform writes use {@code glProgramUniform4f} which targets
     * a specific program regardless of current bind).
     */
    public static void onBind(int program) {
        if (program == 0) {
            return;
        }

        // Cache + log on first encounter REGARDLESS of clipping state, so the
        // log shows every program that carries iportal_ClippingEquation -- even
        // ones bound only when clipping is disabled.
        int[] locs = IPL$LOC_CACHE.get(program);
        if (locs == null) {
            int iportalLoc = GL20.glGetUniformLocation(program, "iportal_ClippingEquation");
            int subLevelLoc = GL20.glGetUniformLocation(program, "ipl_subLevelClipEquation");
            int subLevelLoc2 = GL20.glGetUniformLocation(program, "ipl_subLevelClipEquation[1]");
            locs = new int[]{iportalLoc, subLevelLoc, subLevelLoc2};
            IPL$LOC_CACHE.put(program, locs);

            // Register slot-1 carriers so SubLevelClipUniformPatcher.patchForSubLevel
            // can spray the equation to all known programs at bracket entry,
            // not just whatever shader is bound at that moment.
            IplSubLevelUniformRegistry.register(program, subLevelLoc, subLevelLoc2);

            if (iportalLoc >= 0 && IPL$LOGGED_PROGRAMS.putIfAbsent(program, Boolean.TRUE) == null) {
                LOG.info(
                    "[IPL-GLUSE-WRITE] programId={} iportalLoc={} subLevelLoc={} clippingEnabledOnFirstBind={}",
                    program, iportalLoc, subLevelLoc, FrontClipping.isClippingEnabled
                );
            }
        }

        boolean haveActive = FrontClipping.isClippingEnabled;
        boolean inPortalRender = PortalRendering.isRendering();
        boolean inSubLevelBracket = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld() != null;
        if (haveActive) {
            IplClipEquationCache.refreshFromActive();
        } else if (!inPortalRender && !inSubLevelBracket) {
            return;
        }

        // Defensive re-enable of CLIP_PLANE0 (== CLIP_DISTANCE0): Veil bloom
        // disables it mid-portal-through and never restores it.
        if (haveActive || inPortalRender) {
            GL11.glEnable(GL11.GL_CLIP_PLANE0);
        }

        // CLIP_DISTANCE1 only re-enabled when a sub-level bracket is active.
        // Outside brackets, CD1 stays off and slot-1 writes are
        // rasterizer-ignored even if uniform values are stale.
        if (locs[1] >= 0 && inSubLevelBracket) {
            GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
            // Slot 2 = second sub-level cut (was folded into slot 1 via min(),
            // which the rasterizer interpolated linearly and bent into a
            // triangular wedge at the portal plane). Enable it with slot 1.
            if (locs.length > 2 && locs[2] >= 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE2);
        }

        boolean entityStyle = IplProgramRegistry.isEntityStyleProgram(program);
        double[] eq = entityStyle
            ? (haveActive ? FrontClipping.getActiveClipPlaneEquationAfterModelView()
                          : IplClipEquationCache.getEyeEq())
            : (haveActive ? FrontClipping.getActiveClipPlaneEquationBeforeModelView()
                          : IplClipEquationCache.getWorldEq());

        // Slot 0 (portal clip): write only if we have an equation (portal
        // context active).
        int iportalLoc = locs[0];
        if (iportalLoc >= 0 && eq != null) {
            GL41.glProgramUniform4f(
                program, iportalLoc,
                (float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]
            );
        }

        // Slot 1 (sub-level clip):
        //   Entity-style programs -> eye-space equation
        //   Non-entity programs   -> camera-relative world equation
        // Sable applies the BE pose before these entity shaders evaluate their
        // vertices, so their slot-1 dot uses the same eye-space convention as
        // IP slot 0.
        int subLevelLoc = locs[1];
        if (subLevelLoc >= 0) {
            float[] subEq;
            if (IplProgramRegistry.usesVanillaSubLevelInputSpace(program)) {
                subEq = SubLevelClipUniformPatcher.getCurrentSubLevelEqVanillaInput();
            } else if (entityStyle) {
                subEq = SubLevelClipUniformPatcher.getCurrentSubLevelEqEye();
                if (subEq == null) {
                    // A shader can bind before the camera matrix is available.
                    subEq = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld();
                }
            } else {
                subEq = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld();
            }
            if (subEq != null) {
                if (IPL$SLOT1_FLIP) {
                    GL41.glProgramUniform4f(
                        program, subLevelLoc,
                        -subEq[0], -subEq[1], -subEq[2], -subEq[3]
                    );
                } else {
                    GL41.glProgramUniform4f(
                        program, subLevelLoc,
                        subEq[0], subEq[1], subEq[2], subEq[3]
                    );
                }
            }
        }
    }

    private static final boolean IPL$SLOT1_FLIP =
        Boolean.getBoolean("ipl.sable.clip.slot1Flip");

    static {
        if (IPL$SLOT1_FLIP) {
            LOG.info("[IPL-PROGRAM-BIND] slot1Flip=TRUE -- writing negated slot-1 equations for diagnostic");
        }
    }
}
