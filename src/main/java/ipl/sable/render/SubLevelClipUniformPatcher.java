package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.q_misc_util.my_util.Plane;

/**
 * Writes our per-sub-level clip equation to the {@code ipl_subLevelClipEquation}
 * uniform that drives {@code gl_ClipDistance[1]} in the shader transformation.
 *
 * <p>This is independent of IP's {@code iportal_ClippingEquation /
 * gl_ClipDistance[0]} pipeline. We compute the equation directly from the
 * supplied {@link Plane} (sub-level-center-oriented, mirror-flip-applied --
 * see {@link SourceClipPortalFinder}) and the current camera position, in
 * the same form IP uses for its own inner clipping:
 *
 * <pre>
 *   c = -n · (planePoint - cameraPos)
 *   equation = (n.x, n.y, n.z, c)
 * </pre>
 *
 * <p>Then we evaluate the discard in the shader against the post-modelview
 * camera-relative world position via the
 * {@code gbufferModelViewInverse × iris_ModelViewMat × (Position + ChunkOffset)}
 * chain in {@code shader_transformation.yaml}. {@code n · worldPos + c > 0}
 * is the kept half-space.
 *
 * <p>{@code patchForSubLevel} uploads when a program is active. If a renderer
 * has only selected a shader, the uniform remains dirty for its next apply;
 * calling {@code glUniform} before that bind is invalid.
 */
public final class SubLevelClipUniformPatcher {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip-patch");
    private static volatile long lastReportNanos = 0L;

    /**
     * Diagnostic mode -- when {@code -Dipl.sable.clip.forceAll=true} is set on the
     * JVM cmdline, override the equation with {@code (0, 0, 0, -1)}: every vertex
     * evaluates to -1 → discarded. If sub-level geometry STILL renders with this
     * active, the uniform/upload pipeline isn't reaching the shader at all.
     */
    private static final boolean FORCE_CLIP_ALL =
        Boolean.getBoolean("ipl.sable.clip.forceAll");

    static {
        LOG.info("[IPL-CLIP-PATCH] static init: FORCE_CLIP_ALL={}", FORCE_CLIP_ALL);
    }

