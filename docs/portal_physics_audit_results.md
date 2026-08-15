# Portal Physics Audit Results — Gap Matrix

Companion to `dim_agnostic_portal_physics_spec.md` §5. Three audit slices (sable-src
natives/JNI, simulated-src actors/traction, IPSable attachment points), 2026-06-11.

## Verdict

**Green light. Nothing forces the §2.5 shape-surgery fallback, and no component lands
in "fork the natives."** The Rust source for the `Rapier3D` JNI layer is in the sable
repo (gradle/Docker-built, `common/build.gradle:43-96`) — native work is additive and
PR-able. The two scariest dependencies turn out to already exist in production:
`PhysicsHooks::modify_solver_contacts` is implemented and active on every sub-level
collider, and a contact-readout JNI path exists (used for impact sounds/particles).

## Gap matrix (spec component → classification)

| Spec § | Need | Classification | Hook point | Size |
|---|---|---|---|---|
| 2.2 | Per-dimension scenes from Java | **EXISTING API** | `Rapier3D.getID(ServerLevel)` mints per-level scene IDs lazily (Rapier3D.java:121-132); `initialize(sceneId, gravity, drag)` creates scenes in a plain HashMap (lib.rs:317-421). Per-dimension scenes are stock Sable's own shape. No scene-destroy native exists (pre-existing leak; accept). | days (bridge plumbing only) |
| 2.3 | Clone body reusing collider data | **EXISTING API** | `createSubLevel` + `addChunk(global=false, id)` + `setLocalBounds`/`setCenterOfMass`/`setMassPropertiesFrom` (template: RapierPhysicsPipeline.java:310-330, 449-484). The voxel-collider registry is **global and cross-scene** (lib.rs:250-252) — the far scene accepts the home pipeline's packed ints verbatim. Plot coords are globally unique → no chunk-key collisions. | days (clone manager + re-feed on block updates) |
| 2.4 | Velocity servo | **EXISTING API** | `addLinearAngularVelocities` is an exact set (Rust does `set_linvel(current + Δ)`, lib.rs:1170-1177); per-substep injection point exists: `SableEventPublishPlatform.INSTANCE.prePhysicsTick` (SubLevelPhysicsSystem.java:257). Default stepping: 2 substeps/tick, dt=1/40s. Scenes step sequentially in level-tick order → same-tick or next-tick feedback as spec allows. | days (gain tuning) |
| 2.4 | Contact-impulse readout | **EXISTING API (degraded)** → **NATIVE CHANGE (small)** for fidelity | `Rapier3D.clearCollisions(sceneId)` returns per-manifold-point records: `[idA, idB, pairForceScalar, localNormals, localPoints]` (lib.rs:1186-1238, fed by CONTACT_FORCE_EVENTS, event_handler.rs:23-89). Degradations: pair-level force scalar (not per-point impulses), **no tangent/friction component**, 0.1N floor, 100-record cap, and the buffer is cleared by `processCollisionEffects` (RapierPhysicsPipeline.java:218) — bridge must share via mixin, not double-read. Fidelity fix: emit `point.data.impulse` (+tangent) in event_handler.rs, widen record, raise cap. Precedent for impulse readout exists (`getConstraintImpulses`). | mixin ~1 day; native fidelity 1-3 days |
| 2.4 | Mass properties / gravity on clone | **EXISTING API** | `setMassPropertiesFrom(sceneId, id, MassData)` (Rapier3D.java:655). Note: rigid-body local COM forced to zero (lib.rs:1101); the COM concept lives in the voxel-frame offset (`setCenterOfMass`) — mirror both. Gravity is per-scene, no per-body scale — but the servo owns the clone's velocity, so likely moot; else counter-impulse (pre-rotate to body-local! lib.rs:1318) or a trivial `setGravityScale` native. | hours |
| 2.5 | Contact clipping at aperture | **NATIVE CHANGE (small, additive, PR-able)** | `SablePhysicsHooks::modify_solver_contacts` is implemented and **active on every sub-level collider** (hooks.rs:15-113; ActiveHooks set at lib.rs:652). Production precedent for contact removal exists (`solver_contacts.clear()` via TNT/bell callbacks, hooks.rs:109-111). Work: clip-OBB storage in `ActiveLevelColliderInfo` (alongside `fake_velocities`, lib.rs:50-61), new `setClipRegions(sceneId, id, double[])` native, force the `NEEDS_HOOKS` bit (same shape as fake-velocity forcing), per-contact `retain` on the predicate. `UnilateralPortalState` (IP) is the input struct. | 2-5 days incl. cross-platform rebuild |
| 2.5 | CCD during straddle | **LIKELY ALREADY INERT** | CCD is hard-coded on (lib.rs:621) but almost certainly does nothing against voxel colliders: the custom dispatcher returns `Unsupported` for all shape casts (dispatcher.rs:109-131) and `LevelCollider` raycast is `todo!()` (collider.rs:38-47). This *explains the observed dim-stack free-fall*. Keep the speed cap; a `setCcdEnabled` native is hours if wanted for hygiene. | 0; verify in step 3 |
| 2.6 | Authority swap | **EXISTING (bridge)** | `executeHostedTransit` (SableRehomeOps.java:327-408) is structurally the swap: pose/velocity remap, riders, parent flip. Becomes a two-scene op (demote/promote instead of teleport); the arrival rebake (lines 383-385, 416-449) retires with phantom terrain. | days |
| 2.7 | Friction / traction | **RAY ROUTING, not contact mapping** | Sable: Rapier-native material friction via the hooks (hooks.rs:104-106); no custom pass. Simulated/Offroad: **zero contact enumeration exists anywhere** — wheels raycast (`level.clip` ×3 downward, WheelMountBlockEntity.java:346-401) and read block-property friction off the hit (lines 203/323). The §2.7 fix is therefore the same routing predicate as §2.8 applied to clip rays + the friction lookup following the ray's resolved level. Force application is already single-authority (`applyForcesAndReset`, line 234) — clone-compatible as-is. | folds into §2.8 pass |
| 2.8 | Actor world routing | **EXTEND EXISTING ROUTER** + **3 BYPASSES** | BE-tick seam covers Simulated's own BEs (ad hoc `this.level.*`, no funnel — fine, we own the Level). Bypasses outside the BE-tick window: (1) `RockCuttingWheelActor.tick` — true MovementBehaviour on a ControlledContraptionEntity riding the ship, position resolution at RockCuttingWheelActor.java:121-124 in entity tick; (2) `MultiMiningServerManager.BlockBreakingData.tick` — destroyBlock/popResource from the **level tick event** (MultiMiningServerManager.java:164-215), level hard-bound at registration; (3) `PropellerBearingContraptionEntity` — unfiltered contraption actors on blades. Each needs context established at its own seam (entity tick / manager tick). | days |
| 2.9 | Entity collision layer | **REUSE AS-IS** | Pose-map mixins confirmed (IplStraddleCollisionPoseMixin etc.); `IplStraddlePoseMap.getOffsetInto`'s server truth must repoint from terrain-clone sessions to the new clone registry, and `mapped()` gains rotation for 90° portals. | hours-days |

