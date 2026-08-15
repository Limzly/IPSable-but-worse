package ipl.sable.render;

import qouteall.imm_ptl.core.render.EntityShaderNames;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps GL program id → {@code ShaderInstance.getName()} so the {@code _glUseProgram}
 * hook can pick the correct coordinate-space form of
 * {@code iportal_ClippingEquation} to upload for the program being bound.
 *
 * <p><b>Why this is needed:</b> the GLSL injection differs by shader class.
 * Vanilla entity / particle / portal_area shaders inject
 * {@code dot((ModelViewMat * vec4(Position, 1.0)).xyz, eq)} which is in
 * <i>eye</i> space (per-entity {@code ModelViewMat} includes per-batch
 * translation+rotation on top of camera view). Terrain shaders and
 * Iris-rewritten {@code entities_*}/{@code terrain_*} shaders inject
 * {@code dot(Position + ChunkOffset, eq)} or
 * {@code dot(gbufferModelViewInverse * iris_ModelViewMat * (iris_Position + iris_ChunkOffset), eq)}
 * which are in <i>camera-relative world</i> space.
 *
 * <p>Writing the wrong-space equation gives a plane that clips <i>something</i>
 * but in the wrong location/orientation -- which is exactly the symptom we saw
 * on the chest: clip math runs, but the cull boundary doesn't line up with the
 * portal.
 *
 * <p>Populated by {@code IplShaderUniformProbeMixin} at
 * {@code ShaderInstance.updateLocations()} RETURN. Read by
 * {@code IplGlUseProgramProbeMixin} at {@code _glUseProgram} HEAD.
 */
public final class IplProgramRegistry {
    private IplProgramRegistry() {}

    private static final ConcurrentMap<Integer, Boolean> ENTITY_STYLE_BY_PROGRAM = new ConcurrentHashMap<>();
    private static final java.util.Set<String> VANILLA_SUBLEVEL_INPUT_SHADERS = java.util.Set.of(
        "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped",
        "rendertype_translucent"
    );
    private static final ConcurrentMap<Integer, Boolean> VANILLA_SUBLEVEL_INPUT_BY_PROGRAM =
        new ConcurrentHashMap<>();

    /**
     * Called from {@code IplShaderUniformProbeMixin} after a {@code ShaderInstance}
     * resolves uniform locations. Records whether the program backs an
     * entity-style shader (per {@link EntityShaderNames#IS_ENTITY_STYLE}).
     *
     * <p>If a program id is reused after a shader reload, the new
     * {@code updateLocations} call overwrites the prior entry -- so stale
     * registrations heal automatically.
     */
    public static void register(int programId, String shaderName) {
        if (programId == 0 || shaderName == null) return;
        ENTITY_STYLE_BY_PROGRAM.put(programId, EntityShaderNames.IS_ENTITY_STYLE.contains(shaderName));
        VANILLA_SUBLEVEL_INPUT_BY_PROGRAM.put(
            programId, VANILLA_SUBLEVEL_INPUT_SHADERS.contains(shaderName));
    }

    /**
     * Returns true if the program's shader is one whose GLSL injection works
     * in eye space (vanilla entity / particle / portal_area). Returns false
     * for unknown programs and for world-space shaders (terrain, Iris-rewritten,
     * vanilla rendertype_solid/cutout/translucent).
     *
     * <p>Defaulting unknowns to <i>false</i> (world-space) is safer because
     * (a) vanilla terrain dominates portal-through scene complexity and
     * (b) Iris-rewritten shaders -- which we won't see in our registry until
     * after their first {@code updateLocations} -- are world-space.
     */
    public static boolean isEntityStyleProgram(int programId) {
        Boolean v = ENTITY_STYLE_BY_PROGRAM.get(programId);
        return v != null && v;
    }

    /** Vanilla Sable chunks feed slot-1 plot-local, camera-relative vertices. */
    public static boolean usesVanillaSubLevelInputSpace(int programId) {
        Boolean v = VANILLA_SUBLEVEL_INPUT_BY_PROGRAM.get(programId);
        return v != null && v;
    }

    public static boolean isVanillaSubLevelInputShader(String shaderName) {
        return VANILLA_SUBLEVEL_INPUT_SHADERS.contains(shaderName);
    }

}
