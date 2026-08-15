# Refactor Spec: Dim-Agnostic Sable Sub-Levels

> Status: spec locked, ready for phase 0
> Target branch: `ipl-sable-compat`
> Authors: drafted collaboratively, 2026-05-18
> Estimated scope: 12-18 working days (phases 0-8), 8-12 days (phase 9 post-release)

## Path-prefix convention

Throughout this document:
- `IP-Sable/...` = this fork (`ImmersivePortals-Sable/`)
- `sable-src/...` = the local Sable checkout (`PortalsSable/sable-src/`)

---

## 1. Goals

- One datapack-registered `sable_sublevels` dimension hosts every plot's chunks
- `SubLevel.level` field splits into `parentLevel` (where airship visually appears) + `hostingLevel` (always `sable_sublevels`)
- Sable's existing volumetric renderer rewired to iterate per-camera-dim, not per-parentLevel
- Slot-0 IP clipping (already shipped on this branch) handles cross-portal transit cropping
- Slot-1 (`ipl_subLevelClipEquation`) plumbing deleted entirely
- Cross-dimension airship transit becomes a non-event for the data model

## 2. Non-goals

- Apparent gravity for standing entities (moved to phase 9a)
- Parent-aware biome tinting on sub-level vertices (moved to phase 9b)
- Runtime dimension synthesis via `MinecraftServer.addLevel` — datapack registration is sufficient
- Backwards-compat with stock Sable jar — this fork is the integration target
- Mob spawning, weather, day-night cycle in `sable_sublevels` (configured off)
- Performance tuning of cross-dim rendering — defer to phase 9c if it becomes a real problem

## 3. Background: how Sable models sub-levels today

A `SubLevel` is **not** a real `Level`. Each `SubLevel` owns a `LevelPlot` whose chunks are physically stored **inside its parent `ServerLevel` at a far offset** (`DEFAULT_ORIGIN = 10000`). Access is mediated by `EmbeddedPlotLevelAccessor`, which translates `(pos)` → `parentLevel.getBlockState(pos.offset(plotCenter))`. The `Pose3d` on `SubLevel` is purely a visual/physics transform — blocks physically live in parent coords; rendering and collision apply the pose on top.

The structural consequence: an airship's chunks are anchored to one specific parent dim. Crossing an IP portal is conceptually impossible because the embedded chunks can't be in two parent dims at once.

## 4. Target architecture

- All plot chunks live in a single dedicated dimension, `sable_sublevels`, registered via datapack
- Each `SubLevel` knows both its `parentLevel` (the dim where it currently visually appears) and its `hostingLevel` (always `sable_sublevels`)
- The `Pose3d` transform now maps `hostingLevel`-local coords → `parentLevel`-world coords
- Sable's renderer iterates **all** sub-levels per camera dim, computing a per-sub-level visible region in the current camera's dim; non-empty regions render with IP slot-0 clipping handling any portal cropping
- Cross-portal transit becomes: airship chunks stay put; `parentLevel` reference flips; during the straddle, the airship renders in **both** parent dims (each cropped by IP slot-0)

## 5. New artifacts

### 5.1 Datapack (sable namespace, ~30 LOC total)

- `data/sable/dimension_type/sublevels.json` — void-style dim type, fixed time 6000, no spawn, ambient light 1.0, height 384, min_y −64
- `data/sable/dimension/sublevels.json` — references the type above, uses an empty/void chunk generator

### 5.2 New Java classes

- `SableSubLevelDimension` — `ResourceKey<Level> SUBLEVELS` constant + `getSableSubLevels(MinecraftServer)` accessor
- `SableSubLevelChunkLoader` — adds `TicketType.UNKNOWN`-level chunk tickets for each active plot's chunk range; hooked into `SubLevelContainer.tick()`
- `SableSubLevelMigrator` — one-shot per-save migration from parent-dim plot chunks to `sable_sublevels`; idempotent via a version flag on `LevelStorageSource`

## 6. Data model changes

### 6.1 `sable-src/.../SubLevel.java:27, 32` — field split

| Before | After |
|---|---|
| `Level level` | `Level parentLevel` + `Level hostingLevel` |
| `getLevel()` | `getParentLevel()` / `getHostingLevel()`; old name deprecated |
| `Pose3d pose` | unchanged — now transforms `hostingLevel`-local coords → `parentLevel`-world coords |
| `globalBoundingBox` (`SubLevel.java:134-135`) | unchanged |

Every reader of `getLevel()` needs to be tagged as one or the other:

- **Wants `hostingLevel`**: chunk access, block reads, BE iteration, save data scope
- **Wants `parentLevel`**: pose-transform origin, networking dim ref, IP portal lookup, player presence

## 7. Server-side touchpoints