## Surprises worth knowing

1. **The rapier engine is already a fork** — `rapier3d = { git = "ryanhcode/rapier" }`
   with glam math and an added `InteractionTestMode`. "Avoid forking" reads "avoid
   *further* divergence"; sable-side additions don't touch the engine fork.
2. **f32 confirmed** (`marten::Real = f32`) — per-scene natural coordinates were the
   right call.
3. **⚠ Source/binary skew**: the bundled `.l4z` natives were NOT built from the
   checked-in Rust rev (Java/Rust signature mismatch on `newVoxelCollider`; `dispose()`
   declared in Java with no Rust symbol). **First step of any native work: rebuild
   from source and A/B against the shipped binary.**
4. **Raycast/shapecast natives don't exist at all** (`todo!()` / `Unsupported`) — the
   wheel raycasts go through MC's `level.clip`, never Rapier. Good for us (routing MC
   clips is bridge-side work), but also means no physics-side ground-truth queries.
5. **Hull voxels live in the scene's shared chunk map** (not body-private; the body
   octree indexes them) — kinematic contraptions are the precedent for body-private
   voxel data if the clone path ever needs it.

## Pre-existing bugs surfaced (independent of this design)

- **Borehead multi-mining is likely broken on hosted ships TODAY**: the actual block
  breaking runs from the level-tick event on the BE's level (= hosting dim), outside
  the world-frame router's BE-tick window (MultiMiningServerManager.java:196, 205) —
  same disease the drills had. Candidate quick fix on the current branch.
- **Static cross-level wheel-force queue**: `queuedWheelMounts` is a static set
  flushed by whichever level's physics tick fires first (WheelMountBlockEntity.java:71,
  99-107) — a latent ordering bug that multiplies once multiple parent dims step.
- Staff drag's `subLevel.getLevel().getPlayerByUUID` (PhysicsStaffServerHandler.java:310)
  — already mitigated in IPSable by `IplHostingLevelPlayerLookupMixin` (server-wide
  fallback on the hosting level).
- Plunger ropes create two-body joints via one level's physics system
  (LaunchedPlungerEntity.java:279) — under per-scene, a rope through a portal must be
  force-only (isometry-mapped) or refused; the joint path cannot span scenes.

## Migration surface (the "hosting container's pipeline" assumption)

Eight bridge call sites assume the hosting scene; the chokepoint is
`SableRapierPipelineOwnershipGuardMixin.ipl$forwardTarget` (built for exactly this
indirection — repoint it at a dimension→scene map and most per-body call sites follow
for free). Full list in the IPSable audit: SableRehomeOps:297,347;
IplStraddleTerrainClone:146,155 (replaced); IplHostedTerrainChangeForwardMixin:58,89
(retired); IplHostedTicketManagerMixin:78,107,134 (replaced);
IplHostedIntersectionMixin:159-162 (extend); SableTransitOps:298 (legacy, inert).
Caveat: `IplLevelAcceleratorOverrideMixin`'s no-override cross-profile branch
(lines 80-82) serves the plot-bridge entity-collision read path, NOT the terrain feed
— keep or relocate it when retiring the class.

## Sequencing adjustments (vs spec §6)

- **Step 0 (new): rebuild natives from source, diff against shipped binary, reconcile
  the skew.** Everything native-side depends on a trustworthy build loop.
- Step 3 feedback will feel mushier than spec until the per-point-impulse readout
  lands (pair-scalar, no tangent) — plan the native fidelity patch as part of step 3,
  not after.
- Step 7 grows the three bypass seams (borehead actor, multi-mining manager,
  propeller-blade contraptions) alongside the router predicate extension.
- The borehead multi-mining pre-existing bug is fixable on the current branch before
  any of this lands (same world-frame-context pattern, different tick seam).

## Total cost picture

Native (PR-able to sable): clip regions + contact-impulse fidelity + optional
gravity-scale/CCD toggles ≈ **1-2 weeks** including the build-loop bring-up.
Bridge-side: scene plumbing + clone manager + servo/feedback + swap + router
extension + bypass seams ≈ **3-5 weeks**. No fork. No shape surgery.
