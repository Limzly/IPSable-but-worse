# Changelog

This is a fork of IPSable for the Create Convoluted modpack. It's indev.

## 0.6.8 (indev)

### Code audit fixes

Found 15 issues during a full code audit. All fixed.

**Bug A: IplGlUseProgramProbeMixin had its own inlined copy of the bind logic**
- The Bug 2 (no-clip sentinel) and Bug 4 (camera-relative equation refresh) fixes were only applied to `IplProgramBindHook.onBind`, not the inlined copy in `IplGlUseProgramProbeMixin`. So vanilla terrain shaders bound via `GlStateManager._glUseProgram` still had stale clip equations.
- Now `IplGlUseProgramProbeMixin` is a thin delegator that calls `IplProgramBindHook.onBind(program)`. Single source of truth for the bind logic.
- Effect: Bug 2 + Bug 4 fixes now apply to all program bind paths.

**Bug B: IplProgramBindHook was missing slot 2 (ipl_subLevelClipEquation[1]) write**
- Only `IplGlUseProgramProbeMixin` wrote slot 2 (the multi-straddle second cut). Programs bound via `ProgramManager.glUseProgram` (Iris entity/deferred/terrain shaders + Flywheel embeddings) missed the second cut.
- Now `IplProgramBindHook.onBind` writes slot 2 in the active path (when `haveActive || inPortalRender || inSubLevelBracket`), matching the old `IplGlUseProgramProbeMixin` behavior. The early-return path (no portal, no sub-level bracket) deliberately does NOT touch slot 1/2 because they're owned by Sable's sub-level pipeline and stale values are harmless outside a sub-level bracket (GL_CLIP_DISTANCE1/2 are disabled).
- Effect: multi-straddle portals (portals inside portals) clip correctly for all programs.

**Bug C: refreshClipEquationForCurrentCamera only refreshed the world-space equation**
- The eye-space equation (`activeClipPlaneAfterModelView`) stayed stale. Entity / particle / block-entity shaders that do `dot((ModelViewMat * Position).xyz, eq)` got stale clip equations.
- Now also refreshes the eye-space equation using `RenderSystem.getModelViewMatrix()`.
- Effect: entity / particle / block-entity shadow stripes fixed.

**Bug D: IplDhSkipHostingDimensionMixin never applied (constructor cancellation anti-pattern)**
- Mixin refuses to apply any `cancellable = true` injection on a `<init>` method (throws `InvalidInjectionException`). The `require = 0` silently swallowed the failure, so the mixin never applied. Bug 7's "fix" was completely non-functional.
- Even if it HAD applied, cancelling at `<init>` HEAD only skips the body of that constructor — `super()` already returned and the subclass `<init>` continues, leaving the DhLevel in a half-initialized state that NPEs on every chunk update.
- Replaced: now targets `DhServerWorld.getOrLoadLevel` + `DhClientServerWorld.getOrLoadLevel` with `@Inject(HEAD, cancellable=true)` returning null when the DhDimensionTracker flag is set. Safe because `ServerApi.serverLevelLoadEvent` pops the return value without dereferencing it (verified by DH 3.2.0-b bytecode disassembly).
- Effect: Bug 7 actually works now.

**Bug E: IplServerLevelDhTrackerMixin comment was wrong twice**
- Said "one server tick" but timeout is 1000ms (20 ticks).
- Said "DhDimensionTracker's own tick logic" but DhDimensionTracker has no tick logic — it uses a deadline-based lazy clear in `isHostingDimensionLoading()`.
- Comments corrected.

**Bug F: DhDimensionTracker race condition (theoretical)**
- Research (DH 3.2.0-b bytecode disassembly) confirmed the whole chain `ServerLevel.<init>` → `LevelEvent.Load` → `ServerApi.serverLevelLoadEvent` → `getOrLoadLevel` → `new DhServerLevel` runs synchronously on the server thread. The flag is consumed within microseconds of being set.
- The 1-second deadline in DhDimensionTracker is now documented as a safety net only — it never trips in practice.

**Bug G: Hardcoded dimension ID string**
- `IplServerLevelDhTrackerMixin` used `"ipl_sable:sublevels"` string literal. Now uses the canonical `SableSubLevelDimension.SUBLEVELS` constant.
- Effect: stays in sync if the dimension ID ever changes.

**Bug H: Version mismatch**
- `gradle.properties` had `fork_version=0.6.7` but `HANDOFF.md` and `BUILD.md` said 0.6.0. Bumped to 0.6.8 and synced all references.

**Bug I: changelog said "DhDimensionTracker (thread-local flag)"**
- DhDimensionTracker is NOT thread-local — it's a `static volatile` field on a singleton. Fixed.

**Bug J: DhConfigPatch first-launch silent no-op**
- On first launch, DH hasn't generated its config yet, so `patchConfig` returned silently. The patch only took effect on the SECOND launch (after DH created the config), and even then only on the THIRD launch (because DH reads config at startup).
- Now `patchConfig` returns a `boolean` indicating success. `IPModEntryClient` schedules a retry task (every 5 seconds, up to 12 attempts = 60 seconds total) when the first call returns false. Once the config exists, the retry succeeds and the task removes itself.
- Effect: first-launch now works correctly without manual relaunching.

