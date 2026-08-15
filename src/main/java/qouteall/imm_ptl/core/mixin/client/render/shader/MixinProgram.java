package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(value = Program.class)
public class MixinProgram {
    // Probe A: once-per-distinct-name diagnostic so we can see which shader IDs
    // actually flow through Mojang's compile path (and therefore through our
    // regex transformation). If a name in the YAML never shows up here, our
    // affectedShaders entry for it is dead.
    private static final Logger IPL$PROBE_LOG = LoggerFactory.getLogger("ipl-shader-intercept");
    private static final ConcurrentMap<String, Boolean> IPL$SEEN_VANILLA = new ConcurrentHashMap<>();
    // The redirect uses method arguments.
    // Iris also injects that method and uses local capture, so cannot overwrite.
    private static final ThreadLocal<Program.Type> ip_programType = new ThreadLocal<>();
    private static final ThreadLocal<String> ip_programName = new ThreadLocal<>();
    
    @Inject(
        method = "compileShaderInternal",
        at = @At("HEAD")
    )
    private static void onBeginCompileShaderInternal(
        Program.Type type, String name, InputStream shaderData,
        String sourceName, GlslPreprocessor preprocessor, CallbackInfoReturnable<Integer> cir
    ) {
        Validate.isTrue(ip_programType.get() == null);
        Validate.isTrue(ip_programName.get() == null);
        ip_programType.set(type);
        ip_programName.set(name);
    }
    
    @Inject(
        method = "compileShaderInternal",
        at = @At("RETURN")
    )
    private static void onEndCompileShaderInternal(
        Program.Type type, String name, InputStream shaderData,
        String sourceName, GlslPreprocessor preprocessor, CallbackInfoReturnable<Integer> cir
    ) {
        Validate.isTrue(ip_programType.get() == type);
        Validate.isTrue(Objects.equals(ip_programName.get(), name));
        ip_programType.set(null);
        ip_programName.set(null);
    }
    
    @Redirect(
        method = "compileShaderInternal",
        at = @At(
            value = "INVOKE",
            target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;",
            remap = false
        )
    )
    private static String redirectReadShaderSource(
        InputStream inputStream, Charset charset
    ) throws IOException {
        String shaderCode = IOUtils.toString(inputStream, charset);
        Program.Type type = ip_programType.get();
        String name = ip_programName.get();
        Validate.notNull(type);
        Validate.notNull(name);
        
        String transformedShaderCode =
            ShaderCodeTransformation.transform(type, name, shaderCode);

        // Probe A: log the first time we see each distinct (type, name) pair,
        // plus whether the regex actually altered the source (changed != was-affected;
        // an entry in YAML can still no-op if its pattern doesn't match the actual source).
        String key = type + ":" + name;
        if (IPL$SEEN_VANILLA.putIfAbsent(key, Boolean.TRUE) == null) {
            boolean changed = !shaderCode.equals(transformedShaderCode);
            boolean inList = ShaderCodeTransformation.shouldAddUniform(name);
            IPL$PROBE_LOG.info(
                "[IPL-PROBE-A-VANILLA] type={} name='{}' inAffectedList={} regexChangedSource={}",
                type, name, inList, changed
            );
        }

        return transformedShaderCode;
    }
}