| File:line (sable-src) | Today | After |
|---|---|---|
| `.../api/sublevel/SubLevelContainer.java:230` — `allocateNewSubLevel(pose)` | Called on `parentLevel.getContainer()` | Called on `sableSubLevels.getContainer()`. Caller passes `parentLevel` separately. ~5 LOC per call site |
| `.../sublevel/ServerLevelPlot.java:226-228` — `addChunkHolder` | `cache.chunkMap.updatingChunkMap.put(...)` on parent | Same code; `cache` now `sable_sublevels`'s via field-split propagation. ~1 LOC change |
| `.../sublevel/EmbeddedPlotLevelAccessor.java:78` — constructor | `this.level = plot.getSubLevel().getLevel()` | `this.level = plot.getSubLevel().getHostingLevel()` |
| `.../sublevel/EmbeddedPlotLevelAccessor.java:100, 104, 110, 114-121, 130, 258` | Block/entity offset reads | Unchanged; field redirect cascades through |
| `.../sublevel/mixin/LevelsMixin.java:32, 45-47` — container creation | Per-Level mixin | Unchanged. `sable_sublevels` automatically gets a container |
| `.../api/sublevel/SubLevelContainer.java:141` — `tick()` | Iterates `allSubLevels`, ticks internal state | Add: invoke `SableSubLevelChunkLoader.refreshTickets(allSubLevels)` |
| Sub-level factory callers (commands, `SubLevelAssemblyHelper`, schematic placement) | Each calls `someParentLevel.getContainer().allocateNewSubLevel(...)` | Route through `SableSubLevelDimension.getSableSubLevels(server).getContainer().allocateNewSubLevel(...)` |

### 7.1 Tick driver — not a refactor, just a redirect

The initial recon flagged the tick-driver redirect as a 50-100 LOC blocker. It isn't. Plot chunks tick because they're registered into a `ServerChunkCache.chunkMap.updatingChunkMap`. Change which `ServerChunkCache` they're registered into (parent → `sable_sublevels`) and vanilla's tick loop does the work. `sable_sublevels` ticks on the server's normal level-tick rotation; nothing custom needed beyond the chunk-load tickets above.

## 8. Client-side touchpoints

| File:line (sable-src) | Today | After |
|---|---|---|
| `.../mixin/sublevel_render/impl/vanilla/LevelRendererMixin.java:52` — sublevel gather | `((SubLevelContainerHolder)this.level).sable$getPlotContainer().getAllSubLevels()` | `sableSubLevels.sable$getPlotContainer().getAllSubLevels()` — drop the implicit `this.level == parentLevel` filter |
| `.../sublevel/render/dispatcher/VanillaSubLevelRenderDispatcher.java:148` — iteration | `for (ClientSubLevel sublevel : sublevels)` | Same, but `sublevels` is now universe-wide. Filter per camera-dim via new visible-region check |
| `.../sublevel/render/vanilla/VanillaChunkedSubLevelRenderData.java:79` — constructor | Takes `Minecraft.getInstance().levelRenderer.getSectionRenderDispatcher()` | Takes `ClientWorldLoader.getWorldRenderer(SableSubLevelDimension.SUBLEVELS).getSectionRenderDispatcher()` |
| New: per-camera-dim visible region | n/a | For each sub-level: compute `pose.transform(plotBounds)` in `parentLevel` space, intersect with current camera dim, return non-empty region or null |
| `.../sublevel/render/dispatcher/VanillaSubLevelRenderDispatcher.java:228-252` — BE rendering | Already pose-transforms BE positions | Unchanged; same transformation matrix logic on a wider sublevel list |
| `.../mixin/CameraMixin.java:89-104`, `.../mixin/EntityMixin.java:97-106` | Already orientation-aware | Unchanged — apparent gravity is phase 9a |

### 8.1 The new visible-region check

```
for subLevel in sableSubLevels.allSubLevels:
    boundsInParent = pose.transform(subLevel.plot.bounds)
    if currentRenderDim == subLevel.parentLevel:
        # Direct render: IP slot-0 clip stack handles any portal cropping
        renderRegion = boundsInParent ∩ cameraFrustum
        if !renderRegion.empty: emit(subLevel)
    elif PortalRendering.isRendering() and PortalRendering.activeTargetDim == subLevel.parentLevel:
        # Inside an IP portal recursion targeting this airship's parent
        # IP's recursive slot-0 stack crops automatically
        renderRegion = boundsInParent ∩ cameraFrustum
        if !renderRegion.empty: emit(subLevel)
```

The IP slot-0 stack we already shipped on `ipl-sable-compat` does the GPU-side cropping. The visible-region check is purely a frustum + dim filter — no shader work.

### 8.2 Per-frame atmospheric state vs per-vertex baked state

Two distinct categories that confused the design discussion initially:

| Category | Source | After refactor |
|---|---|---|
| Per-frame uniforms (fog, sky color, time-of-day brightness, cloud color, `LightTexture`, Iris dim folder) | Camera dim's render pass, set by outer `LevelRenderer` before Sable's sub-level draw | ✅ Inherits camera dim's state automatically — nothing to thread through |
| Baked skylight values | Owning Level at section compile | `sable_sublevels` (full sky); modulated at draw by camera dim's `LightTexture` → day/night/nether glow all work correctly |
| Baked block light values | Owning Level at section compile | `sable_sublevels` — captures airship's own torches correctly |
| Baked biome tints (grass/leaf/water color) | Owning Level at section compile | `sable_sublevels`'s single biome (controlled default). Today: random biome at offset. Comparable approximation — phase 9b improves over both |

IP's [`MyRenderHelper.java:421`](../ImmersivePortals-Sable/src/main/java/qouteall/imm_ptl/core/render/MyRenderHelper.java:421) proves the multi-dispatcher pattern works: many `SectionRenderDispatcher` instances run in parallel without `withSwitchedWorld` swapping. The dispatcher's job is "give me baked geometry for these chunks," not "drive the frame's atmospheric state."

## 9. Networking changes