**Bug K: Dead setHostingDimensionLoading(false) overloads**
- Both overloads accepted `false` but no caller ever passed `false`. The flag only ever gets cleared by the deadline check in `isHostingDimensionLoading()`.
- Kept the overloads (they're API-complete for future use), but added documentation noting that the deadline is the primary clear mechanism.

**Bug L: enable_iris=false dead config**
- `gradle.properties` had `enable_iris=false` but it was only referenced in `build.gradle.old` (not the active build script). Removed.

**Bug M: Duplicate IPL$LOC_CACHE**
- `IplProgramBindHook.IPL$LOC_CACHE` and `IplGlUseProgramProbeMixin.IPL$LOC_CACHE` both cached uniform locations per program ID. Same data, twice the memory.
- After Bug A fix (deduplication), only `IplProgramBindHook.IPL$LOC_CACHE` remains.

**Bug N: IPL$SLOT1_FLIP diagnostic was inconsistent**
- `IplProgramBindHook` had `IPL$SLOT1_FLIP`; `IplGlUseProgramProbeMixin` did NOT. So `-Dipl.sable.clip.slot1Flip=true` only flipped the equation for one bind path.
- After Bug A fix (deduplication), `IPL$SLOT1_FLIP` lives in `IplProgramBindHook` and applies to all bind paths uniformly. Also extended to cover slot 2 (the multi-straddle second cut) so the diagnostic is complete.

**Bug O: DhConfigPatch.addToCsv edge case**
- A single `"` character would match `startsWith("\"") && endsWith("\"")` and then throw `StringIndexOutOfBoundsException` in `substring`.
- Added `value.length() >= 2` guard.

## 0.6.7 (indev)

### Rendering fixes (from investigation doc)

**Bug 1: Terrain disappears near portal**
- Root cause: FrontClipping.disableClipping() not called after popPortalLayer in the compatibility renderer. Stale clip plane clips source terrain.
- Fix: added defensive FrontClipping.disableClipping() call after popPortalLayer() in IrisCompatibilityPortalRenderer.doRenderPortal.

**Bug 2: Liquids and particles visible through terrain**
- Root cause: IplProgramBindHook.onBind had an early return that skipped the clip equation upload when clipping was not active. Shaders bound before the portal bracket opened never got the clip equation, so the clip test always passed and nothing got clipped.
- Fix: when not in a portal render and not in a sub-level bracket, now writes the no-clip sentinel (0,0,0,1) to the shader's iportal_ClippingEquation uniform before returning. This ensures stale equations from previous portal renders are cleared.

**Bug 4: Shadow stripes (revised)**
- Root cause: the clip equation is camera-relative. When the camera moves between shader binds, the equation is stale. The stale equation shifts the clip plane by however much the camera moved, creating bands of wrong lighting proportional to camera movement. Thickness changes because camera speed varies. Stripes are parallel to the portal plane, not to chunks.
- Fix: added FrontClipping.refreshClipEquationForCurrentCamera() which re-computes the world-space clip equation using the current camera position. Called from IplProgramBindHook.onBind before uploading the equation. This ensures every shader bind gets an up-to-date equation.

**Bug 7: DH data collision (Phase 4)**
- Root cause: DH creates a DhLevel for the ipl_sable:sublevels hosting dimension. The original investigation claimed this was due to LocalSaveStructure path accumulation, but DH 3.2.0-b bytecode disassembly revealed LocalSaveStructure uses a per-dimension ConcurrentHashMap cache, not path accumulation. The actual issue is more nuanced (DH's per-dim caches get polluted when the hosting dim exists at all).
- Fix: @Pseudo mixin on DhServerWorld.getOrLoadLevel + DhClientServerWorld.getOrLoadLevel that cancels the call when DhDimensionTracker flag is set. Returns null - safe because ServerApi.serverLevelLoadEvent pops the return value without dereferencing it.
- Previous attempt: @Pseudo mixin on AbstractDhLevel.<init> with cancellable=true. Mixin refuses to apply cancellable injections on constructors (InvalidInjectionException), silently swallowed by require=0. The mixin never applied. Replaced.

### What still needs investigation

- Bug 3: Portal visible through blocks with shaders (Iris depth buffer management)
- Bug 5: Ghost terrain (should be fixed by Bug 4 + Bug C, needs testing)
- Bug 6: Wrong water color (biome color resolver not swapped during portal render)

### Previous fixes (kept)

- Sound Physics raycast log fix (MixinBlockGetter)
- Auto-enable compatibility render mode when Iris + DH detected (IPModEntryClient)
- DH OverrideInjector mixin (stops 15K errors/session)
- DH config patch (ignoredDimensionCsv) - now with retry mechanism for first-launch
- Lightmap always updated (MyGameRenderer)

## Upstream history

This fork builds on top of upstream IPSable v0.5.0 (commit f5e0471).
