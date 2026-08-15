package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.shaders.Program;
import com.mojang.logging.LogUtils;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.Yaml;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.Helper;

import java.util.List;
import java.util.Set;

public class ShaderCodeTransformation {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static enum ShaderType {
        vs, fs
    }
    
    private static boolean matches(ShaderType me, Program.Type type) {
        if (type == Program.Type.FRAGMENT) {
            return me == ShaderType.fs;
        }
        else if (type == Program.Type.VERTEX) {
            return me == ShaderType.vs;
        }
        return false;
    }
    
    // snakeyaml does not allow passing generic type
    // so use another wrapper type to make list generic type work
    public static class ConfigsObj {
        public List<Config> configs;
    }
    
    public static class TransformationEntry {
        public String comment;
        public String pattern;
        public String replacement;
    }
    
    public static class Config {
        public String comment;
        public ShaderType type;
        public Set<String> affectedShaders;
        public List<TransformationEntry> transformations;
        public boolean debugOutput;
    }
    
    private static List<Config> configs;
    
    public static void init() {
        // Idempotent: tolerate being called from multiple init sites (the
        // sable-fork IplVeilCompat path needs to force-init eagerly before
        // Veil's vanilla-shader compile pass starts, since IP's own
        // Minecraft.execute()-queued init runs *after* Veil has already
        // compiled all 58 vanilla shaders without our injection).
        if (configs != null) {
            return;
        }
        if (IPGlobal.enableClippingMechanism) {
            Yaml yaml = new Yaml();

            String yamlStr = McHelper.readTextResource(McHelper.newResourceLocation(
                "immersive_portals:shaders/shader_transformation.yaml"
            ));
            ConfigsObj configsObj = yaml.loadAs(yamlStr, ConfigsObj.class);

            configs = configsObj.configs;

            LOGGER.info("Loaded Shader Code Transformation");
        }
        else {
            LOGGER.info("Shader Transformation Disabled");
        }
    }
    
    public static String transform(Program.Type type, String shaderId, String inputCode) {
        if (configs == null) {
            LOGGER.info("Shader Transform Skipping {}", shaderId);
            return inputCode;
        }

        // IPL diagnostic: dump full shader source for any shader matching a watchlist
        // so we can see what attribute / uniform names it uses. Set via
        // -Dipl.sable.clip.dumpShaders=terrain_solid,terrain_cutout etc. (comma-separated).
        String dumpList = System.getProperty("ipl.sable.clip.dumpShaders", "");
        boolean shouldDump = false;
        if (!dumpList.isEmpty()) {
            for (String wanted : dumpList.split(",")) {
                if (wanted.trim().equals(shaderId)) {
                    shouldDump = true;
                    LOGGER.info("[IPL-SHADER-DUMP-INPUT] type={} id={}\n----- BEGIN INPUT -----\n{}\n----- END INPUT -----",
                        type, shaderId, inputCode);
                    break;
                }
            }
        }

        Config selected = getConfig(type, shaderId);

        if (selected == null) {
            if (shouldDump) {
                LOGGER.info("[IPL-SHADER-DUMP-NOMATCH] type={} id={} -- no matching config, source returned unchanged",
                    type, shaderId);
            }
            return inputCode;
        }

        String result = inputCode;

        for (TransformationEntry entry : selected.transformations) {
            String replacement = String.join("\n", entry.replacement);
            String before = result;
            result = result.replaceAll(entry.pattern, replacement);
            if (shouldDump) {
                boolean matched = !before.equals(result);
                LOGGER.info("[IPL-SHADER-DUMP-STEP] type={} id={} pattern matched={} (pattern={})",
                    type, shaderId, matched, entry.pattern.replace("\n", "\\n"));
            }
        }

        if (shouldDump) {
            // ALSO dump the post-transformation source so we can verify the uniform /
            // discard logic actually got injected. Critical when GL warnings show that
            // a uniform "could not be found" in the compiled program -- distinguishes
            // "transformation never ran" from "transformation ran but dead-code-stripped".
            LOGGER.info("[IPL-SHADER-DUMP-OUTPUT] type={} id={}\n----- BEGIN OUTPUT -----\n{}\n----- END OUTPUT -----",
                type, shaderId, result);
        }

        if (selected.debugOutput) {
            LOGGER.info("Shader Transformed {}\n{}", shaderId, result);
        }

        return result;
    }
    
    @Nullable
    private static Config getConfig(Program.Type type, String shaderId) {
        return configs.stream().filter(
            config -> matches(config.type, type) &&
                config.affectedShaders.contains(shaderId)
        ).findFirst().orElse(null);
    }
    
    public static boolean shouldAddUniform(String shaderName) {
        if (configs == null) {
            LOGGER.info("Shader Transform Skipping {} in shouldAddUniform", shaderName);
            return false;
        }
        
        return configs.stream().anyMatch(config -> config.affectedShaders.contains(shaderName));
    }
}