    // Per-reason last-log-time. With multiple distinct shader names alternating
    // every frame (shadow_terrain_cutout, shadow_translucent, empty-name, etc.)
    // a naive "suppress consecutive same reason" approach would fire on every
    // call. Use a map so each distinct reason logs at most once per 5s.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> earlyReturnLastLogNanos =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static void logEarlyReturn(String reason) {
        long now = System.nanoTime();
        Long last = earlyReturnLastLogNanos.get(reason);
        if (last != null && now - last < 5_000_000_000L) {
            return;
        }
        earlyReturnLastLogNanos.put(reason, now);
        LOG.warn("[IPL-CLIP-PATCH] early return: {}", reason);
    }

    /**
     * Currently-active sub-level equation in <b>camera-relative world space</b>.
     * Used by chunk shaders (iris+sodium {@code terrain_*}) which compute
     * {@code _ipl_worldPos = gbufferModelViewInverse * iris_ModelViewMat *
     * (iris_Position + iris_ChunkOffset)} and dot against this equation in
     * world space.
     */
    private static volatile float[] currentSubLevelEqWorld = null;

    /**
     * Persistent "last seen" world-space equation, never cleared by
     * {@link #clearAndUpload}. Set whenever {@link #patchForSubLevel}
     * fires. Used by hooks that run OUTSIDE the sub-level bracket --
     * notably {@code IplFlywheelEmbeddedClipMixin.setupDraw} on Flywheel's
     * {@code EmbeddedEnvironment}, which runs at a separate frame stage
     * after all Sable brackets have exited (the cog draws happen here
     * because Flywheel batches per-sub-level BE visualizations and
     * dispatches them outside Sable's chunk-render brackets).
     *
     * <p>Caveat: for multi-sub-level scenes, this only holds the LAST
     * sub-level's equation -- not per-sub-level. Adequate for the airship
     * test case; needs a sceneId-indexed map for full correctness.
     */
    private static volatile float[] latestSubLevelEqWorld = null;

    /** Persistent counterpart of {@link #currentSubLevelEqEye}. */
    private static volatile float[] latestSubLevelEqEye = null;

    /**
     * Timestamp (System.nanoTime) of the most recent {@link #patchForSubLevel}
     * call. Latest equations are considered "fresh" if set within
     * {@link #LATEST_STALE_THRESHOLD_NANOS}; older = treated as null by
     * {@link #getLatestSubLevelEqWorld} / {@code Eye} / {@code Local}.
     *
     * <p>Without this, latest values persist forever after a sub-level
     * disengages from a portal -- and the Flywheel embedded clip mixin
     * keeps writing them + enabling CD1 every frame. Result: "clipping
     * enabled before contact" -- the system stays active long after the
     * airship has left the portal. Fresh-frame check fixes this without
     * needing a per-frame hook.
     *
     * <p>50ms ≈ 3 frames at 60fps; long enough that mid-frame Flywheel
     * draws see the latest from the bracket that fired earlier in the
     * same frame, short enough to expire within a few frames after the
     * sub-level disengages.
     */
    private static volatile long latestSetNanos = 0L;
    private static final long LATEST_STALE_THRESHOLD_NANOS = 50_000_000L; // 50ms

    /**
     * Sub-level-local equation. For programs whose vertex shader chain
     * produces a position in the SUB-LEVEL's local coordinate frame
     * (not world / not camera-relative).
     *
     * <p>Empirical finding (user-confirmed by airship Y-rotation test):
     * for Create cog block-entity rendering, the chain
     * {@code gbufferModelViewInverse * iris_ModelViewMat * iris_Position}
     * produces a SUB-LEVEL-LOCAL position rather than world position --
     * apparently Sable's sub-level transform is NOT in iris_ModelViewMat
     * for the BE path even though it IS for the chunked-section path.
     * Dotting a world-space equation against sub-level-local position
     * gives clipping that rotates with the airship (works when airship
     * rotation aligns sub-level axes with world axes; wrong otherwise).
     *
     * <p>Math: world plane (n_w, c_w) with c_w = -n_w·p_w. Substitute
     * P_w = R_sub·P_local + t_sub:
     *   dot(R_sub·P_local + t_sub, n_w) - n_w·p_w
     *   = dot(P_local, R_sub^T·n_w) + n_w·(t_sub - p_w)
     * Local form: n_local = R_sub^T·n_w, c_local = -n_w·(p_w - t_sub).
     * Pose3dc gives this directly via transformNormalInverse +
     * transformPositionInverse.
     */
    private static volatile float[] currentSubLevelEqLocal = null;

    /**
     * Equation for vanilla Sable chunk vertices. Their {@code Position + ChunkOffset}
     * is plot-local after camera translation, unlike Iris/Sodium terrain world positions.
     */
    private static volatile float[] currentSubLevelEqVanillaInput = null;

    /** Persistent counterpart of {@link #currentSubLevelEqLocal}. */
    private static volatile float[] latestSubLevelEqLocal = null;

    /**
     * Currently-active sub-level equation in <b>eye space</b> (rotated by the
     * camera's view rotation). Used by entity-style shaders (Iris-rewritten
     * {@code block_entity_diffuse} / {@code moving_block}, vanilla
     * {@code rendertype_entity_*}, Veil-managed Simulated/Aeronautics
     * shaders) which compute {@code _ipl_eyePos = iris_ModelViewMat *
     * iris_Position} (or {@code ModelViewMat * Position}) and dot against
     * this equation in eye space.
     *
     * <p><b>Why two variants:</b> the world-space equation has the form
     * {@code (n_w, c)} where {@code c = -n_w · (planePoint - camPos)}.
     * For an eye-space vertex {@code e = R_view · (P_world - camPos)},
     * the signed distance is {@code (R_view · n_w) · e + c}. So the
     * eye-space normal is the world normal rotated by the camera-only
     * view rotation; the constant stays the same.
     *
     * <p>Without this variant, eye-space shaders dot a rotated vertex
     * against an un-rotated equation -- the clip plane ends up at a
     * rotated angle, so cogs and other entity-style sub-level meshes
     * either always pass the clip test or get culled on a wrong-axis
     * plane (RenderDoc confirmed via EID 2294 in cog_leak5.rdc).
     */
    private static volatile float[] currentSubLevelEqEye = null;

    /**
     * @deprecated Use {@link #getCurrentSubLevelEqWorld()} or
     *     {@link #getCurrentSubLevelEqEye()} -- {@code IplGlUseProgramProbeMixin}
     *     picks the right space per program via {@code IplProgramRegistry.isEntityStyleProgram}.
     */
    @Deprecated
    public static float[] getCurrentSubLevelEq() {
        return currentSubLevelEqWorld;
    }

    /** Camera-relative world-space equation, or null if no bracket is active. */
    public static float[] getCurrentSubLevelEqWorld() {
        return currentSubLevelEqWorld;
    }

    /** Eye-space equation (world normal rotated by view), or null. */
    public static float[] getCurrentSubLevelEqEye() {
        return currentSubLevelEqEye;
    }

    /**
     * World-space equation from the most recent {@link #patchForSubLevel},
     * persistent across bracket exit BUT returns null if no patch fired
     * within {@link #LATEST_STALE_THRESHOLD_NANOS}. Used by hooks that
     * run outside the Sable bracket scope (Flywheel EmbeddedEnvironment
     * setupDraw) which can fire even when no bracket is active for the
     * sub-level being drawn -- the freshness check prevents stale-value
     * application after the airship disengages.
     */
    public static float[] getLatestSubLevelEqWorld() {
        if (ipl$latestIsStale()) return null;
        return latestSubLevelEqWorld;
    }

    /** Eye-space counterpart of {@link #getLatestSubLevelEqWorld()}. */
    public static float[] getLatestSubLevelEqEye() {
        if (ipl$latestIsStale()) return null;
        return latestSubLevelEqEye;
    }

    /** Sub-level-local equation, current bracket. */
    public static float[] getCurrentSubLevelEqLocal() {
        return currentSubLevelEqLocal;
    }

    public static float[] getCurrentSubLevelEqVanillaInput() {
        return currentSubLevelEqVanillaInput;
    }

    /**
     * Second cut plane (multi-straddle), same spaces as the primaries; null means
     * "no second cut" and uploaders write the neutral {@code (0,0,0,1)}. The shader
     * takes {@code min} of both plane distances into {@code gl_ClipDistance[1]}.
     */
    private static volatile float[] currentSubLevelEqWorld2 = null;
    private static volatile float[] currentSubLevelEqEye2 = null;
    private static volatile float[] currentSubLevelEqVanillaInput2 = null;

    public static float[] getCurrentSubLevelEqWorld2() {
        return currentSubLevelEqWorld2;
    }

    public static float[] getCurrentSubLevelEqEye2() {
        return currentSubLevelEqEye2;
    }

    public static float[] getCurrentSubLevelEqVanillaInput2() {
        return currentSubLevelEqVanillaInput2;
    }

    /** Sub-level-local equation, persistent across bracket exit (with freshness check). */
    public static float[] getLatestSubLevelEqLocal() {
        if (ipl$latestIsStale()) return null;
        return latestSubLevelEqLocal;
    }

    private static boolean ipl$latestIsStale() {
        return System.nanoTime() - latestSetNanos > LATEST_STALE_THRESHOLD_NANOS;
    }

    private SubLevelClipUniformPatcher() {}

    /**
     * Compute the clip equation from {@code plane} (in camera-relative form) and
     * push to {@code ipl_subLevelClipEquation} on the currently-bound shader.
     * Upload only when its GL program is active; otherwise leave it dirty for
     * ShaderInstance.apply rather than issuing glUniform with program zero.
     */
    public static void patchForSubLevel(ClientSubLevel sub, Plane plane) {
        patchForSubLevel(sub, plane, null);
    }

    /**
     * Two-plane variant (multi-straddle): {@code plane2} is the SECOND cut, or null
     * for single-cut behavior. Both flow into the same {@code gl_ClipDistance[1]}
     * via the shader-side {@code min}.
     */
    public static void patchForSubLevel(
        ClientSubLevel sub, Plane plane, @org.jetbrains.annotations.Nullable Plane plane2
    ) {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            logEarlyReturn("RenderSystem.getShader() null");
            return;
        }
        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        if (uniform == null) {
            // Silent skip for shaders we haven't transformed: shadow shaders, empty
            // name (Veil/Sodium internal blits), particle, vanilla rendertype_*.
            // They fire every frame; logging would spam.
            String name = shader.getName();
            if (!name.isEmpty()
                && !name.startsWith("shadow_")
                && !name.equals("particle")
                && !name.startsWith("rendertype_")) {
                logEarlyReturn("shader '" + name + "' has no ipl_subLevelClipEquation uniform");
            }
            return;
        }

        // Build the camera-relative world-space clip equation used by Iris/Sodium.
        // Vanilla Sable terrain receives an inverse-rotated normal below because its
        // Position + ChunkOffset remains plot-local until the model-view transform.
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        Vec3 n = plane.normal();
        Vec3 p = plane.pos();
        double c = -(n.x * (p.x - cameraPos.x)
                   + n.y * (p.y - cameraPos.y)
                   + n.z * (p.z - cameraPos.z));

        float nx, ny, nz, cw;
        if (FORCE_CLIP_ALL) {
            nx = 0f; ny = 0f; nz = 0f; cw = -1f;
        } else {
            nx = (float) n.x;
            ny = (float) n.y;
            nz = (float) n.z;
            cw = (float) c;
        }

        float[] eqVanillaInput = null;
        if (sub != null) {
            try {
                Pose3dc renderPose = sub.renderPose();
                Vec3 nInput = renderPose.transformNormalInverse(n);
                eqVanillaInput = new float[]{(float) nInput.x, (float) nInput.y, (float) nInput.z, cw};
            } catch (Exception e) {
                // Fall back to world space if a transient pose read is unavailable.
            }
        }
        currentSubLevelEqVanillaInput = eqVanillaInput;

        // Second cut (multi-straddle): same three spaces, from plane2's geometry.
        float[] eqWorld2 = null;
        float[] eqVanillaInput2 = null;
        if (plane2 != null) {
            Vec3 n2 = plane2.normal();
            Vec3 p2 = plane2.pos();
            double c2 = -(n2.x * (p2.x - cameraPos.x)
                        + n2.y * (p2.y - cameraPos.y)
                        + n2.z * (p2.z - cameraPos.z));
            eqWorld2 = new float[]{(float) n2.x, (float) n2.y, (float) n2.z, (float) c2};
            if (sub != null) {
                try {
                    Vec3 nInput2 = sub.renderPose().transformNormalInverse(n2);
                    eqVanillaInput2 = new float[]{
                        (float) nInput2.x, (float) nInput2.y, (float) nInput2.z, (float) c2};
                } catch (Exception e) {
                    // world fallback below
                }
            }
        }
        currentSubLevelEqWorld2 = eqWorld2;
        currentSubLevelEqVanillaInput2 = eqVanillaInput2;

        float[] currentEq = IplProgramRegistry.isVanillaSubLevelInputShader(shader.getName())
            && eqVanillaInput != null ? eqVanillaInput : new float[]{nx, ny, nz, cw};
        boolean vanillaInputShader = IplProgramRegistry.isVanillaSubLevelInputShader(shader.getName());
        float[] currentEq2 = plane2 == null ? null
            : (vanillaInputShader && eqVanillaInput2 != null ? eqVanillaInput2 : eqWorld2);
        // IPL fix (P3): only touch the tracked shader's uniform when its program is
        // ACTUALLY bound. RenderSystem.getShader() is CPU-side tracking — post/blit
        // passes leave glUseProgram(0) while it's still set, and upload() then raises
        // GL_INVALID_OPERATION "No active program" every frame, poisoning GL state
        // (storms observed right before several native crashes). The registry spray
        // below reaches registered programs bind-free.
        int boundProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (boundProgram == shader.getId()) {
            uniform.set(currentEq[0], currentEq[1], currentEq[2], currentEq[3]);
            uniform.upload();
            Uniform uniform2 = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform2();
            if (uniform2 != null) {
                if (currentEq2 != null) {
                    uniform2.set(currentEq2[0], currentEq2[1], currentEq2[2], currentEq2[3]);
                } else {
                    uniform2.set(0f, 0f, 0f, 1f);
                }
                uniform2.upload();
            }
        } else {
            logEarlyReturn(
                "shader '" + shader.getName() + "' tracked but not bound — registry spray only");
        }

        // Publish world-space equation for chunk shaders (iris+sodium
        // terrain_*) that dot against a world-space position chain.
        float[] eqWorld = new float[]{nx, ny, nz, cw};
        currentSubLevelEqWorld = eqWorld;
        latestSubLevelEqWorld = eqWorld;
        // Mark freshness so getLatestSubLevelEq* don't return stale values
        // after the sub-level disengages from any portal.
        latestSetNanos = System.nanoTime();

        // Compute and publish the eye-space variant for entity-style
        // shaders (Iris-rewritten block_entity_diffuse / moving_block,
        // vanilla rendertype_entity_*, Veil-managed Simulated/Aeronautics).
        // n_eye = R_view * n_world, c stays the same.
        //
        // The active ModelView can include portal frame transforms in addition to
        // camera rotation. Transform the plane covariantly through that exact
        // matrix; camera.rotation() alone is wrong in portal eye-space.
        float[] eqEye = transformEquationForModelView(eqWorld, RenderSystem.getModelViewMatrix());
        currentSubLevelEqEye = eqEye;
        latestSubLevelEqEye = eqEye;
        if (eqWorld2 != null) {
            currentSubLevelEqEye2 = transformEquationForModelView(
                eqWorld2, RenderSystem.getModelViewMatrix());
        } else {
            currentSubLevelEqEye2 = null;
        }

        // Retain the local form for shader paths that explicitly recover plot
        // coordinates. Vanilla entity/text shaders do not use it: their emitted
        // vertices already include Sable's BE pose transform and are clipped in
        // eye space with eqEye below.
        float[] eqLocal = null;
        if (sub != null) {
            try {
                Pose3dc renderPose = sub.renderPose();
                Vec3 nLocal = renderPose.transformNormalInverse(n);
                Vec3 pLocal = renderPose.transformPositionInverse(p);
                double cLocal = -(nLocal.x * pLocal.x
                                + nLocal.y * pLocal.y
                                + nLocal.z * pLocal.z);
                eqLocal = new float[]{
                    (float) nLocal.x, (float) nLocal.y, (float) nLocal.z,
                    (float) cLocal
                };
            } catch (Exception e) {
                // Pose unavailable / inverse failed -- leave local null,
                // entity-style programs fall back to world.
            }
        }
        currentSubLevelEqLocal = eqLocal;
        latestSubLevelEqLocal = eqLocal;

        // Spray BOTH variants to every program known to carry the uniform,
        // picking the right space per program via the entity-style registry.
        // Handles the case (RenderDoc-confirmed in cog_leak5.rdc EID 2294)
        // where a program was bound BEFORE this bracket started and won't
        // re-trigger _glUseProgram during the bracket -- without this loop
        // its slot-1 stays at (0,0,0,1) and the cog leaks.
        // glProgramUniform4f writes to a specified program regardless of
        // bind state (GL 4.1+).
        final float[] eqW = currentSubLevelEqWorld;
        final float[] eqV = currentSubLevelEqVanillaInput;
        final float[] eqW2 = currentSubLevelEqWorld2;
        final float[] eqV2 = currentSubLevelEqVanillaInput2;
        final float[] eqE2 = currentSubLevelEqEye2;
        IplSubLevelUniformRegistry.forEach((programId, locs) -> {
            // Entity-style programs evaluate their transformed BE vertices in
            // eye space. Chunks use world/input space.
            float[] eq;
            float[] eq2;
            if (IplProgramRegistry.usesVanillaSubLevelInputSpace(programId) && eqV != null) {
                eq = eqV;
                eq2 = eqV2;
            } else if (IplProgramRegistry.isEntityStyleProgram(programId)) {
                eq = currentSubLevelEqEye;
                eq2 = eqE2;
            } else {
                eq = eqW;
                eq2 = eqW2;
            }
            GL41.glProgramUniform4f(programId, locs[0], eq[0], eq[1], eq[2], eq[3]);
            if (locs[1] >= 0) {
                if (eq2 != null) {
                    GL41.glProgramUniform4f(programId, locs[1], eq2[0], eq2[1], eq2[2], eq2[3]);
                } else {
                    GL41.glProgramUniform4f(programId, locs[1], 0f, 0f, 0f, 1f);
                }
            }
        });

        // Defensive re-enable: Veil bloom (RenderDoc EID ~2453 in cog_leak3)
        // disables GL_CLIP_DISTANCE1 mid-frame and doesn't restore it. If our
        // bracket comes after bloom, slot-1 writes from the vertex shader are
        // silently ignored unless we explicitly re-enable.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        // Slot 2 = second sub-level cut, which now writes its own clip
        // distance rather than being min()'d into slot 1.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE2);

        long now = System.nanoTime();
        if (now - lastReportNanos >= 5_000_000_000L) {
            lastReportNanos = now;
            LOG.info("[IPL-CLIP-PATCH] shader={} forceAll={} subLevel={} wrote=({},{},{},{})",
                shader.getName(),
                FORCE_CLIP_ALL,
                sub != null ? sub.getUniqueId() : null,
                nx, ny, nz, cw
            );
        }
    }

    /** Converts a camera-relative world plane to the current shader eye space. */
    private static float[] transformEquationForModelView(float[] equation, Matrix4f modelView) {
        org.joml.Vector4f transformed = new org.joml.Vector4f(
            equation[0], equation[1], equation[2], equation[3]);
        new Matrix4f(modelView).invert().transpose().transform(transformed);
        return new float[]{transformed.x, transformed.y, transformed.z, transformed.w};
    }

    /**
     * Adapts IP's slot-0 portal equation for Sable's vanilla chunk draw. Sable
     * feeds {@code Position + ChunkOffset} in plot-local, camera-relative space;
     * its model-view matrix applies the sub-level rotation only afterwards. IP's
     * normal world-space terrain equation therefore has to be inverse-rotated for
     * this one draw. The constant is already camera-relative and remains valid.
     */
    public static boolean patchPortalClipForVanillaSubLevel(
        ShaderInstance shader, ClientSubLevel sub, double[] worldEquation
    ) {
        if (shader == null || sub == null || worldEquation == null) return false;

        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return false;

        try {
            Vec3 normal = new Vec3(worldEquation[0], worldEquation[1], worldEquation[2]);
            Vec3 localNormal = sub.renderPose().transformNormalInverse(normal);
            // IPL fix (micro rim behind the portal plane).
            //
            // MixinLevelRenderer installs IP's slot-0 inner clipping with
            // `adjustment = -FrontClipping.ADJUSTMENT`, i.e. the plane is pushed 0.01
            // blocks BACK along its own normal so IP's own destination TERRAIN does not
            // z-fight with the portal quad. The kept half-space therefore starts 0.01
            // blocks BEFORE the mathematical portal plane.
            //
            // Our slot-1 sub-level cut sits exactly ON the mathematical plane and keeps
            // the complementary half. Both slots are active for a sub-level draw inside
            // a portal-through pass, so the surviving region is the INTERSECTION:
            //
            //     n·p > w_exact          (slot 0, shifted back by 0.01)
            //     n·p < w_exact + 0.01   (slot 1, exact)
            //
            // = a 0.01-block-thick slab of the ship sitting just past the portal plane.
            // That slab is the "micro rim": it is drawn for the whole crossing and its
            // width never changes, because it is literally ADJUSTMENT wide.
            // (RenderDoc obodok.rdc: slot0 = {0.72907,-0.1988,0.65493,-0.84243},
            //  slot1 = {-0.72907,0.1988,-0.65493,0.85243} -> |Δw| = 0.01 exactly.)
            //
            // Undoing the adjustment for THIS scoped sub-level draw only puts both cuts
            // on the identical plane, so the intersection is empty and the rim is gone.
            // IP's z-fighting guard is unaffected: slot-0 is restored to its original
            // world equation by restorePortalClip() as soon as the bracket exits, so
            // the parent world's terrain still renders with the 0.01 back-shift.
            double exactW = worldEquation[3] - qouteall.imm_ptl.core.render.FrontClipping.ADJUSTMENT;
            uniform.set(
                (float) localNormal.x, (float) localNormal.y, (float) localNormal.z,
                (float) exactW
            );
            uniform.upload();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Restore IP's world-space slot-0 equation after the scoped Sable draw. */
    public static void restorePortalClip(ShaderInstance shader, double[] worldEquation) {
        if (shader == null || worldEquation == null) return;

        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return;
        uniform.set(
            (float) worldEquation[0], (float) worldEquation[1],
            (float) worldEquation[2], (float) worldEquation[3]
        );
        uniform.upload();
    }

    /**
     * Restore the per-sub-level clip uniform to "no clipping" {@code (0,0,0,1)} and
     * push to the GPU. Used by mixin RETURN paths so subsequent draws (vanilla
     * terrain after our sub-level render) don't inherit a stale equation.
     */
    public static void clearAndUpload() {
        // Clear the published equations first so subsequent program binds
        // outside the sub-level bracket fall back to portal-mirror or
        // no-clip writes in IplGlUseProgramProbeMixin.
        currentSubLevelEqWorld = null;
        currentSubLevelEqEye = null;
        currentSubLevelEqVanillaInput = null;
        currentSubLevelEqWorld2 = null;
        currentSubLevelEqEye2 = null;
        currentSubLevelEqVanillaInput2 = null;

        // NOTE: We intentionally do NOT spray (0,0,0,1) to all registered
        // programs here. Doing so was the root cause of the cog-leak bug
        // (cog_leak3/5.rdc EID 2294: slot-1 was at default because clearAndUpload
        // had just reset every program after the chunk bracket exited).
        // The bracket's GL_CLIP_DISTANCE1 disable in finally is what makes
        // stale slot-1 values inert for subsequent draws -- so leaving
        // them at the last sprayed equation is safe.
        //
        // For the currently-bound shader, the existing set/upload still
        // restores its slot-1 to identity, in case some downstream code
        // re-enables CLIP_DISTANCE1 without going through patchForSubLevel.

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        if (uniform == null) return;
        // Same bound-program guard as patchForSubLevel (IPL fix P3).
        if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != shader.getId()) return;
        uniform.set(0f, 0f, 0f, 1f);
        uniform.upload();
        Uniform uniform2 = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform2();
        if (uniform2 != null) {
            uniform2.set(0f, 0f, 0f, 1f);
            uniform2.upload();
        }
    }
}
