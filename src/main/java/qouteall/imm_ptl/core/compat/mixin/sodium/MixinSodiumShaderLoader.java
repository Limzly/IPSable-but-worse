package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.Program;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = ShaderLoader.class)
public abstract class MixinSodiumShaderLoader {

    // Probe A: once-per-name visibility into which Sodium shader names actually
    // pass through here. Under modern Sodium (>= 0.5) the resource paths may not
    // match the legacy `sodium:blocks/block_layer_opaque.vsh` in our YAML, in
    // which case terrain clipping is silently bypassed at load time.
    private static final Logger IPL$PROBE_LOG = LoggerFactory.getLogger("ipl-shader-intercept");
    private static final ConcurrentMap<String, Boolean> IPL$SEEN_SODIUM = new ConcurrentHashMap<>();

    @WrapOperation(
        method = "loadShader",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderLoader;getShaderSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
            remap = true
        ),
        remap = false
    )
    private static String wrapGetShaderSource(
        ResourceLocation name,
        Operation<String> operation,
        @Local(argsOnly = true) ShaderType shaderType
    ) {
        String shaderSource = operation.call(name);
        String nameStr = name.toString();
        String transformed = ShaderCodeTransformation.transform(
            shaderType == ShaderType.VERTEX ? Program.Type.VERTEX : Program.Type.FRAGMENT,
            nameStr, shaderSource
        );

        String key = shaderType + ":" + nameStr;
        if (IPL$SEEN_SODIUM.putIfAbsent(key, Boolean.TRUE) == null) {
            boolean changed = !shaderSource.equals(transformed);
            boolean inList = ShaderCodeTransformation.shouldAddUniform(nameStr);
            IPL$PROBE_LOG.info(
                "[IPL-PROBE-A-SODIUM] type={} name='{}' inAffectedList={} regexChangedSource={}",
                shaderType, nameStr, inList, changed
            );
        }

        return transformed;
    }
}
