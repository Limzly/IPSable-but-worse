# Atlas Portal Physics Audit Results — Gap Matrix (M0)

Companion to `atlas_portal_physics_spec.md` §5. Three audit slices (engine fork
internals @ cargo checkout `38e92f1`, sable_rapier natives crate, Java/bridge step &
readout seams), 2026-07-21.

Engine source audited at the pinned rev:
`C:\Users\crazy\.cargo\git\checkouts\rapier-b7330b82f1d0b14c\38e92f1` — the exact
source the shipped natives build against.

## Verdict

**Green light — the Tier-1 gate PASSES, and it is cheaper than the spec estimated.**
The fork's solver is a rewritten Structure-of-Arrays design (not stock rapier), and
that rewrite happens to make the COM substitution clean: `world_com` is gathered
**per lane, per constraint** from `SolverBodies`, so a per-manifold mapped COM
injects with no struct changes; and the "scalar path" is literally the SIMD builder
at width 1, so there is **one** code path to patch, not two. Tier 1 touches three
`dp1/dp2` sites plus their `local_p` twins. Tier 2's real cost is now precisely
known: the constraint structs share a single `dir1`/`tangent1` between both bodies,
which must be split per-body — contained, but a genuine refactor.

Two findings *improve* the design (fed back into the spec):
1. **Self-image exclusion is free**: the narrow phase hard-clears same-parent pairs
   (`narrow_phase.rs:843`) — exactly the §2.5 exclusion, implemented by the engine
   already. No `ipl_excluded_pairs` growth needed for images.