| File:line (sable-src) | Today | After |
|---|---|---|
| `.../network/packets/tcp/ClientboundStartTrackingSubLevelPacket.java:26` — record | `(plotCoordinate, subLevelID, lastPose, pose, bounds, name, gameTick)` | Add `ResourceKey<Level> parentLevel`. Client constructs `ClientSubLevel` with `hostingLevel = SableSubLevelDimension.SUBLEVELS` (hardcoded), `parentLevel` from packet |
| `.../network/packets/tcp/ClientboundStartTrackingSubLevelPacket.java:34` — encoder | Writes 6 fields | Writes 7 fields; `ResourceKey` as namespaced string |
| Handler (line 69-74, 82) | `new ClientSubLevel(level, ...)` | `new ClientSubLevel(parentLevel, hostingLevel, ...)` |
| `ClientboundFinalizeSubLevelPacket` | Triggers render-data creation | Triggers `VanillaChunkedSubLevelRenderData` construction with `sable_sublevels`'s section dispatcher |
| New: `ClientboundUpdateSubLevelParentPacket` | n/a | Sent during cross-portal transit when `parentLevel` flips; client updates without full re-track |

## 10. Persistence + migration

### 10.1 Region files

| Location | Today | After |
|---|---|---|
| Plot chunks | `world/<parentDim>/region/r.X.Z.mca` at offset (10000+, ?) | `world/sable/sublevels/region/r.X.Z.mca` |

### 10.2 SavedData

| File:line (sable-src) | Today | After |
|---|---|---|
| `.../sublevel/storage/SubLevelOccupancySavedData.java:15, 24` | Per-Level via `level.getChunkSource().getDataStorage()` | Single instance on `sable_sublevels.getChunkSource().getDataStorage()`. Migrator merges per-parent files |
| `.../sublevel/tracking_points/SubLevelTrackingPointSavedData.java:35, 45` | Per-Level via `level.getDataStorage()` | **Investigate during phase 1** — tracking points may need to stay per-parent for sensible behavior |

### 10.3 Migration procedure

`SableSubLevelMigrator.migrate(MinecraftServer)`:
1. For each loaded parent dim, read `SubLevelOccupancySavedData` (the BitSet)
2. For each occupied slot, compute its chunk range at the parent's `DEFAULT_ORIGIN = 10000` offset
3. Copy each chunk's NBT from parent's region file to `sable_sublevels`'s region file (atomic per-chunk)
4. Update the parent's `SubLevelOccupancySavedData` BitSet to empty
5. Write a version flag to `world/sable/migration_done.flag`
6. Idempotent: skip if flag present

## 11. IP-Sable bridge changes (the deletion dividend)

### 11.1 Delete entirely

| File (IP-Sable) | Reason |
|---|---|
| `src/main/java/ipl/sable/render/SubLevelClipUniformPatcher.java` | Slot-1 unused once sub-levels live in own dim |
| `src/main/java/ipl/sable/mixin/client/IplShaderClipMirrorMixin.java` | ↑ |
| `src/main/java/ipl/sable/mixin/client/IplFrontClippingStateMirrorMixin.java` | ↑ |
| `src/main/java/ipl/sable/mixin/client/SableSubLevelBlockEntityClipMixin.java` | Replaced by per-camera-dim visible region |
| `src/main/java/ipl/sable/render/SourceClipPortalFinder.java` | No "source clip portal" concept post-refactor |
| `src/main/java/ipl/sable/mixin/client/SableSourceClipMixin.java` | ↑ |
| `src/main/java/ipl/sable/mixin/client/SableSourceClipSodiumMixin.java` | ↑ |
| `src/main/java/ipl/sable/duck/IplSubLevelClipShader.java` | Slot-1 uniform duck-typing |

### 11.2 Modify

| File (IP-Sable) | Change |
|---|---|
| `src/main/resources/assets/immersive_portals/shaders/shader_transformation.yaml` | Remove `ipl_subLevelClipEquation` declarations and `gl_ClipDistance[1]` writes from every entry. Keep `iportal_ClippingEquation` / `gl_ClipDistance[0]` |
| `src/main/java/ipl/sable/mixin/client/IplShaderInstanceClipMixin.java` | Delete entirely (only registered slot-1 Uniform) |
| `src/main/java/ipl/sable/mixin/client/IplGlUseProgramProbeMixin.java` lines 229-243 | Delete slot-1 write block. Keep slot-0 write |
| `src/main/resources/ipl_sable.mixins.json` | Remove the listed deleted mixin entries |

### 11.3 Keep (slot-0 stays load-bearing for transit)

- IP-side: `MixinRenderSystem_Clipping`, `FrontClipping`, `ViewAreaRenderer`, `ShaderCodeTransformation`
- IP-side helpers: `IplClipEquationCache`, `EntityShaderNames`
- Veil/Flywheel: `IplVeilCompat`, `IplVeilShaderPreProcessor`, `IplFlywheelCompilationMixin`
- Probes: `IplProgramRegistry`, `IplShaderUniformProbeMixin` (delete-after-stabilization candidate)
- Slot-0 half of `IplGlUseProgramProbeMixin`

## 12. Phased plan

| # | Phase | Days | Exit criteria |
|---|---|---|---|
| 0 | Datapack + `SableSubLevelDimension` | ½ | `/forge dimensions` lists `sable:sublevels`; server boots cleanly |
| 1 | `SubLevel.level` field split | 1 | Compiles; `parentLevel == hostingLevel` everywhere; no behavior change |
| 2 | Plot relocation | 2-3 | New airship spawns; chunks land in `sable_sublevels`; BEs tick; existing airships invisible (expected — fixed by phase 7) |
| 3 | Chunk-load tickets | ½ | Plot chunks stay loaded; `/forge tps` for `sable:sublevels` non-zero |
| 4 | Renderer rewire + visible region | 3-5 | New-spawned airships render in their parent dim; chest/cog test passes |
| 5 | Physics validation | 1 | Player walks on airship deck; collision identical to pre-refactor |
| 6 | Cross-portal transit | 2-4 | Airship crosses OW→nether portal; both halves render during straddle; `parentLevel` flips at midpoint |
| 7 | Migration | 1 | Existing world loads; existing airships at their pre-refactor positions |
| 8 | Slot-1 deletion | 1 | Build clean; visual regression test passes |

