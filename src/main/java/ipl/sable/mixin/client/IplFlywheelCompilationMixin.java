package ipl.sable.mixin.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inject IP's portal-clip and Sable's sub-level-clip GLSL into Flywheel-compiled
 * vertex shaders.
 *
 * <p><b>Why this exists:</b> Flywheel (Create's GPU-instancing renderer) has its
 * own shader compile pipeline that bypasses Mojang's
 * {@code com.mojang.blaze3d.shaders.Program}, Veil's preprocessor chain, AND
 * Iris's glsl-transformer entirely. Block-entity sub-components rendered through
 * Flywheel -- Create cogs, Aeronautics animated handles, Simulated sub-models --
 * therefore never received our {@code iportal_ClippingEquation} or
 * {@code ipl_subLevelClipEquation} injections from any other path, and slipped
 * through portals unclipped. Test case that motivated this: a Simulated airship
 * straddling a nether portal with a small animated handle leaking through to
 * the wrong side.
 *
 * <p><b>How it works:</b> Flywheel's
 * {@code dev.engine_room.flywheel.backend.compile.core.Compilation} class builds
 * up the final assembled GLSL source in a {@code StringBuilder fullSource}
 * field, then calls {@code GlCompat.safeShaderSource(...)} with that source at
 * the start of {@code compile(ShaderType, String)}. We hook {@code compile} at
 * HEAD, identify Flywheel's standard vertex-shader signature by anchoring on
 * {@code gl_Position = flw_viewProjection * flw_vertexPos;}, and rewrite
 * {@code fullSource} in place to:
 *
 * <ol>
 *   <li>declare the two clip uniforms after the {@code #version} line</li>
 *   <li>compute {@code _ipl_camRel = flw_vertexPos.xyz - flw_cameraPos} and
 *       write {@code gl_ClipDistance[0..1]} immediately after the
 *       {@code gl_Position} assignment.</li>
 * </ol>
 *
 * <p>The camera-relative-world space matches IP's
 * {@code activeClipPlaneEquationBeforeModelView}, which is what our existing
 * {@code _glUseProgram} hook uploads via {@code glProgramUniform4f} for
 * non-entity-style programs. If Flywheel binds its programs through
 * {@code GlStateManager._glUseProgram} (likely; Mojang's wrapper is the common
 * GL entry point), the existing hook covers the upload side too -- no separate
 * Flywheel program-bind mixin needed. If it doesn't, the IPL-GLUSE-WRITE log
 * won't show Flywheel programs at all and we'll need to add one.
 *
 * <p>{@code @Pseudo} so the mixin tolerates Flywheel's absence without a
 * load-time crash. Skipped silently if the source doesn't contain the anchor
 * (fragment shaders, compute shaders, non-Flywheel-style vertex shaders).
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation", remap = false)
public abstract class IplFlywheelCompilationMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-flywheel-preprocess");

    /** Match `#version 330 core\n`, `#version 460\n`, etc. */
    @Unique
    private static final Pattern IPL$VERSION = Pattern.compile("(#version\\s+\\d+(?:\\s+\\w+)?\\s*\\n)");

    /** Anchor identifies Flywheel's standard vertex-shader chain. */
    @Unique
    private static final String IPL$ANCHOR = "gl_Position = flw_viewProjection * flw_vertexPos;";

    @Unique
    private static final String IPL$UNIFORM_DECLS =
        "\nuniform vec4 iportal_ClippingEquation;\n" +
            "uniform vec4 ipl_subLevelClipEquation;\n";

    @Unique
    private static final String IPL$CLIP_WRITES =
        IPL$ANCHOR + "\n" +
            "    {\n" +
            "        vec3 _ipl_camRel = flw_vertexPos.xyz - flw_cameraPos;\n" +
            "        gl_ClipDistance[0] = dot(_ipl_camRel, iportal_ClippingEquation.xyz) + iportal_ClippingEquation.w;\n" +
            "        gl_ClipDistance[1] = dot(_ipl_camRel, ipl_subLevelClipEquation.xyz) + ipl_subLevelClipEquation.w;\n" +
            "    }\n";

    /** Dedup the per-injection info-log so it fires once per session, not per compile. */
    @Unique
    private static final ConcurrentMap<String, Boolean> IPL$LOGGED = new ConcurrentHashMap<>();

    /** One-shot latch for the "mixin applied" sanity log. */
    @Unique
    private static final AtomicBoolean IPL$LOGGED_APPLY = new AtomicBoolean(false);

    // Flywheel's Compilation.fullSource is declared `final`; without @Final
    // the mixin processor can reject the shadow silently and our @Inject
    // never gets installed. Same convention as IplShaderInstanceClipMixin.
    @Shadow
    @Final
    private StringBuilder fullSource;

    @Inject(
        method = "compile",
        at = @At("HEAD"),
        remap = false
    )
    private void ipl$injectFlywheelClipping(CallbackInfoReturnable<?> cir) {
        // Sanity check that the mixin actually applied at all. Logged once
        // per session regardless of whether the anchor matches, so a session
        // with ANY Flywheel compile activity will leave this line in the log.
        // Absence means the mixin processor never installed us -- check class
        // target string, @Final on the shadow, @Pseudo, etc.
        if (IPL$LOGGED_APPLY.compareAndSet(false, true)) {
            IPL$LOG.info("[IPL-FLYWHEEL-APPLY] IplFlywheelCompilationMixin is active");
        }

        StringBuilder src = this.fullSource;
        if (src == null) return;
        // String search via indexOf on the StringBuilder backing array; cheap.
        int anchorIdx = src.indexOf(IPL$ANCHOR);
        if (anchorIdx < 0) {
            // Not a Flywheel vertex shader with the standard chain. Fragment
            // shaders, compute shaders, and any custom Flywheel variants
            // that build gl_Position differently land here.
            return;
        }

        String original = src.toString();

        // Inject clip writes immediately after the gl_Position assignment.
        String rewritten = original.replace(IPL$ANCHOR, IPL$CLIP_WRITES);

        // Insert uniform declarations right after the first #version line so
        // they're at top-level scope. If there's no #version (shouldn't happen
        // for real Flywheel programs), prepend at the very beginning.
        Matcher m = IPL$VERSION.matcher(rewritten);
        String finalSrc;
        if (m.find()) {
            finalSrc = rewritten.substring(0, m.end()) + IPL$UNIFORM_DECLS + rewritten.substring(m.end());
        } else {
            finalSrc = IPL$UNIFORM_DECLS + rewritten;
        }

        src.setLength(0);
        src.append(finalSrc);

        // One-time log per distinct anchor-line context. Use the next ~80
        // chars after the anchor as a coarse program key -- enough to
        // distinguish posed/oriented/transformed variants without spamming
        // for every recompile.
        String key = original.substring(anchorIdx, Math.min(anchorIdx + 80, original.length()));
        if (IPL$LOGGED.putIfAbsent(key, Boolean.TRUE) == null) {
            IPL$LOG.info("[IPL-FLYWHEEL-INJECT] injected clip writes into Flywheel vertex shader (variant key='{}')",
                key.replace('\n', ' ').trim());
        }
    }

}
