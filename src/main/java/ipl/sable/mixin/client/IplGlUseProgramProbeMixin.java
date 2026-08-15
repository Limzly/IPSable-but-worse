package ipl.sable.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import ipl.sable.render.IplProgramBindHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches GL program binds that go through {@link GlStateManager#_glUseProgram(int)}.
 *
 * <p>This is the chokepoint for:
 * <ul>
 *   <li>Vanilla MC's own {@code setShader} fast path (when no PoseStack
 *       rotation is in play).</li>
 *   <li>Some Iris paths.</li>
 *   <li>Any third-party mod that calls {@code GlStateManager._glUseProgram}
 *       directly without going through {@code ProgramManager.glUseProgram}
 *       or Flywheel's {@code GlProgram.bind}.</li>
 * </ul>
 *
 * <p><b>This mixin is a thin delegator.</b> The actual clip-equation write
 * logic lives in {@link IplProgramBindHook#onBind(int)}, shared with the
 * sister probes {@link IplProgramManagerProbeMixin} (ProgramManager path)
 * and {@link IplFlywheelEmbeddedClipMixin} (Flywheel embedded path).
 *
 * <p><b>Why this matters:</b> this file used to have its own ~200-line
 * inlined copy of the bind logic. Bug 2 (no-clip sentinel for stale
 * uniforms) and Bug 4 (camera-relative clip equation refresh) fixes were
 * only applied to {@code IplProgramBindHook.onBind}, not the inlined
 * copy here — meaning vanilla terrain shaders bound via
 * {@code _glUseProgram} still had stale equations and leaked. Bug A in
 * the worklog. Deduplicating here means every fix in
 * {@link IplProgramBindHook} applies uniformly to all bind paths.
 *
 * <p><b>Idempotency:</b> safe to call multiple times per bind — the shared
 * helper's uniform writes are idempotent. So if both this probe and
 * {@link IplProgramManagerProbeMixin} fire for the same logical bind, we
 * just do the work twice with identical results.
 */
@Mixin(value = GlStateManager.class, remap = false)
public class IplGlUseProgramProbeMixin {

    @Inject(
        method = "_glUseProgram(I)V",
        at = @At("HEAD")
    )
    private static void ipl$writeClipEquationToProgram(int program, CallbackInfo ci) {
        IplProgramBindHook.onBind(program);
    }
}