**Realistic total: 12-18 working days.**

## 13. Resolved architectural questions

These were the load-bearing unknowns at spec-time. All resolved before phase 0.

| # | Question | Resolution |
|---|---|---|
| 1 | Per-dim `SectionRenderDispatcher` availability | ✅ Confirmed via [`ClientWorldLoader.getWorldRenderer(dim).getSectionRenderDispatcher()`](src/main/java/qouteall/imm_ptl/core/ClientWorldLoader.java:394). Pattern used by [`MyGameRenderer.java:132`](src/main/java/qouteall/imm_ptl/core/render/MyGameRenderer.java:132) and [`MyRenderHelper.java:421`](src/main/java/qouteall/imm_ptl/core/render/MyRenderHelper.java:421). No shim required |
| 2 | `SubLevelContainer` per-Level vs per-SubLevel | ✅ Per-Level; auto-instantiated via [`LevelsMixin.java:32`](`sable-src/.../LevelsMixin.java:32`). `sable_sublevels` gets one for free |
| 3 | Per-frame atmospheric state (fog, sky, LightTexture) | ✅ Inherits camera dim automatically — Sable's renderer hook runs inside the outer LevelRenderer pass which has already set parent's uniforms |
| 4 | Per-vertex baked state (skylight, biome tints) | ⚠️ Comparable to today; biome accuracy is a phase 9b enhancement, not a phase 4 blocker |
| 5 | `SubLevelTrackingPointSavedData` cross-dim semantics | ⏳ Investigate during phase 1; tracking points may need to remain per-parent |

## 14. Acceptance test

1. Build a Create contraption with cogs, chests, animated BEs on an airship
2. Pilot the airship across an IP nether portal at low speed
3. **From overworld side**: only the OW-portion is visible, clipped at the portal plane
4. **From nether side via portal**: only the NE-portion visible, clipped at the portal plane
5. Once fully crossed: airship visible only in nether; `parentLevel` reads as nether
6. Player walks around inside the airship during transit: collision correct on both sides
7. Save world; reload; airship in correct position with correct `parentLevel`
8. Inspect `world/sable/sublevels/region/` — sub-level chunks present; `world/overworld/region/` no longer holds plot chunks

## 15. Phase 9 — Post-release enhancements

### 15.1 Apparent gravity for standing entities (~1-2 weeks)

Bring gravity orientation, input transform, and physics-frame coupling up to par with the existing camera + collision infrastructure. Sable already has 60-70% of this stack (camera up-vector, OBB collision, partial input transform); the missing pieces are:

| Touchpoint (sable-src) | Change |
|---|---|
| `.../api/sublevel/util/EntitySubLevelUtil.java:92-94` — `getCustomEntityOrientation()` | Resolve to tracking sub-level's orientation for standing entities (not just riding/sleeping) |
| `.../sublevel/physics/SubLevelPhysicsSystem.java:236-277` — `tickPipelinePhysics()` | Per-body gravity rotation via Rapier impulse application each substep |
| `.../sublevel/physics/DimensionPhysics.java:16` — hardcoded `Vector3f(0, -11, 0)` | Rotated by tracking sub-level orientation before applying |
| `LivingEntity.aiStep` / `Entity.applyGravity` (new mixin) | Rotate gravity vector for non-Rapier entities (player, vanilla mobs) |

### 15.2 Parent-aware biome tinting (~3-4 days)

Sub-level leaves/grass/water tint correctly per parent dim's biome at the airship's apparent position.

| Touchpoint | Change |
|---|---|
| New: `ParentAwareRenderRegionCache extends RenderRegionCache` | Override `getBiome(pos)` to sample from `subLevel.parentLevel` at pose-transformed position |
| `sable-src/.../VanillaChunkedSubLevelRenderData.java:79` compile path | Inject the new cache type |
| New: recompile trigger | On `parentLevel` flip during transit, or when airship pose enters a different biome region in parent |

### 15.3 Optional: parent-dim block light contribution

Stretch goal — airship deck illuminated by nearby parent-dim torches/lava/etc. Today's chunks-at-offset don't do this; refactor doesn't either. Listed for completeness only. Would require cross-dim lightmap sampling at section compile. Defer unless players ask for it.

## 16. What gets simpler permanently

Beyond the deletion dividend, the refactor produces lasting architectural simplifications:

- **Cross-dim airship transit**: from architecturally-impossible to free
- **Sub-level rendering across IP portals**: from "Sable-specific clip-plane plumbing" to "standard IP slot-0"
- **Shader pack compatibility surface**: one clip pipeline instead of two (no more "this mod's new shader needs slot-0 AND slot-1 coverage")
- **Recursive sub-levels**: sub-levels-of-sub-levels = portals-into-sublevels-of-sublevels — IP's recursive portal stack handles depth automatically
- **Multi-mod compatibility**: shader-aware mods (Veil, Iris, Sodium, Flywheel) only need to coexist with IP's slot-0, which is upstream-supported

## 17. Risks before phase 0

