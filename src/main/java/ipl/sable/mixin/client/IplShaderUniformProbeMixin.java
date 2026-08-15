package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Shader;
import ipl.sable.render.IplProgramRegistry;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Probe C: at RETURN of {@link ShaderInstance#updateLocations()}, enumerate every
 * active uniform the linker kept in the program via {@code glGetActiveUniform},
 * and report whether {@code iportal_ClippingEquation} (and our slot-1
 * {@code ipl_subLevelClipEquation}) survived.
 *
 * <p>This is the definitive answer for the open question "did the GLSL linker
 * strip our uniform?" The {@code "Shader X could not find uniform named Y"}
 * warnings tell us glGetUniformLocation returned -1, but that's a side-effect
 * symptom. Listing the actual active-uniforms set distinguishes:
 *
 * <ul>
 *   <li><b>Uniform missing from list</b> — linker stripped it. Our injection
 *       made it into source but the optimizer decided the gl_ClipDistance write
 *       was dead. Hypothesis space: gl_PerVertex redeclaration, glEnable
 *       state visibility, driver bug.</li>
 *   <li><b>Uniform present in list but glGetUniformLocation returns -1</b> —
 *       Mojang-side bug: we're querying location on the wrong program, or the
 *       resolution call is happening before link. Different fix entirely.</li>
 * </ul>
 *
 * <p>Only logs once per (shader name, uniform set) pair to avoid spam, and only
 * for shaders that IP's {@code ShaderCodeTransformation.shouldAddUniform}
 * reports as in the affectedShaders list (those are the ones we care about).
 */
@Mixin(ShaderInstance.class)
public abstract class IplShaderUniformProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-shader-uniform-probe");

    @Unique
    private static final ConcurrentMap<String, Boolean> IPL$PROBED = new ConcurrentHashMap<>();

    @Shadow @Final private String name;

    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("RETURN")
    )
    private void ipl$probeActiveUniforms(CallbackInfo ci) {
        // Only probe shaders we expect to carry iportal_ClippingEquation.
        if (!ShaderCodeTransformation.shouldAddUniform(name)) {
            return;
        }

        Shader self = (Shader) (Object) this;
        int programId = self.getId();

        // ALWAYS register the programId -> entity-style mapping (outside the
        // per-name dedup below) because Veil recompiles vanilla shaders with
        // fresh program ids, calling updateLocations on the new program. The
        // mapping needs to track the *current* programId for each shader name
        // so the _glUseProgram hook writes the correct-space equation. Stale
        // entries (old programIds) are harmless -- they'll never be bound
        // again, and IS_ENTITY_STYLE matching is the same regardless.
        if (programId != 0) {
            IplProgramRegistry.register(programId, name);
        }

        if (IPL$PROBED.putIfAbsent(name, Boolean.TRUE) != null) {
            return;
        }

        if (programId == 0) {
            IPL$LOG.warn("[IPL-PROBE-C] name='{}' programId=0 -- can't enumerate, link likely failed", name);
            return;
        }

        // Link-status check first. If link failed, the program info log explains why.
        IntBuffer statusBuf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(programId, GL20.GL_LINK_STATUS, statusBuf);
        int linkStatus = statusBuf.get(0);

        IntBuffer countBuf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(programId, GL20.GL_ACTIVE_UNIFORMS, countBuf);
        int activeUniformCount = countBuf.get(0);

        String programLog = GL20.glGetProgramInfoLog(programId, 4096);
        boolean haveLog = programLog != null && !programLog.isEmpty();

        List<String> uniformNames = new ArrayList<>();
        boolean clipPresent = false;
        boolean subLevelClipPresent = false;
        for (int i = 0; i < activeUniformCount; i++) {
            IntBuffer sizeBuf = BufferUtils.createIntBuffer(1);
            IntBuffer typeBuf = BufferUtils.createIntBuffer(1);
            String uName = GL20.glGetActiveUniform(programId, i, sizeBuf, typeBuf);
            int loc = GL20.glGetUniformLocation(programId, uName);
            uniformNames.add(uName + "@" + loc + ":t=" + typeBuf.get(0) + ":s=" + sizeBuf.get(0));
            if ("iportal_ClippingEquation".equals(uName)) clipPresent = true;
            if ("ipl_subLevelClipEquation".equals(uName)) subLevelClipPresent = true;
        }

        IPL$LOG.info(
            "[IPL-PROBE-C] name='{}' linkStatus={} activeUniforms={} iportalClip={} subLevelClip={}{}",
            name,
            linkStatus == GL20.GL_TRUE ? "OK" : ("FAIL(" + linkStatus + ")"),
            activeUniformCount,
            clipPresent,
            subLevelClipPresent,
            haveLog ? "\n----- PROGRAM INFO LOG -----\n" + programLog.trim() + "\n----- END LOG -----" : ""
        );
        IPL$LOG.info("[IPL-PROBE-C] name='{}' uniforms={}", name, uniformNames);
    }
}
