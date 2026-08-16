# Changelog

This is a fork of IPSable for the Create Convoluted modpack. It's indev.

## Status legend

- **Confirmed** — tested in-game, behavior verified
- **Code-level** — code change based on investigation, not yet tested in-game
- **Untested** — fix may or may not work, needs in-game verification
- **Not fixed** — known issue, no fix attempted yet

## Currently confirmed working

- Game boots with IP + Sable + DH + Iris + Sodium + Veil + Flywheel all loaded together
- No crash on launch (the original IP+Sable collision is fixed upstream by IPSable)
- Portals render and you can walk through them
- Cross-portal physics (Sable sub-levels straddle portals) — upstream IPSable work
- Sound Physics mods no longer spam the log with raycast errors (`MixinBlockGetter`)
- DH's render-setup handler no longer throws 15,000+ exceptions per session (`IplDhOverrideInjectorMixin`)
- Auto-enable compatibility render mode when Iris + DH are both detected (`IPModEntryClient`)
- Lightmap always updates when switching dimensions for portal render (`MyGameRenderer`)

## Currently in the code, untested in-game

These are code-level changes from the latest investigation round. They're based on DH 3.2.0-b bytecode disassembly, Mixin source analysis, and RenderSystem API verification. They should fix the listed bugs in theory, but none have been confirmed in-game yet.

### Bug 1: Terrain disappears near portal

- **Status:** Code-level, untested
- **Root cause:** `FrontClipping.disableClipping()` not called after `popPortalLayer` in the compatibility renderer. Stale clip plane clips source terrain.
- **Fix:** Added defensive `FrontClipping.disableClipping()` call after `popPortalLayer()` in `IrisCompatibilityPortalRenderer.doRenderPortal`. Under Iris, `MixinLevelRenderer.onAfterRenderingLayer` may not fire if Iris bypasses the normal `renderSectionLayer` path.
- **File:** `src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatibilityPortalRenderer.java`

### Bug 2: Liquids and particles visible through terrain

- **Status:** Code-level, untested
- **Root cause:** The clip equation write hook had an early return that skipped the upload when clipping was not active. Shaders bound before the portal bracket opened never got the clip equation, so the clip test always passed and nothing got clipped.
- **Fix:** When not in a portal render and not in a sub-level bracket, now writes the no-clip sentinel `(0,0,0,1)` to the shader's `iportal_ClippingEquation` uniform before returning. This ensures stale equations from previous portal renders are cleared.
- **Also:** This fix was originally only applied to `IplProgramBindHook.onBind`. `IplGlUseProgramProbeMixin` had its own ~200-line inlined copy of the bind logic that was missed. Deduplicated so both bind hooks delegate to a single shared path. **This was a critical gap** — vanilla terrain shaders bound via `GlStateManager._glUseProgram` still had stale clip equations and leaked, despite the changelog claiming the bug was fixed.
- **Files:** `src/main/java/ipl/sable/render/IplProgramBindHook.java`, `src/main/java/ipl/sable/mixin/client/IplGlUseProgramProbeMixin.java`

### Bug 4: Shadow stripes

- **Status:** Code-level, untested
- **Root cause:** The clip equation is camera-relative. When the camera moves between shader binds, the equation is stale. The stale equation shifts the clip plane by however much the camera moved, creating bands of wrong lighting proportional to camera movement. Thickness changes because camera speed varies. Stripes are parallel to the portal plane, not to chunks.
- **Fix:** Added `FrontClipping.refreshClipEquationForCurrentCamera()` which re-computes BOTH the world-space equation (used by terrain shaders that do `dot(Position + ChunkOffset, eq)`) AND the eye-space equation (used by entity / particle / BE shaders that do `dot((ModelViewMat * Position).xyz, eq)`) using `RenderSystem.getModelViewMatrix()`. Called from `IplProgramBindHook.onBind` before uploading the equation, on every shader bind.
- **Also:** Same deduplication issue as Bug 2 — the fix was originally only applied to one of two bind hooks. Fixed.
- **Files:** `src/main/java/qouteall/imm_ptl/core/render/FrontClipping.java`, `src/main/java/ipl/sable/render/IplProgramBindHook.java`

### Bug 7: DH data collision