| Risk | Mitigation |
|---|---|
| NeoForge 1.21.1 frozen-registry timing for custom dim types | Kill-switch test in phase 0 — cheap to validate before committing further work |
| `SubLevelTrackingPointSavedData` cross-dim semantics unclear | Investigate during phase 1; may stay per-parent |
| Migration data loss on existing worlds | Test against a known-good save before enabling automatic migration; copy-then-delete is atomic per-chunk at NBT level |
| Iris/Sodium/Veil re-test after renderer rewire | Phase 4 includes a regression-test pass on the cog/chest scenarios that drove the slot-0 work on this branch |

## 18. Pre-refactor baseline (what already shipped on this branch)

The 5 commits on `ipl-sable-compat` ahead of `origin/main` deliver the slot-0 IP-side architectural fix that's load-bearing for this refactor's transit cropping:

1. `60e2c89` — Fix portal clipping under shaderpacks (Iris pass outside FrontClipping bracket)
2. `3dc849a` — Hook Veil + Flywheel shader compile pipelines for cross-stack coverage
3. `4b93f5b` — Expand affectedShaders: block_entity_diffuse, moving_block, particles, simulated:*, aeronautics:*
4. `883cb41` — Slot-1 propagation: program-bind equation write + sub-level equation priority
5. `048802a` — Diagnostic probes for shader pipeline, program binds, and uniform residency

Phases 4-6 of this refactor depend on commits 1, 2, 3 staying load-bearing. Phase 8 deletes commit 4 (slot-1 work) and most of commit 5 (probes that targeted slot-1 specifically).

---

## 19. Status: TABLED (2026-05-19)

> This branch (`ipl-sable-dim-agnostic`) is preserved for archival. **The dim-agnostic refactor as designed has an architectural blocker that surfaced during phase 2 implementation.** Active development reverts to `ipl-sable-compat` (the slot-0 + slot-1 model) until the blocker is resolved.

### 19.1 What was actually implemented (phases 0-2 + 2b + 2c)

| Phase | Status | Artifacts |
|---|---|---|
| 0 — datapack + `SableSubLevelDimension` + kill-switch | ✅ working | `data/ipl_sable/dimension*/sublevels.json`, `ipl/sable/dim/SableSubLevelDimension.java` |
| 1 — `SubLevel.level` field split via mixin | ✅ working | `IplSubLevelDuck`, `IplSubLevelFieldSplitMixin` |
| 2 — plot relocation: route `allocateNewSubLevel` + `allocateSubLevel(UUID,...)` to `sable_sublevels`'s container | ⚠️ technically fires but exposes the blocker | `IplSubLevelAllocRoutingMixin`, `IplTrackingSystemParentRedirectMixin` |
| 2b — single global Rapier scene (`getID(ServerLevel)` → 0) | ✅ working | `IplRapier3DUnifyMixin` |
| 2c — pipeline-add CoM/bounds defaults (band-aid) | ⚠️ masked a real bug | `IplPipelineAddDefaultsMixin` — **delete on revisit** |

### 19.2 The blocker: `SubLevelAssemblyHelper` is hard-coded to write blocks into the input Level

[`SubLevelAssemblyHelper.assembleBlocks`](https://github.com/ryanhcode/sable/blob/main/common/src/main/java/dev/ryanhcode/sable/api/SubLevelAssemblyHelper.java#L68-L119) takes a `level` parameter (the player's current dim), calls `container.allocateNewSubLevel(pose)` on it, then constructs an `AssemblyTransform` with `resultingLevel = level` and uses that for **every block write** in `moveBlocks`.

After our phase 2 routing:
- The sub-level lives in `sable_sublevels`'s container ✓
- Its plot chunks live in `sable_sublevels` ✓
- But `moveBlocks` still writes blocks into the **input level** (overworld) at offset (10000, ?, 10000) — using vanilla chunks, NOT the plot chunks
- The sub-level's plot stays empty
- Mass tracker scans the empty plot → mass = 0, CoM = null
- `onStatsChanged` never fires → Rapier never gets CoM/bounds
- First physics tick: `compute_buoyancy` panics on `info.center_of_mass.unwrap()`

The pipeline-defaults mixin (phase 2c) was masking step 6 without fixing steps 4-5. Result: the airship would be invisible AND have wrong physics AND scatter stray blocks in overworld at offset (10000, ?, 10000).

### 19.3 Three real forward paths

| Option | Description | Effort | Why it might be right |
|---|---|---|---|
| **A. Comprehensive routing** | Mixin `SubLevelAssemblyHelper.assembleBlocks` (and similar entry points — Create disassembly, schematic placement, etc.) to use `hostingLevel` for transform/writes | ~30-50 LOC across 2-3 mixins, moving target | Preserves dim-agnostic-from-creation; least conceptual change |
| **B. Post-assembly migration** | Revert routing entirely. Let assembly run normally — sub-level lives in parent. After it returns, migrate chunks/state to `sable_sublevels`. Cross-portal transit uses this same migration at boundary crossings | ~100-150 LOC for a clean migrator | Existing assembly flow untouched; migration is the only new concept |
| **C. Mirror approach** ⭐ | Sub-levels stay in parent's container (today's model). `sable_sublevels`' container is a thin **mirror** used only by the renderer + cross-portal transit. Physics, assembly, ticks all stay parent-dim | ~50-80 LOC for the mirror system | Closest to the user's original intent: "use Sable's current far-off chunk loading map." The dedicated dim becomes the *rendering coordinate*, not the *storage coordinate*. Respects every existing Sable assumption |

