package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.ProgramManager;
import ipl.sable.render.IplProgramBindHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catch program binds that go through {@code ProgramManager.glUseProgram(int)}
 * — the path used by {@code ShaderInstance.apply()},
 * {@code net.irisshaders.iris.pipeline.programs.ExtendedShader.apply()},
 * {@code net.irisshaders.iris.gl.program.Program.use()}, and several other
 * Iris bind sites. None of these touch
 * {@link com.mojang.blaze3d.platform.GlStateManager#_glUseProgram(int)},
 * so {@link IplGlUseProgramProbeMixin} alone misses them — leaving the
 * Iris-rewritten cog program absent from
 * {@link ipl.sable.render.IplSubLevelUniformRegistry} and unable to
 * receive slot-1 uniform writes during sub-level brackets.
 *
 * <p>Empirical proof (cog_leak8.rdc EID 2265): the cog renders with both
 * iportal/subLevel uniforms <em>declared</em> on the program (our YAML
 * transformation reached the shader source), but their VALUES are at
 * the {@code (0,0,0,1)} default — meaning the spray that should have
 * written them never reached this program. Of 12 programs in our
 * registry from that session, none matched the cog's program ID
 * (~RDC resource 4186, much higher than the small MC GL names we saw in
 * the {@code [IPL-GLUSE-WRITE]} log).
 *
 * <p>This mixin's HEAD inject delegates to the shared
 * {@link IplProgramBindHook#onBind(int)} which handles caching,
 * registration, and uniform writes. Idempotent with respect to the
 * sister mixin on {@code _glUseProgram}: even if both fire for the same
 * logical bind, the work just happens twice with identical results.
 */
@Mixin(value = ProgramManager.class, remap = false)
public class IplProgramManagerProbeMixin {

    @Inject(
        method = "glUseProgram(I)V",
        at = @At("HEAD")
    )
    private static void ipl$onProgramManagerUseProgram(int program, CallbackInfo ci) {
        IplProgramBindHook.onBind(program);
    }
}