2. **Terrain needs no chart collider split**: terrain is ONE giant-AABB collider per
   scene whose voxels come from the chunk maps at narrow-phase time, and the
   dispatcher already selects chunk storage per-body through a `&dyn ChunkAccess`
   switch. Chart isolation for terrain = chart-keyed chunk-map *selection* (keyed by
   the dynamic collider's chart tag), not N terrain colliders — which also avoids
   the N-giant-AABB broad-phase pair explosion entirely.

One new open item surfaced: **per-chart gravity/drag under a fused step** (rapier's
`pipeline.step` takes one gravity vector; scenes carry per-dimension gravity/drag
today). Small, three known resolutions, see gap matrix row.

## Gap matrix (spec § → classification)

| Spec § | Need | Classification | Evidence / hook point | Size |
|---|---|---|---|---|
| 2.3 Tier 1 | Mapped-COM lever arms for imaged colliders | **ENGINE CHANGE (small, one path)** | Lever arms: `contact_with_coulomb_friction.rs:120-121` (`dp1 = solver_contact.point - world_com1`), `contact_with_twist_friction.rs:133-134` + `:188-189` (friction center), `generic_contact_constraint.rs:130-131` (multibody/fallback). Matching `local_p1/p2` for substep re-projection: `coulomb:196-197`, `twist:173-174,185-186`, `generic:276-283`. COM is gathered per-lane (`gather_poses(ids1)`, `contact_with_coulomb_friction.rs:70-73`; SoA transpose `solver_body.rs:288-310,444-472`) → per-manifold `t` injects like `data.normal`. Scalar = SIMD at width 1 (`#[cfg(not(simd-is-enabled))]` arms); remainder manifolds reuse the same builder with masked lanes (`contact_constraints_set.rs:427-442`). **No struct changes.** | days |
| 2.3 (carrier) | Per-manifold portal field | **ENGINE CHANGE (small)** | Builders never see collider handles — `ContactManifoldData` (`contact_pair.rs:313-349`) has body handles, normal, flags only; collider handles stop at `ContactPair` (`:149-151`). Stamp a new field (`Option<portal prefix>` / COM offset) in the narrow-phase manifold loop `narrow_phase.rs:999-1008`, where both colliders ARE in scope; gather it per-lane in the builder via the existing `gather!` idiom. | with Tier 1 |
| 2.3 Tier 2 | Frame-rotated Jacobians (any fixed R) | **ENGINE CHANGE (contained refactor)** | Blocker located: `ContactWithCoulombFriction`/`TwistFriction` store ONE shared `dir1` (+ `tangent1`) used for BOTH bodies' linear Jacobians — apply sites `contact_constraint_element.rs:108-128,143-202,255-259,274-286,360-365`. Angular blocks (`torque_dir1/2`) are already per-body and can absorb R in `generate()`. Tier 2 = split `dir1`→`{dir1_b1, dir1_b2}` (structs at `coulomb:323-339`, `twist:328-351`) + element solve/warmstart signatures. | 2-3 wk (as spec'd) |
| 2.2 | Image collider portal transform | **ENGINE CHANGE (small)** | Single chokepoint for the normal step: `RigidBodyColliders::update_positions`, `rigid_body_components.rs:1154` — `new_pos = parent_pos * pos_wrt_parent`; left-multiply prefix → broad phase (AABB from `co.pos`) and narrow phase (`pos12` at `narrow_phase.rs:942,787`) both follow automatically. Sibling sites needing the prefix: `rigid_body_components.rs:1126` (attach), `rigid_body_set.rs:420`, `user_changes.rs:23`. Mass-props composition must NOT get it (`rigid_body_components.rs:463,1134-1136`) — images contribute no mass. 7 CCD composition sites listed (CCD stays off for portal colliders). Prefix storage: alongside `pos_wrt_parent` on `ColliderParent`. | days |
| 2.5 | Self-image exclusion | **FREE (engine already does it)** | Narrow phase hard-clears same-parent pairs: `narrow_phase.rs:843-847` (contacts), `:737-741` (sensors). Broad phase does emit them (`broad_phase_bvh.rs:183-206`, only asserts distinct colliders) but they die before manifolds. Future self-collision-through-loops needs BOTH a relax flag at `:843` AND a solver fix — the SoA double-scatter (`scatter_vels` 1 then 2, `coulomb:380-381,466-467`) silently drops body-1's impulse when ids are equal; no debug_assert guards it. Islands are safe (merge guard `manager.rs:123-125` trivially false for self). Stays descoped; now with precise reasons. | 0 now |
| 2.1 | Chart isolation (dynamic↔dynamic) | **EXISTING API (group bits) + caveats** | Only 2 of 32 group bits used (`groups.rs:3-9`: LEVEL=G1, ROPE=G2) — ~30 free for chart ids. Caveats: fork's `InteractionTestMode::Or` (`groups.rs:6,9`) is more permissive than AND — chart masks must be designed so neither direction crosses (or switch these constants to And); boxes carry NO groups (`boxes.rs:53-60`, default all/all) and must be stamped. Group assignment sites: `lib.rs:378,680`, `contraptions.rs:90`, `rope.rs:246`. `FILTER_CONTACT_PAIRS` never enabled and `filter_contact_pairs` not implemented (`hooks.rs:23-132`) — NOT needed; group bits suffice. | days |
| 2.1 | Chart isolation (terrain) | **NATIVES CHANGE (chunk-map keying — the elegant route)** | Terrain = ONE static collider per scene with a ±30M AABB (`lib.rs:375-379,434`; `collider.rs:88-98`); voxels resolved at query time from `main_level_chunks`/`octree_chunks`. Dispatcher already selects storage per-body via `&dyn ChunkAccess` (`dispatcher.rs:305-311,480-492` — the `useDedicatedChunks` fork). Route: keep ONE terrain collider for the whole world; key chunk maps by chart; dispatcher selects the map by the DYNAMIC collider's chart tag (image colliders carry the far chart's tag). Kills cross-chart terrain pairs at the source — no terrain-AABB explosion in the (fork's BVH) broad phase. | days |
| 2.1 | Terrain map rekey blast radius | **NATIVES CHANGE (mechanical)** | `pack_section_pos` consumes all 64 bits (`scene.rs:29-35`) → wrap in `HashMap<chart, ChunkMap>` rather than widening. ~25 direct map accesses concentrated in `lib.rs` addChunk `:782`/removeChunk `:932`/changeBlock `:999-1111`/setLocalBounds `:591`, `dispatcher.rs:316,519,565,845`, `algo.rs:225`, `buoyancy.rs:77`, 2 `ChunkAccess` impls. Collision hot path goes through the trait — unchanged if the trait is backed per-chart. JNI fns need a chart resolved from the scene-view handle (no Java signature change needed). | days |
| 2.7 | Step fusion seam | **EXISTING (bridge mixin point)** | Stepping is per-level: `ServerLevel.tick` → `SubLevelPhysicsSystem.tick` → `tickPipelinePhysics` (2.0.3 `:249-296`) → `pipeline.physicsTick` → `Rapier3D.step` (`RapierPhysicsPipeline.java:157-170`). 2 substeps/tick, dt=1/40 (`SubLevelPhysicsSystem.java:253`, `PhysicsConfigData.java:10`). The bridge already `@WrapOperation`s exactly the `physicsTick` invoke (`IplCloneSubstepMixin.java:29-41`) — fusion = same target, no-op for all but the designated owner level. `currentlySteppingSystem` static (`SubLevelPhysicsSystem` 2.0.3 `:77,:233,:245`) is the natural owner latch. Pose readback is per-body (`getPose` per runtime id, `RapierPhysicsPipeline.java:302`) — survives as-is. Scene-handle indirection exists: `getSceneHandle()` (`:105-110`) → several pipelines can return one native handle (chart views). | days |
| 2.7 | Per-chart state under fused step | **NATIVES CHANGE (small) + 1 OPEN ITEM** | Must stay per-chart: `reported_collisions` buffer (`scene.rs:103,44-54`), `manifold_info_map` (+atomic counter, `scene.rs:105,120-137` — shared map would collide indices), gravity+drag (`scene.rs:113,116`). **OPEN: `pipeline.step` takes ONE gravity vector** — per-dimension gravity under one world needs (a) uniform-gravity assertion (true for vanilla dims), (b) per-body gravity via forces, or (c) per-body gravity scale in the fork. Global `dt` write at `lib.rs:501` and global `integration_parameters`/`voxel_collider_map` are already shared — non-issues. | days + decide (a/b/c) |
| 2.7 | Contact-event demux | **EXISTING (bridge tee) + 1 Sable assumption** | Body runtime ids are process-globally unique (`Rapier3D.nextBodyID`, single static counter, 2.0.3 `:121-124`) → world-global buffer demuxes by id→owning-pipeline lookup (bridge already has `IplSceneOwnership.bodyHome`, `:54,89-100`). Fan-out point exists: the tee `@WrapOperation` around `clearCollisions(J)[D` (`SableRapierPipelineOwnershipGuardMixin.java:330-344`) — one native drain, demux slices per level. Fix required: `processCollisionEffects` assumes every record is `this.level`'s (`RapierPhysicsPipeline.java:661-662` + effect emission) — foreign records are silently dropped today; gate emission on record ownership. Record stride 15 documented (`RapierPhysicsPipeline.java:650-659`). | 1-2 days |
| 2.7 | Cross-level static flushes | **BUG FIX REQUIRED PRE-M1 (1 confirmed, 4 to verify)** | CONFIRMED: `WheelMountBlockEntity.queuedWheelMounts` static set (`WheelMountBlockEntity.java:71`), populated per sub-level tick (`:221`), drained globally ignoring the level arg (`applyAllBatchedForces`, `:99-107`), fired from prePhysicsTick (`OffroadCommonEvents.java:28-31`, `Offroad.java:62`). Under fusion the first-stepping level drains ALL dims' wheel forces. Fix: key by ServerLevel or filter by `be.getLevel()==level`. VERIFY (level-arg'd singletons, same event): `GLOBAL_REDSTONE_MAGNET_MAP`, `DockingConnectorBlockEntity.MAGNET_CONTROLLER`, `EndSeaPhysicsData`, `LaunchedPlungerServerHandler` (`SimulatedCommonEvents.java:111-116`). | 1-2 days + verify pass |
| 2.2 | Scene→chart JNI shim | **NATIVES CHANGE (mechanical)** | 57 JNI entry points; 50 scene-handle-first, 7 global (`initialize`, 3 config, 3 voxel-collider). No scene registry exists anywhere — handles are leaked `Arc` pointers (`lib.rs:401-440,449-453`, deref `with_handle` `:272-278`). Shim: handle → {world, chart} view struct; per-chart state per Q above. Java caches to re-point are enumerated: pipeline scene holder, constraint/rope/box handles (cache scene at creation), bridge `Session.parentScene/destScene` (`IplStraddleCloneBody.java:170-173,219,227-228`). | days |
| §6 | A/B baseline | **READY** | Local build loop verified: nightly-2026-01-29, `build-windows.ps1` (llvm-ar dlltool shim `:19-25`, release build, copy to `natives_ipl/` `:37-39`, MANDATORY `fix_import_descriptors.py` `:47` — load-bearing, unpatched DLLs 0xc0000005 on rayon threads). Fresh artifact: `target/release/sable_rapier.dll` 4,535,886 B (2026-07-20) = staged `natives_ipl` DLL. Shipped baseline: `sable_rapier_binaries.zip.l4z` (8,900,100 B) in `_jar_unpack\sable-neoforge-1.21.1-1.2.2\natives\sable_rapier\`. | ready |

## Surprises worth knowing

1. **The fork's solver is NOT stock rapier** — a rewritten SoA `SolverBodies`
   gather/scatter solver: no `one_body/two_body_constraint*.rs` split, unified
   builder, static/absent bodies via `u32::MAX` sentinel lanes, `transmute`-based
   AoS↔SoA gathers (`solver_body.rs:268,293,316-335,478-528`), a fork-only
   `ContactWithTwistFriction` "Simplified" friction model, and a **BVH broad phase**
   (`broad_phase_bvh.rs`) replacing upstream multi-SAP. Net effect: SIMPLER for us
   (one path to patch, per-lane COM already), but upstream-rapier intuition does not
   transfer — audit the fork, not the docs.
2. **The scalar fallback is the SIMD builder at width 1** — remainder manifolds go
   through the same builder with masked lanes (`contact_constraints_set.rs:427-442`).
   There is no separate scalar contact path to keep in sync. (The earlier "route
   portal contacts through a non-SIMD path" mitigation is therefore mostly moot for
   Tier 1; for Tier 2 the `nongrouped_interactions` bucket is a candidate lane if the
   split-dir refactor wants a staged landing.)
3. **Same-parent pairs die in the narrow phase, not the broad phase** — free §2.5
   exclusion, and the precise double-scatter reason self-collision-through-loops
   stays descoped until deliberately funded.
4. **Two Sable trees in-workspace at different majors** (decompiled 1.2.2 vs
   `sable-src` 2.0.3; bridge targets 2.0.3 per `gradle.properties:33` and mixin
   signatures `clearCollisions(J)[D`). All Java line numbers above are 2.0.3. Do not
   patch against decompiled-1.2.2 line numbers.
5. **`Rapier3D.tick` ≠ `step`** — a separate per-scene bookkeeping call
   (rope/joints/buoyancy, `RapierPhysicsPipeline.java:146-149`) that also needs the
   owner-level fusion treatment.
6. **Body ids were global all along** (`nextBodyID` static counter) — the demux
   problem the spec worried about mostly dissolves.

## Design adjustments fed back into the spec

- §2.1: terrain chart isolation via chart-keyed chunk-map selection behind the
  existing `ChunkAccess` switch (ONE terrain collider for the world); group bits for
  dynamic↔dynamic only; `InteractionTestMode::Or` caveat; stamp boxes.
- §2.2: the portal prefix rides `ColliderParent` (engine), stamped into a new
  `ContactManifoldData` field at `narrow_phase.rs:999-1008` for the solver.
- §2.5: self-image exclusion downgraded from "generalize ipl_excluded_pairs" to
  "already enforced by `narrow_phase.rs:843`".
- §5: gravity-per-chart added as the one new open decision (a/b/c above).

## Pre-existing bugs surfaced (independent of this design)

- `queuedWheelMounts` cross-level mis-attribution already occurs TODAY whenever two
  dimensions run physics in the same server tick (the level arg is ignored at
  `WheelMountBlockEntity.java:99-107`) — fusion only amplifies it. Fixable on the
  current branch now.
- The four other Simulated global managers (`SimulatedCommonEvents.java:111-116`)
  are the same disease pattern; unverified whether they filter by level.

## Sequencing adjustments (vs spec §7)

- M0 is DONE except the rebuild-A/B run (baseline artifacts located; build loop
  verified fresh as of 2026-07-20 — run the A/B as M1 step 0).
- M1 gains: wheel-mount fix + the 4-manager verify pass (pre-fusion), the gravity
  decision (a: assert-uniform is the cheap start), and per-chart
  `reported_collisions`/`manifold_info_map` plumbing.
- M2's engine diff is smaller than spec'd: Tier 1 ≈ 3 dp sites + local_p twins +
  1 manifold field + narrow-phase stamp + collider prefix at 4 composition sites.
  Revised Tier-1 engine estimate: **≤1 week** including tests.
- M4 (Tier 2) confirmed at 2-3 wk with the exact refactor named (split shared
  `dir1`/`tangent1` per body across constraint structs + element solve paths).

## Total cost picture (revised)

Engine (vendored fork, feature-gated): Tier 1 + collider prefix ≈ **1 wk**; Tier 2
≈ **2-3 wk**. Natives (sable_rapier crate): chart views + chunk-map keying + group
stamping + per-chart buffers ≈ **1-1.5 wk**. Bridge/Java: step fusion + demux +
wheel-mount fix + cache re-pointing ≈ **1-1.5 wk**. Core (M0-M3, translation-exact,
servo deleted): **≈4-5 wk** — unchanged headline, with the engine share de-risked
from "unknown" to "located, line-level".