- **Status:** Code-level, untested. The previous "fix" was completely non-functional.
- **Root cause:** DH creates a `DhLevel` for the `ipl_sable:sublevels` hosting dimension, which pollutes DH's per-dim caches.
- **Previous attempt (BROKEN):** `@Pseudo` mixin on `AbstractDhLevel.<init>` with `cancellable = true`. Mixin refuses to apply any cancellable injection on a constructor (throws `InvalidInjectionException`), and `require = 0` silently swallowed the failure. The mixin NEVER applied. Bug 7's "fix" was completely non-functional from the moment it was committed until it was replaced.
- **Current fix:** `@Pseudo` mixin targeting `DhServerWorld.getOrLoadLevel` + `DhClientServerWorld.getOrLoadLevel` with `@Inject(HEAD, cancellable=true)` returning null when the `DhDimensionTracker` flag is set. Safe because `ServerApi.serverLevelLoadEvent` pops the return value without dereferencing it (verified by DH 3.2.0-b bytecode disassembly).
- **Threading:** Research confirmed the whole chain (`ServerLevel.<init>` → `LevelEvent.Load` → `ServerApi.serverLevelLoadEvent` → `getOrLoadLevel` → `new DhServerLevel`) runs synchronously on the server thread. The 1-second deadline in `DhDimensionTracker` is a safety net only — it never trips in practice.
- **Files:** `src/main/java/ipl/sable/mixin/IplDhSkipHostingDimensionMixin.java`, `src/main/java/ipl/sable/mixin/IplServerLevelDhTrackerMixin.java`, `src/main/java/ipl/sable/dh/DhDimensionTracker.java`

### Slot 2 (multi-straddle second cut) missing from one bind hook

- **Status:** Code-level, untested
- **Root cause:** `IplProgramBindHook.onBind` only wrote slot 1 (`ipl_subLevelClipEquation`), never slot 2 (`ipl_subLevelClipEquation[1]`). `IplGlUseProgramProbeMixin` wrote both. Programs bound via `ProgramManager.glUseProgram` (Iris entity/deferred/terrain shaders + Flywheel embeddings) missed the multi-straddle second cut.
- **Fix:** `IplProgramBindHook.onBind` now writes slot 2 in the active path, matching the old `IplGlUseProgramProbeMixin` behavior. The early-return path deliberately doesn't touch slot 1/2 because they're owned by Sable's sub-level pipeline and stale values are harmless outside a sub-level bracket (GL_CLIP_DISTANCE1/2 are disabled).
- **Effect:** Multi-straddle portals (portals inside portals) should clip correctly for all programs now.
- **File:** `src/main/java/ipl/sable/render/IplProgramBindHook.java`

### DH config first-launch silent no-op

- **Status:** Code-level, untested
- **Root cause:** On first launch, DH hasn't generated its `DistantHorizons.toml` config yet, so `patchConfig` returned silently. The patch only took effect on the second launch (after DH created the config), and even then only on the third launch (because DH reads config at startup).
- **Fix:** `patchConfig` now returns a boolean indicating success. `IPModEntryClient` schedules a retry task (every 5 seconds, up to 12 attempts = 60 seconds total) when the first call returns false. Once the config exists, the retry succeeds and the task removes itself.
- **Files:** `src/main/java/ipl/sable/dh/DhConfigPatch.java`, `src/main/java/qouteall/imm_ptl/core/platform_specific/IPModEntryClient.java`

### Smaller cleanups in the same pass

- `IplServerLevelDhTrackerMixin` now uses `SableSubLevelDimension.SUBLEVELS` constant instead of hardcoded string
- `DhDimensionTracker` documented as static volatile (was misleadingly called "thread-local" before)
- `IPL$SLOT1_FLIP` diagnostic moved to `IplProgramBindHook` so it applies uniformly to all bind paths
- `DhConfigPatch.addToCsv` no longer throws `StringIndexOutOfBoundsException` on a malformed single `"` character
- `enable_iris=false` dead config removed from `gradle.properties`
- Duplicate `IPL$LOC_CACHE` maps merged into one (was wasted memory across two bind hooks)

## Not fixed yet

### Bug 3: Portal visible through blocks with shaders

- **Status:** Not fixed
- **Root cause:** Iris depth buffer management in the compatibility renderer. The portal quad renders on top of terrain because depth clamp is enabled and Iris's depth buffer state is unknown.
- **Next step:** Investigate Iris depth buffer state before `drawPortalAreaWithFramebuffer`.

### Bug 5: Ghost terrain

- **Status:** Not fixed. Might be fixed by the Bug 4 fix (stale clip equation was the root cause), needs testing.
- **Root cause:** Consequence of Bug 4 — stale clip equation lets destination terrain leak through.

### Bug 6: Wrong water color

- **Status:** Not fixed
- **Root cause:** Biome color resolver not swapped during portal render. Water color comes from `BiomeColors.getAverageWaterColor()` which is dimension-specific. `MyGameRenderer` swaps level/renderer/camera but NOT `BlockColors` or the biome resolver.
- **Next step:** Swap `BlockColors` / biome resolver in `MyGameRenderer.switchAndRenderTheWorld` when entering portal content render.

## Tried and reverted (do not try again)

- `glClear(GL_DEPTH_BUFFER_BIT)` before portal content — didn't fix invisible terrain
- Deferred to main FB blit after each portal — didn't fix ghost blocks
- Removed `setPipeline(null)` — didn't fix black screen, caused portal-through-blocks
- `FrontClipping.ADJUSTMENT` 0.01 to 0.1 — didn't fix shadow stripes

## Upstream history

Fork builds on top of upstream IPSable (commit `f5e0471`). That commit is tagged `fork-baseline-v0.5.0` in this repo's history so it's easy to diff against the upstream baseline.
