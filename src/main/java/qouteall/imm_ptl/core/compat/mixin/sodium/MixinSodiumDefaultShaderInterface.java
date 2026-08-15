package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Pseudo
@Mixin(value = DefaultShaderInterface.class, remap = false)
public class MixinSodiumDefaultShaderInterface {
    @Unique
    private GlUniformFloat4v uIPClippingEquation;

    // Probe E: once-per-variant log so we can see, for each ChunkShaderOptions
    // variant Sodium builds (pass × fog × vertex format etc.), whether
    // bindUniformOptional actually returned a real binding. Null means the
    // linked program doesn't expose iportal_ClippingEquation -- which under
    // Sodium implies either the shader name in our YAML doesn't match what
    // Sodium loads, or the linker stripped the uniform.
    @Unique
    private static final Logger IPL$PROBE_LOG = LoggerFactory.getLogger("ipl-shader-intercept");
    @Unique
    private static final ConcurrentMap<String, Boolean> IPL$PROBE_E_SEEN = new ConcurrentHashMap<>();

    @Inject(
        method = "<init>",
        at = @At("RETURN"),
//        require = 0,
        remap = false
    )
    private void onInit(
        ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci
    ) {
        this.uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new);

        String optKey = options == null ? "null" : options.toString();
        if (IPL$PROBE_E_SEEN.putIfAbsent(optKey, Boolean.TRUE) == null) {
            IPL$PROBE_LOG.info(
                "[IPL-PROBE-E-SODIUM-CHUNK] options={} iportalClipBound={}",
                optKey,
                this.uIPClippingEquation != null
            );
        }
    }

    @Inject(
        method = "setupState",
        at = @At("RETURN"),
        remap = false
    )
    private void onSetup(CallbackInfo ci) {
        if (uIPClippingEquation != null) {
            if (FrontClipping.isClippingEnabled) {
                double[] equation = FrontClipping.getActiveClipPlaneEquationAfterModelView();
                uIPClippingEquation.set(new float[]{
                    (float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]
                });
            }
            else {
                uIPClippingEquation.set(new float[]{0, 0, 0, 1});
            }
        }
    }
}
