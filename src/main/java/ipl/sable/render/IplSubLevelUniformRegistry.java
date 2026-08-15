package ipl.sable.render;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * Registry of every GL program known to carry the
 * {@code ipl_subLevelClipEquation} uniform, with the resolved uniform
 * location for each.
 *
 * <p><b>Why this exists:</b> The original slot-1 propagation path
 * (write at {@code SubLevelClipUniformPatcher.patchForSubLevel} +
 * piggyback {@code _glUseProgram} HEAD writes for subsequent shader
 * binds) doesn't cover programs that were bound BEFORE a sub-level
 * bracket started. In GL, {@code glUseProgram} only fires when the
 * binding actually changes; programs that stay bound across many draws
 * (like batched terrain or block-entity programs that Iris reuses)
 * never re-trigger the upload, so their slot-1 uniform stays at its
 * initial {@code (0,0,0,1)} default → {@code dot(pos, 0) + 1 > 0}
 * always passes → cog meshes leak through portals.
 *
 * <p>Diagnosed via RenderDoc EID comparison: at EID 2260 (airship,
 * working) program 5058 had {@code slot-1 = (1.0, 0.0, 0.0, -2.022906)}
 * matching the patcher's logged write; at EID 2579 (cog, leaking)
 * program 5073 — a different Iris-rewritten {@code moving_block}-style
 * program — had {@code slot-1 = (0,0,0,1)} because the patcher's
 * upload only touched whatever shader was bound at the moment it ran.
 *
 * <p><b>How it's wired:</b> {@code IplGlUseProgramProbeMixin}
 * populates this registry on first {@code _glUseProgram} per program
 * (the same caching path it already does for {@code IPL$LOC_CACHE}).
 * {@code SubLevelClipUniformPatcher.patchForSubLevel} iterates the
 * registry and writes the new equation to every entry via
 * {@code glProgramUniform4f}, which targets a program regardless of
 * whether it's the currently-bound program. {@code clearAndUpload}
 * resets all entries to the no-clip identity.
 *
 * <p>Thread-safety: all writes happen on the render thread, but using
 * a {@link ConcurrentMap} keeps the contract explicit and the iteration
 * weakly-consistent without throwing {@code ConcurrentModificationException}
 * if a new program registers mid-iteration.
 */
public final class IplSubLevelUniformRegistry {

    /**
     * Map: GL program id -> resolved locations {@code [element0, element1]} of the
     * {@code ipl_subLevelClipEquation[2]} array uniform. Element 1 may be -1 on
     * drivers/programs where the compiler eliminated it; uploads skip it then.
     */
    private static final ConcurrentMap<Integer, int[]> SUB_LEVEL_LOC_BY_PROGRAM =
        new ConcurrentHashMap<>();

    private IplSubLevelUniformRegistry() {}

    /**
     * Register a program's slot-1 locations. Idempotent; safe to call repeatedly.
     * Negative element-0 locations are ignored (the program doesn't carry our
     * uniform, e.g., GUI / blit shaders).
     */
    public static void register(int programId, int subLevelLoc, int subLevelLoc2) {
        if (subLevelLoc >= 0) {
            SUB_LEVEL_LOC_BY_PROGRAM.put(programId, new int[]{subLevelLoc, subLevelLoc2});
        }
    }

    /**
     * Drop a program from the registry (e.g., when the GL program is
     * deleted). Currently unused -- we never delete from the cache --
     * but exposed for future cleanup hooks.
     */
    public static void unregister(int programId) {
        SUB_LEVEL_LOC_BY_PROGRAM.remove(programId);
    }

    /**
     * Invoke {@code action} once per (programId, [loc0, loc1]) currently
     * registered. Iteration is weakly consistent: concurrent registers
     * mid-iteration won't throw, but may or may not be observed.
     */
    public static void forEach(BiConsumer<Integer, int[]> action) {
        SUB_LEVEL_LOC_BY_PROGRAM.forEach(action);
    }

    /** Number of programs currently tracked. For diagnostics. */
    public static int size() {
        return SUB_LEVEL_LOC_BY_PROGRAM.size();
    }
}