**Recommended path on revisit: Option C.** Despite being labelled "the mirror approach" and initially dismissed, it's actually the closest fit to the original framing. The unified Rapier scene (2b) is still useful for Portal-style physics (phase 9c) regardless of which option we pick.

### 19.4 Outstanding questions for revisit

1. **`Rapier3D.initialize` overwrite at startup** — our unify mixin doesn't intercept it (native method); multiple pipelines call `initialize(0, ...)` and the second clobbers the first. We argued this is benign because no bodies exist between startup initializes. Should verify under load.
2. **`SubLevelTrackingPointSavedData` per-parent semantics** — never fully investigated; phase 1 left it as a TODO.
3. **Which other entry points** besides `assembleBlocks` write blocks into a sub-level? Create contraption disassembly, schematic placement, `/sable assemble` commands. Each needs the same routing fix under Option A.

### 19.5 What to delete on revisit

- `IplPipelineAddDefaultsMixin` (the band-aid; root cause is `assembleBlocks` not the missing CoM)
- Possibly `IplSubLevelAllocRoutingMixin` if Option C wins (sub-levels stay in parent's container)

### 19.6 What to keep on revisit

- `SableSubLevelDimension` + the datapack files (phase 0 — the dedicated dim is useful for all three options)
- `IplSubLevelFieldSplitMixin` + `IplSubLevelDuck` (phase 1 — the duck interface is needed for any approach that distinguishes parent from host)
- `IplRapier3DUnifyMixin` (phase 2b — unified physics scene enables Portal-style mechanics regardless of which option we pick)
- This spec document

---

## 20. Status: Option B implemented on `ipl-sable-dim-agnostic-v2` (2026-06-09)

Phases 2–6 implemented on top of the phase 0/1 foundation, following Option B
(migrate-at-assembly) with the parent-flip transit, then **runtime-validated through an
iterative bring-up session** (same day). Working in-game: rehome at assembly, hosted
rendering, walking/collision, block place/break, physics staff (lock + drag), pistons
pushing ships, straddle dual-render (source clip + dest-side projection), and full
cross-portal transit with arrival-terrain re-bake.
Master kill-switch: `-Dipl.sable.dimAgnostic=false` reverts to the legacy mirror/copy model.

### 20.0 Bring-up findings (read before debugging anything "silently not working")

1. **IP subclass overrides bypass Sable's vanilla-class mixins.** Sable hooks
   `ClientChunkCache`, `ViewArea`, etc.; IP's secondary worlds use `ImmPtlClientChunkMap`
   and `ImmPtlViewArea`, whose overrides never hit those hooks. Plot routing is re-added
   directly in the IP classes (delegating to `ipl.sable.client.IplPlotChunkRouting`).
   Related trap: interface DEFAULT methods (e.g. `EntityGetter.getPlayerByUUID`) can't be
   mixin-targeted via `Level` — with `defaultRequire: 0` the miss is silent; an override
   must be MERGED into `ServerLevel` instead (`IplHostingLevelPlayerLookupMixin`).
2. **The Rapier pipeline re-reads voxel content through level-bound accessors** — the
   section arguments are coordinate carriers only. Any cross-dim feed must route reads
   via `IplTerrainReadOverride` (covers `LevelAccelerator.getChunk` and the
   `handleBlockChange` neighbor reads).
3. **Vanilla section compile self-cancels via `hasAllNeighbors()`** — vacant plot-grid
   coords must resolve to a non-null empty chunk (Sable's semantics), or hosted geometry
   stays UNCOMPILED forever.
4. **Client block-change predictions live on the player's level**, while hosted
   confirmations arrive under the sublevels level — bridged via
   `IplPredictionBridgeMixin`, else breaks roll back visually.
5. **The plot grid is a universal address space** (`IplPlotBridgeMixin`): chunk-coordinate
   plot lookups from ANY dimension fall through to the hosting container (allocation
   guard exempted). This single bridge powers parent-dim block reads, collision clipping,
   placement checks, by-UUID tool lookups, and `getContaining`.
6. **Per-body pipeline calls are location-transparent**: the Rapier ownership guard
   forwards calls on hosted bodies to the owning (hosting) pipeline instead of no-opping
   (impulses, velocities, teleport, wake, constraints, contraption enrollment).
7. **Frame errors amplify through height-profile gates.** A pose used in the wrong
   dimension frame is off by the portal offset; harmless-looking consumers blow up when
   the wrong Y crosses a height gate. Two bites: `LevelAccelerator` indexes sections with
   its constructor level's `minSection` (nether accelerator + hosting plot chunk = 64-block
   shift), and `Level.isLoaded`'s FIRST gate is `isOutsideBuildHeight` — Sable's
   plot-local `getOnPos` result at unmapped Y (~264) fails the nether's 0..256 range,
   vanilla travel takes its "chunk below not loaded" failsafe, and the rider's velocity is
   pinned to `-0.1 * 0.98F` every tick (eaten jumps, floaty falls — through-part only,
   because the overworld's -64..320 range happens to contain the wrong Y).
8. **Mixin composition rules learned the hard way** (straddle getOnPos saga):
   (a) callbacks at the same injection point do NOT reliably order by mixin priority —
   Sable's cancellable HEAD inject ran first regardless of ours being 1000 or 1200, and a
   cancel skips all later callbacks; (b) `@ModifyReturnValue` does not see returns produced
   by another mixin's `setReturnValue`; (c) methods ADDED by another mixin (handler
   bodies) cannot be injection targets at all — wildcard `method = "*"` silently skips
   them. The reliable seam is the CALLER: vanilla methods that delegate
   (`getBlockPosBelowThatAffectsMyMovement` etc.) return normally and their returns are
   correctable. And don't stack two corrections: a fix at the source plus a fix at the
   caller double-subtracts the offset.
   (d) INVOKE targets match the BYTECODE RECEIVER TYPE — javac emits the static type of
   the receiver variable as the owner, even for inherited methods. A call through a
   `LocalPlayer`-typed variable to `pick` (declared on Entity) needs owner
   `net/minecraft/client/player/LocalPlayer`, not `Player`; same for
   `ClientSubLevel`-typed calls to `logicalPose`. With `require = 0` a wrong owner is a
   SILENT no-op — when a wrap "mysteriously doesn't fire", check the receiver's declared
   type at the call site first. (e) Mixin classes may not contain non-private static
   members — shared helpers go in regular classes.

### 20.1 Server side

| Piece | Artifact | Notes |
|---|---|---|
| Rehome sweep (phase 2) | `ipl/sable/transit/SableRehomeOps.sweep` — driven per container tick from `SableSubLevelTransitMixin`, before the transit controller | Assembly/load paths untouched (fixes the v1 `assembleBlocks` blocker). One rehome per container tick: allocate same-UUID twin in `ipl_sable:sublevels` at identical pose → `copyPlotBlocks` (proven 3-pass) → relocate plot-resident entities → transfer velocity verbatim → remove parent original. Parent persisted in sub-level `user_data` (`ipl_parent_dim`); restored by the sweep on world load. Split sub-levels inherit parent from their split source (best-effort; see 20.4). |
| Gate + helpers | `ipl/sable/dim/IplDimAgnostic` | `isEnabled` / `isHostingLevel` / `isHosted` / `getParentLevel` / `getServerParentLevel` |
| Tracking (phase 2b) | extensions in `SableCrossDimTrackingMixin` | Hosting container's viewers = parent-dim players within Sable tracking range + IP portal viewers of the parent pose-chunk. All existing cross-dim machinery (server-wide player resolution, forced `shouldLoad`, bootstrap full-sync, forced TCP) applies unchanged. `sendRemoval` wrapped in `withForceRedirect(hosting)`. Parent-dim stamp RPC (`McRemoteProcedureCall` → `ipl.sable.client.IplParentDimSync`) sent after each hosted full sync. |
| Packet stamping (phase 3) | `IplHostingLevelTickRedirectMixin` (whole hosting `ServerLevel.tick` under `withForceRedirect`) + `IplPlotChunkSendStampMixin` (plot-expansion chunk send, which runs in C2S handling between ticks) | Every packet the hosting dim emits is dim-stamped; IP's client unwrap lands them in the client's sublevels containers. The client sublevels `ClientLevel` is created lazily by IP on the first redirected packet. |
| Unload + terrain (phases 3/5) | `IplHostedTicketManagerMixin` | (a) hosting dim is always "loaded enough" → hosted ships never `moveToUnloaded`, no ticket thrash; (b) inside the per-sub-level ticket loop, terrain chunk sections are read from the PARENT dim (section index recomputed against the parent's height profile) and fed into the HOSTING pipeline — ship pose and terrain share the parent frame, so Rapier collision is consistent without the v1 global scene unification. |
| Spatial queries (phase 5) | `IplHostedIntersectionMixin` on `ActiveSableCompanion` | `getAllIntersecting(level, bounds)` splices in hosted sub-levels with `parentLevel == level` (both sides; client resolves via non-creating `IplClientHostedLookup`). Also fixes `getVelocity(level, subLevel, …)` to resolve the physics handle from the sub-level's own (hosting) container. |
| Transit (phase 6) | `SableRehomeOps.executeHostedTransit` + hosted dispatch in `SableTransitController` | Portal query against the PARENT dim; explicit straddle latch replaces "mirror exists"; CROSSED → pose remap (`computeMappedPose`), velocity rotation, rider teleport from parent, `parentLevel` flip, forced re-track (StopTracking to all, re-bootstrap next tick). No block copy, no container move. Mirrors fully disabled in dim-agnostic mode. |

### 20.2 Client side

| Piece | Artifact | Notes |
|---|---|---|
| Parent stamp | `ipl/sable/client/IplParentDimSync` | RPC receiver; sets duck parent on the hosted `ClientSubLevel` in the sublevels client container. |
| Render gather (phase 4) | `client/IplHostedSubLevelRenderMixin` (priority 1010, vanilla backend) | Mirrors all seven of Sable's `LevelRenderer` hook points, feeding hosted sub-levels whose `parentLevel == this.level`. Each `ClientLevel` has its own `LevelRenderer`, so main pass + every IP portal pass get per-dimension visibility automatically; IP slot-0 crops at portal planes. |
| Section compile source | `client/IplHostedRenderDataMixin` | Hosted render data is built with `ClientWorldLoader.getWorldRenderer(SUBLEVELS).getSectionRenderDispatcher()` → block/light reads come from the sublevels `ClientLevel` (resolved question #1, the multi-dispatcher pattern). |

### 20.3 Test sequence (acceptance ladder)

1. Boot: `[IPL-SABLE-DIM] … loaded OK` + `[IPL-REHOME] dim-agnostic mode ENABLED`.
2. Assemble an airship → `[IPL-REHOME] rehoming uuid=… minecraft:overworld -> ipl_sable:sublevels` then `complete`. Ship stays visible, walkable, pilotable; propellers thrust.
3. `/forge dimensions` or region files: plot chunks in the sublevels dim, none in overworld.
4. Save + reload → `[IPL-REHOME] restored parent …`; ship reappears in overworld.
5. Look at the ship through a nether portal from the nether → visible, clipped at the plane (slot-0).
6. Fly the ship through the portal → `[IPL-FLIP] firing … complete`; ship + riders arrive in the nether; chunks never moved.

### 20.4 Known gaps / follow-ups

Updated 2026-06-10 after the straddle-physics milestone (`dcb8126`); ordered roughly by
priority.

- ~~**Sub-level disk persistence**~~ FIXED (boot-restore): saving always worked
  (`saveAll` serializes live ships); RESTORE was chunk-event-driven and the hosting dim's
  chunks only load via physics tickets of already-live ships — boot deadlock, plus
  surprise "ghost" restores when a new ship's tickets overlapped an old save's holding
  position, plus mid-session despawns when ticket churn unloaded a chunk intersecting a
  live ship (the rare disappear-on-crossing). Now: holding region files enumerated at
  first hosting tick and released eagerly (readiness bypassed for hosting), and
  hosting-dim chunk-UNLOAD events are ignored by the holding map.
- **Sub-level ↔ sub-level interaction across parent frames** (untested blind spot, the
  big one): all hosted ships share one Rapier scene with poses in their PARENT frames.
  Same-parent pairs interact correctly; ships from different parents can phantom-collide
  on coordinate overlap, and a straddler + a dest-side ship do NOT collide where they
  visually meet at the portal mouth. Direction: parent-keyed collision filters + portal-
  mapped kinematic collider clones for straddlers (the terrain-clone concept applied to
  ship bodies — a physics-only mirror, no plots). Needs design first.
- **Rare disappearance on crossing**: sub-levels occasionally vanish during a flip; no
  repro yet. Instrument `removeSubLevel` with a reason log for hosted ships (suspects:
  straddle-latch reap, rehome rollback, mirror-era removal guards, MassTracker-invalid
  destroy).
- ~~**Targeting straddled through-parts**~~ DONE: projection-aware client clip
  (mapped-pose ray clips with `sable$setDoNotProject` recursion suppression +
  frame-correct distance comparison), the `projectOutOfSubLevel` keystone wraps (mapped
  pose feeds ALL of Sable's frame-aware distances incl. `GameRenderer.pick`'s reach
  clamp), mapped `canInteractWithBlock`, outline via a state-bracket outside Sable's
  outline mixin, staff lock/beam pose wraps, cross-portal ship targeting through IP's
  block manipulation (overlay clip + `SableBridge.frameAwareDistanceSqr` in
  client targeting and server `canPlayerReach`), and the prediction bridge fanned out to
  all client worlds (IP's remote pipeline books predictions on the remote world).
  Verified: pick/outline/break/place on through-parts near-side and through the portal;
  staff lock lands correctly. Remaining staff DRAG = the through-portal staff item.
- **Staff control through portals** (enhancement): keep drag/aim active across the
  portal with the target mapped through the portal transform.
- ~~**Deferred block logic**~~ DONE (`IplPlotDeferredLogicMixin` +
  `IplHostingLevelNeverEmptyMixin`): scheduled ticks and block events route to the
  hosting level (cascades run on the interaction dim whose plot tick containers are
  stale/missing — the pre-rehome original even leaves a registered-but-never-collected
  container, so drops were silent); block-event packets broadcast to the sub-level's
  tracking players (vanilla targets 64 blocks around PLOT coords = nobody, a stock-Sable
  gap). The piston's final layer: vanilla's EMPTY-LEVEL optimization skips
  `tickBlockEntities` after 300 ticks with no players in the dimension — nobody is ever
  IN the hosting dim, so every ship block entity froze (the moving-piston BE stuck
  mid-extend; furnaces/hoppers would be equally dead). The hosting level now resets
  `emptyTime` every tick. Verified: repeaters, lamps, multi-cycle pistons. ~+1 tick
  scheduling latency from cross-level routing.
- **Sodium backend**: `IplHostedSubLevelRenderMixin` covers the vanilla path only; the Sodium reach-around dispatcher hooks `SodiumWorldRenderer` and needs its own gather splice. Test without Sodium first.
- **Straddle render polish**: portal-plane seam hole (eye-space mismatch at the clip
  boundary) and Flywheel-rendered components (cogs) using a buggy eye-space clip in
  portal-containing scenes.
- **Split timing**: parent inheritance for split-off sub-levels races `clearSplitFrom()` (tracking processes the addition queue in the same container tick). If the `[IPL-REHOME] … no persisted parent dim` warn shows after a split, hook the splitter's allocation site directly.
- **Hosted ships never hold-unload** (always "loaded enough") and keep their parent-frame terrain chunks force-loaded via synchronous `getChunk` — perf parity with legacy unloading is a follow-up.
- **Old-parent terrain residue**: after a flip, the previous parent's terrain sections persist in the hosting pipeline up to ~20 ticks; with nether 8:1 compression they can overlap the arrival position.
- **Cross-parent terrain bleed**: ships from different parent dims share the hosting Rapier scene; overlapping parent-frame positions would see each other's terrain copies.
- **`SubLevelTrackingPointSavedData`** (spawn points on airships) still per-parent-dim; unresolved from phase 1.
- Hanging/plot-resident entity relocation during rehome uses `teleportEntityGeneral` per entity; passengers are skipped (vanilla re-seats).

