# Sub-level transit through portals — design doc

This is the living design doc for IP + Sable sub-level transit (airships moving
through IP portals). Updated as research progresses through Phase 0.

## Decisions locked in

- **Phasing**: Phase 0 (research, this doc) → Phase 1 (atomic teleport MVP) → Phase 2
  (mirror without straddling) → Phase 3 (true straddling) → Phase 4 (polish).
- **Identity**: mirror gets a new UUID with an association field
  (`mirrorOf=<sourceUuid>`) — keeps Sable's per-dim tracking clean.
- **Passengers**: defer mounted-passenger handling (Create Aero seats) to a later
  phase. v1 only handles entities standing on deck.
- **Block changes mid-transit**: snapshot at mirror-spawn time, disallow source-side
  changes from propagating. Live sync is a later phase.
- **Render culling**: when an airship straddles a portal plane, each dim should only
  render its "valid side." Sable client renderer mixin adds a clip plane.

## Open questions by area

Each area has a "Findings" section (what we've learned) and an "Open" section (what
we still need to answer before we can implement).

---

## Area A — Sable sub-level lifecycle

### Findings (session 1, b7d454f-era code review of `sable-src/common/`)

**Key APIs on `SubLevelContainer` (`api/sublevel/SubLevelContainer.java`):**

- `allocateSubLevel(UUID uuid, int x, int z, Pose3d pose)` — creates a sub-level at
  specific plot coords with a specific UUID. **This is exactly the API we need for
  spawning a mirror.** Throws `IllegalArgumentException("Plot already exists")` if
  the plot is occupied; throws `IllegalArgumentException("Plot coordinates out of
  bounds")` if outside the plotgrid.
- `allocateNewSubLevel(Pose3d pose)` — picks the first empty plot and generates a
  new UUID. We'd use `allocateSubLevel` directly for mirrors to control UUID.
- `removeSubLevel(SubLevel, SubLevelRemovalReason)` — for cleanup. Two reasons:
  `REMOVED` (clears occupancy) and others (keep occupancy bit set — for unload paths).
- `getSubLevel(UUID)` — UUID lookup.
- `tick()` — iterates `allSubLevels.forEach(SubLevel::tick)` then
  `processSubLevelRemovals()` then `observers.forEach(o -> o.tick(this))`.
- **Observer pattern**: `addObserver(SubLevelObserver)` — observers fire on
  `onSubLevelAdded`, `onSubLevelRemoved`, and per-tick.

**Allocation flow**:
1. `allocateSubLevel(uuid, x, z, pose)` checks bounds + empty plot
2. Calls abstract `createSubLevel(globalPlotX, globalPlotZ, pose, uuid)` — impl in
   `ServerSubLevelContainer` or `ClientSubLevelContainer`
3. Adds to `subLevels[]` array + `allSubLevels` list + `subLevelsByUUID` map
4. Sets occupancy bit
5. Fires `observer.onSubLevelAdded(subLevel)` — **this is how physics auto-enrolls**
6. Marks `SubLevelOccupancySavedData.setDirty()` if on a `ServerLevel` (persists)

**Removal flow**:
1. Fires `observer.onSubLevelRemoved(subLevel, reason)` — **this is how physics
   auto-removes**
2. Calls `subLevel.onRemove()` (cleans up plot)
3. Clears the array slot, list entry, UUID map entry
4. Clears occupancy if reason was `REMOVED`

**Persistence**: `SubLevelOccupancySavedData` and `SubLevelSerializer` handle save/load.
For mirrors, we'd want to mark them as non-persistent — they should be transient.
Need to find the API surface for that.

**`SubLevelAssemblyHelper.assembleBlocks`** (`api/SubLevelAssemblyHelper.java:68`) is
the canonical "create a sub-level from world blocks" path used by Aero contraption
assembly. Reference for what setup steps a properly-initialized sub-level needs:
- Allocate via `container.allocateNewSubLevel(pose)`
- Create center chunk: `plot.newEmptyChunk(plot.getCenterChunk())`
- Move blocks via `moveBlocks(level, transform, blocks)`
- `pipeline.teleport(subLevel, position, orientation)` to set initial physics pose
- Tracking points, block entities, hanging entities handled separately

**For our mirror creation**, the major difference is: blocks aren't being moved from
world; they're being *copied* from another plot. That copy operation is not yet
identified — need to look at `LevelPlot` and `PlotChunkHolder` next session.

### Open (Area A)

- **How do we copy blocks from source plot to mirror plot?** No helper found yet.
  Likely need to: iterate `PlotChunkHolder`s in source plot → for each chunk, iterate
  block positions → write to mirror plot via some `setBlock` API. Mirror plot needs
  the corresponding chunks pre-allocated. Block entities need NBT copy.
- **How do we mark a mirror as non-persistent** (won't be saved/restored on world
  reload)? Need to investigate `SubLevelOccupancySavedData` and `SubLevelSerializer`.
- **`mirrorOf` field**: where do we store it? `ServerSubLevel.userDataTag` is a
  `CompoundTag` that's serialized — could use that, but we'd want it ignored at
  load time for the mirror itself. Maybe we just keep a server-side `Map<UUID, UUID>`
  outside Sable.
- **Does `markRemoved()` on a sub-level cleanly cascade through observers**, including
  Sable's physics + tracking systems? (Probably yes per the observer pattern, but
  verify there's no special "save before removal" path we'd hit.)

---

## Area B — Sable physics pipeline + kinematic mode

### Findings

**`PhysicsPipeline` interface** (`api/physics/PhysicsPipeline.java`):

- `add(ServerSubLevel, Pose3dc)` — registers a sub-level as a dynamic body
- `remove(ServerSubLevel)` — opt out of physics
- `add(KinematicContraption)` / `remove(KinematicContraption)` — separate API for
  pose-driven bodies (more on this below)
- `readPose(ServerSubLevel, Pose3d dest)` — pipeline writes the sub-level's current
  pose into dest
- `teleport(subLevel, position, orientation)` — sets the pose externally (used
  during initial setup or NaN-recovery)

**`KinematicContraption` interface** (`api/sublevel/KinematicContraption.java`):
*Not* what it sounds like at first read. It's an interface for "contraptions attached
to a plot" — secondary contraptions that piggyback on a sub-level's plot for lift/drag
calculations. Methods: `sable$getPosition`, `sable$getOrientation`, `sable$blockGetter`,
`sable$getMassTracker`, `sable$liftProviders`. Used in
`ServerSubLevel.prePhysicsTick` at line 347: `for (KinematicContraption contraption :
plot.getContraptions())`. Probably for child contraptions attached to a parent
airship. **Not directly usable as the "kinematic mirror" abstraction we want.**

**`SubLevelPhysicsSystem`** (`sublevel/system/SubLevelPhysicsSystem.java`):

- Implements `SubLevelObserver`. Auto-enrolls server sub-levels on add, auto-removes
  on removal:
  ```java
  @Override public void onSubLevelAdded(SubLevel subLevel) {
      if (subLevel instanceof ServerSubLevel s) this.pipeline.add(s, s.logicalPose());
  }
  @Override public void onSubLevelRemoved(SubLevel subLevel, ...) {
      if (subLevel instanceof ServerSubLevel s) this.pipeline.remove(s);
  }
  ```
- `getPipeline()` exposes the pipeline (line 411).
- Physics flow per tick (`tickPipelinePhysics`):
  1. `pipeline.prePhysicsTicks()`
  2. For each substep (multiple per tick):
     - `prePhysicsTickBegin()` on each sub-level
     - `updateMergedMassData()` on each
     - `prePhysicsTick()` on each (applies lift/drag/floating-block forces)
     - `pipeline.physicsTick(substepTimeStep)` — actual sim step
     - `processSubLevelRemovals` (handles fragile-block break)
     - **`updateAllPoses(container)`** — pulls poses from pipeline back to sub-levels
  3. `pipeline.postPhysicsTicks()`

- `updateAllPoses` iterates ALL non-removed sub-levels and calls `updatePose(s)` which
  reads `pipeline.readPose(s, storage)` and writes to `s.logicalPose()`. **This is
  where physics overwrites a sub-level's pose.**
- NaN-detection at line 299-311 — if `readPose` returns NaN, `recoverSubLevel` fires
  which re-adds the body to the pipeline. **So we can't just `pipeline.remove` and
  expect the sub-level to stay un-tracked — the recovery path would re-enroll it.**

**Kinematic mode strategy options:**

| Option | Approach | Mixin surface | Pros | Cons |
|---|---|---|---|---|
| A | Mixin `onSubLevelAdded` to skip pipeline.add for mirrors | 1 mixin on `SubLevelPhysicsSystem` | Clean, mirror never enrolls | Need mirror flag visible to mixin |
| B | Mixin `updateAllPoses` to skip mirrors during readback | 1 mixin on `SubLevelPhysicsSystem` | Lets physics run (wasted CPU) but we authoritatively set pose | Same as A but worse: simulation runs for no reason |
| C | After alloc, `pipeline.remove(mirror)`, then intercept `recoverSubLevel` to NOT re-enroll | 2 mixins | More precise control | More moving parts |
| D | Set mirror's pose AFTER updateAllPoses each tick (overwrite physics) | 1 mixin on tick TAIL | Simplest code | Physics still runs, mass/forces wasted |

**Recommended**: Option A. Pre-empt enrollment. Cleanest separation.

**Flag mechanism**: duck interface on `ServerSubLevel`. Mixin adds a unique field +
accessor (e.g., `ipl$isKinematicMirror() : boolean`). Our `onSubLevelAdded` skip
condition reads it.

### Open (Area B)

- **What does `pipeline.readPose` return if a body isn't in the pipeline?** Need to
  read `Sable.createPhysicsPipeline` impl (probably JOLT or RapierJVM backend).
  Affects whether Option A works as expected vs. needing Option B too.
- **For the mirror, do we still want `prePhysicsTick` to run?** It generates lift/drag
  forces that the pipeline would apply. If the mirror isn't in the pipeline, those
  forces have no target — wasted work but harmless. To save CPU, skip
  `prePhysicsTick` for mirrors too.
- **What about `updateMergedMassData` / `prePhysicsTickBegin`?** Same as above.
  Cheapest path: skip the whole physics substep iteration for mirrors. Means mixin
  on `tickPipelinePhysics` to filter `container.getAllSubLevels()` for mirror exclusion.
- **What about block-change physics signals** like `pipeline.handleChunkSectionAddition`
  triggered when we *copy* blocks to the mirror plot? Would those try to add chunks
  to the physics pipeline for a mirror that isn't enrolled? May error or silently
  succeed-with-no-effect.
- **Mass tracker for mirror**: do we need to build it? `ServerSubLevel.buildMassTracker`
  exists. For a kinematic mirror, mass doesn't matter for simulation, but it might
  matter for things like player collision raycast weight, block-break-physics, etc.
  Safest to build it the same way as source (it's pure block data → mass).

---

## What the v1 spawn-mirror procedure looks like

Putting Area A+B together, here's the rough flow for **spawning a kinematic mirror in
the destination dim**:

```java
// In dest dim (the dim we're spawning the mirror INTO):
ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);

// 1. Pick a free plot in dest container (or compute deterministic plot from sourceUuid)
Vector2i plotPos = destContainer.getFirstEmptyPlot();  // (need to expose this; currently private)

// 2. Compute mirror pose: portal-mapped from source.logicalPose()
Pose3d mirrorPose = portalTransform.applyToPose(sourceSubLevel.logicalPose());

// 3. Allocate. (The IPL mirror-skip mixin on SubLevelPhysicsSystem.onSubLevelAdded
//    inspects a flag on the new sub-level. We need to set the flag BEFORE
//    allocateSubLevel returns, but allocateSubLevel fires the observer
//    immediately on add. So either:
//    (a) pre-register the UUID as "incoming mirror" in a Map, mixin checks Map; OR
//    (b) intercept createSubLevel and set the flag on the just-created instance
//        before observer.onSubLevelAdded fires.)
ipl$mirrorRegistry.markIncoming(mirrorUuid, sourceUuid);
ServerSubLevel mirror = (ServerSubLevel) destContainer.allocateSubLevel(
    mirrorUuid, plotPos.x, plotPos.y, mirrorPose
);
// At this point onSubLevelAdded has fired; our mixin saw the UUID in the
// registry and skipped pipeline.add. Mirror is allocated but not in physics.

// 4. Copy blocks from source plot → mirror plot.
//    [API TBD — see Area A "Open"]
SableBlockCopy.copyPlotBlocks(sourceSubLevel.getPlot(), mirror.getPlot());

// 5. Build mass tracker (for collision queries; not used by sim since not enrolled).
mirror.buildMassTracker();

// 6. Mark as transient (don't persist).
//    [API TBD — see Area A "Open"]
ipl$markTransient(mirror);

// 7. Wire it up to receive pose updates from source each server tick.
//    [Done in a per-tick hook on SubLevelTrackingSystem or similar]
sourceMirrorRegistry.put(sourceUuid, mirror.getUniqueId());

return mirror;
```

Per-tick mirror pose sync (server-side, in source dim's tick):

```java
ServerSubLevel source = ...;
ServerSubLevel mirror = lookupMirror(source);
if (mirror != null && !mirror.isRemoved()) {
    Pose3d mirrorPose = portalTransform.applyToPose(source.logicalPose());
    mirror.logicalPose().set(mirrorPose);
    // The mirror's tick() will pick up the new pose for bounding box update.
    // The mirror is NOT in the physics pipeline, so updateAllPoses doesn't
    // overwrite this.
}
```

This pose change in the mirror will be observed by Sable's `SubLevelTrackingSystem`
in the dest dim (which runs as a per-dim observer), which will broadcast bounds +
movement updates to mirror-tracking players in the normal flow. No special networking
required.

## Mixin surface estimate (Area A+B coverage only)

So far, we need:

1. **`SubLevelPhysicsSystem.onSubLevelAdded`** — pre-empt enrollment for mirror UUIDs.
2. **`SubLevelPhysicsSystem.tickPipelinePhysics` or `updateAllPoses`** — skip mirror
   sub-levels during pose readback (depends on Option A vs B above).
3. **`ServerSubLevel`** — add duck-interface field for "is kinematic mirror" flag.
4. **`ServerSubLevel.tick()` or `prePhysicsTick`** — possibly skip force application
   for kinematic mirrors (CPU saving).
5. **Persistence hook (TBD)** — skip mirror serialization.

Plus the not-yet-researched:

6. Per-tick mirror pose driver (probably a new server-side helper class, not a mixin —
   could hook into our existing `SableCrossDimTrackingMixin.tick HEAD`).
7. Block-copy helper (probably not a mixin, just a utility class using Sable's APIs).
8. Lifecycle triggers (when to spawn / despawn / hand off authority — needs IP
   portal awareness — Area D research).
9. Sable client renderer clip plane (Area F research).
10. Aero integration (Area C research).

## Session 1 takeaways

The good news: **Sable's lifecycle and physics APIs are clean.** We can spawn a
sub-level with arbitrary UUID, opt it out of physics with a single mixin, and drive
its pose externally — exactly the substrate the mirror approach needs.

The not-fully-resolved questions: how to copy blocks plot-to-plot (probably tractable
via the existing `PlotChunkHolder` API; need to look at `LevelPlot` next), and how
to skip persistence. Both fit cleanly into "small additional helper classes" rather
than "new mixin surface."

Next session: **Area C — Create Aero contraption integration.** What does Aero
expect about the sub-level it's attached to, and can a mirror sub-level coexist with
Aero's controller-block / contraption identity model.

---

## Area C — Create Aero contraption integration

### Findings (session 2, monorepo at `simulated-src/`)

The repo cloned is the Aeronautics + Simulated monorepo. Contains two mods:
- `aeronautics/` — `dev.eriksonn.aeronautics.*` (the user-facing airship blocks,
  balloons, propellers, levitite, etc.)
- `simulated/` — `dev.simulated_team.simulated.*` (lower-level framework/utils used
  by Aero)

35 Aero source files import from `dev.ryanhcode.sable`. Aero is fully built on top
of Sable as its substrate.

**Aero's "airship" has no separate identity from a Sable sub-level.** No
contraption UUID, no controller-block-as-identity. The sub-level UUID *is* the
airship identity. Aero attaches state via per-level maps and per-block-position
metadata. Specifically:

- **Balloons** (hot-air structures with a controller block + airtight enclosure):
  - Stored in `BalloonMap.MAP.get(level)` — keyed by controller `BlockPos`.
  - Each `Balloon` has a `controllerPos` (a block in the airship), a `graph` of
    enclosed cells, a set of `heaters` (block entities providing lifting gas).
  - Lifecycle observed via `BalloonMap.BalloonSubLevelObserver`, registered as a
    `SubLevelObserver` on the container (`AeronauticsCommonEvents.onSubLevelContainerReady`).
- **Lift providers**: blocks implementing `BlockSubLevelLiftProvider`. Per-block,
  scanned by Sable each physics tick from `plot.getLiftProviders()`. Pure block-
  level — no separate state.
- **Levitite crystallizers**: `LevititeCrystallizerManager`. Per-level state about
  crystal propagation. Not tied to a sub-level per se.

**The auto-cascade flow is the load-bearing insight for us:**

Sable's `SableCommonEvents.handleBlockChange(level, chunk, x, y, z, oldState, newState)`
fires on every block change in any Sable-tracked level, **including plot-chunk
changes**. The handler:
1. Updates the plot chunk's mass/heatmap/floating-block state.
2. Aero's `SableCommonEventsMixin` injects at HEAD and calls
   `AeronauticsCommonEvents.onBlockModifiedEvent` → `BalloonMap.updateNearbyBalloons`.

So **if we write blocks into the mirror's plot via Sable's normal block-setting API,
Aero's Balloon (and Sable's mass/heatmap/floating-block) state updates automatically
on the dest side.** No need to manually wire up Aero state.

**Aero's hooks into `SubLevelAssemblyHelper`** (Aero's
`SubLevelAssemblyHelperMixin`) only fire during the canonical assembly flow
(blocks-in-world → sub-level). They're irrelevant for mirror creation because we're
copying plot → plot, not world → plot. We must NOT call `assembleBlocks` for the
mirror — we need a different path that goes directly through chunk block sets.

### Implications for the mirror approach

**Aero does not block our design.** Each dim's Aero state attaches independently to
its sub-level via block-change events. Source and mirror each get their own
balloon, their own lift-provider scan, their own heatmap. They are "the same
airship" only at our (IPL's) layer — Aero treats them as two unrelated airships
sharing identical block layouts.

For Phase 1 (atomic teleport, no straddling): trivially fine. Destroy source →
its balloon gets GC'd via removal observer. Create dest sub-level → blocks copy
→ Aero's `updateNearbyBalloons` fires → dest balloon appears. No state transfer
needed (the dest balloon will rebuild from controller block + airtight cells in
the new plot).

For Phase 2 (mirror without straddling): source remains authoritative, mirror is
kinematic. Both sides have their own balloon. Source balloon runs heat/gas
simulation and feeds the physics pipeline (which moves the source). Mirror balloon
*also* runs heat/gas (because Aero auto-attaches), but the forces have nowhere to
apply (mirror not enrolled in physics pipeline) — wasted CPU but no
correctness concern. At handoff, the source is destroyed and the mirror takes
over physics enrollment.

For Phase 3 (straddling): same as Phase 2 with longer-lived mirror. Aero's
internal state on the dest side may drift slightly from source (different heat
exchange timings, etc.) but as long as we don't tell the dest balloon to apply
physics forces, the discrepancy is purely cosmetic.

### Aero-specific risks for transit

1. **`ServerBalloon` does its own physics tick** (`AeronauticsCommonEvents.physicsTick
   → BalloonMap.physicsTick(level, timeStep)`). This computes heat/gas state per
   balloon. On the mirror side, this work is wasted but presumably harmless.
   *Mitigation*: optionally skip dest-side balloon physicsTick when the balloon's
   sub-level is marked as a kinematic mirror. Probably defer to phase 4 polish.

2. **`LevititeCrystallizerManager`** is per-level. If an airship has crystallizers,
   we'd need to handle their state separately. Niche case; defer.

3. **Aero block-entity tickers**: many Aero block entities tick state (heaters,
   propellers, bearings). On the mirror side, these would tick if the chunks are
   loaded. Same wasted-work concern. Probably want chunk-level "this is a mirror,
   don't tick" guard for v2 polish.

4. **Inter-block-entity references**: Aero has things like
   `HotAirBurner → BalloonController` relationships established at runtime
   (e.g., the burner registers itself with the balloon when first ticked). When we
   copy blocks, those runtime references won't be wired. The auto-cascade
   via block-change events would re-wire them as long as the references rebuild
   from block placement (which they appear to, per `checkHeaters` in `Balloon.java`).
   Worth verifying empirically once we have a v1 build.

5. **Network sync to client**: Aero sends balloon state to clients via its own
   packets (separate from Sable's sub-level packets). For a mirror that doesn't
   exist on the client until tracking starts, this should work naturally —
   when the player crosses into the dest dim, Sable's start-tracking flow fires,
   and Aero's per-block-entity sync delivers heater/balloon state via block-entity
   updates.

### Open (Area C)

- **Does `chunk.setBlockState(...)` on a plot chunk fire `SableCommonEvents.handleBlockChange`?**
  Sable's `SableCommonEvents.handleBlockChange` is called from somewhere — presumably
  a Sable mixin on `LevelChunk.setBlockState` or similar. Need to verify so we can
  trust the auto-cascade. (Highly likely yes given the way Aero attaches to it, but
  worth one grep.)
- **Block entity copy**: blocks copy via chunk setBlockState; block entities need
  manual `setBlockEntity(pos, BlockEntity.loadStatic(pos, state, nbt))` after the
  block is placed. Aero block entities likely have NBT serialization for their
  internal state — should "just work" as long as we save+restore NBT through the
  copy.
- **Plot chunk creation pattern**: how do new plot chunks get created in Sable
  when blocks are placed via API? Does Sable auto-create chunks on demand for plot
  block changes, or do we need to pre-allocate via `plot.newEmptyChunk(...)`?
  (`SubLevelAssemblyHelper` calls `plot.newEmptyChunk(plot.getCenterChunk())` only
  once at start; doesn't seem to do it during each block copy.)

### Session 2 takeaways

**Aero is a non-issue for our design.** It auto-attaches via block-change events and
sub-level observers, so creating a mirror sub-level with copied blocks gets
Aero's balloon system rebuilt for free on the dest side. No identity conflict.
No manual state transfer needed. The only concern is wasted CPU on the mirror
side (running balloon heat simulation, block entity ticks) which is a polish
issue, not a correctness one.

This is great news for the mirror approach. The next session (Area D + E — IP
machinery) will tell us how to hook portal detection and clip-plane rendering;
between those and what we have now we'll have everything needed for a Phase 1
MVP design.

Next session: **Area D — IP entity teleport and coord mapping; Area E — IP's
portal-view chunk clipping** (mostly internal to our own repo).

---

## Area D — IP entity teleport + coord mapping

### Findings (session 3, internal IP code at `src/main/java/qouteall/imm_ptl/core/`)

**Portal class** (`portal/Portal.java`) is an `Entity` subclass with everything we
need for coord mapping and lifecycle queries:

| API | Signature | Use |
|---|---|---|
| `transformPoint` | `Vec3 → Vec3` | Maps source-world pos to dest-world pos |
| `transformLocalVec` | `Vec3 → Vec3` | Local-frame vector transform (orient axes) |
| `getRotation` | `() → DQuaternion?` | Rotation quaternion the portal applies (nullable) |
| `getRotationD` | `() → DQuaternion` | Same but non-null wrapper |
| `getDestDim` | `() → ResourceKey<Level>` | Destination dimension |
| `getOriginPos` / `getDestPos` | `() → Vec3` | Geometric anchors |
| `getNormal` | `() → Vec3` | Portal plane normal |
| `getInnerClipping` | `() → Plane?` | Plane to clip against during portal-view render |
| `axisW`, `axisH` | fields, `Vec3` | Portal plane basis |
| `scaling` | field, `double` | Uniform scale for scale portals (usually 1) |

**Portal enumeration** — `Portal extends Entity`, idiomatic query pattern lifted
from `ServerTeleportationManager.getEntitiesToTeleport`:
```java
List<Portal> nearby = level.getEntitiesOfClass(
    Portal.class,
    airshipWorldBoundingBox.inflate(approachThreshold),
    p -> p.isTeleportable()
);
```

**Entity teleport machinery** (`ServerTeleportationManager`):
- `teleportEntityGeneral(Entity, Vec3 targetPos, ServerLevel targetWorld)` —
  main public API. Players go through `forceTeleportPlayer`, others through
  `teleportRegularEntityTo`.
- `teleportRegularEntityTo(E, ResourceKey<Level>, Vec3)` — same-dim move or
  cross-dim `changeEntityDimension`.
- A sub-level is **not an `Entity`** so none of these apply directly to it — but
  the *coord mapping* on Portal is reusable, and `teleportEntityGeneral` is
  exactly what we want for moving riders/passengers when the airship transits.

### Phase 1 (atomic) sub-level transit procedure

Putting Sessions 1+2+3 together:

```
1. Per server tick, scan each ServerSubLevel against nearby portals:
   List<Portal> nearby = sourceLevel.getEntitiesOfClass(
       Portal.class, airship.globalBoundingBox.inflate(N), Portal::isTeleportable
   );
   For each portal whose plane the airship's center crosses since last tick:

2. Compute mapped pose:
   Vec3 sourcePos = airship.logicalPose().position();
   Vec3 destPos = portal.transformPoint(sourcePos);
   Quaterniond destOrient = composeWithPortalRotation(
       airship.logicalPose().orientation(),
       portal.getRotationD()
   );

3. Resolve destination level + container:
   ServerLevel destLevel = server.getLevel(portal.getDestDim());
   ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);

4. Find a free plot in destContainer + allocate dest sub-level:
   ServerSubLevel dest = (ServerSubLevel) destContainer.allocateSubLevel(
       airship.getUniqueId(),  // reuse source UUID for atomic teleport
       plotPos.x, plotPos.y, destPose
   );

5. Copy blocks plot → plot via Sable block-setting API.
   handleBlockChange auto-fires on each set, cascading to:
     - Sable mass tracker / heatmap / floating-block controller
     - Aero balloon updates via BalloonMap.updateNearbyBalloons

6. Teleport entities standing on/inside the airship to dest dim via
   ServerTeleportationManager.teleportEntityGeneral.

7. Remove source: sourceContainer.removeSubLevel(airship, REMOVED).
```

**UUID handling decision** for atomic teleport: reuse source UUID in dest.
Sable's `allocateSubLevel(uuid, ...)` accepts an explicit UUID — clean
identity continuity for clients. (The mirror UUID question from Phase 0 is for
Phase 2+ where source and mirror coexist.)

### Open (Area D)

- **`DQuaternion` ↔ `org.joml.Quaterniond` conversion** — Sable uses JOML, IP
  uses `DQuaternion`. Need to find/build a converter. Likely just constructing
  `new Quaterniond(dq.x, dq.y, dq.z, dq.w)`.
- **Rotation composition order** — `portalRot * airshipRot` (apply airship
  first, then portal) per standard convention. Verify empirically.
- **When in the tick to fire transit** — probably TAIL of source dim's
  `SubLevelContainer.tick()` after physics has completed. Pick a clean phase
  so we don't mid-step.
- **Passenger handling** — per Phase 0 decision, mounted-passenger handling
  deferred. Phase 1 leaves Aero-seat passengers behind in source dim. Document.

---

## Area E — IP portal-view chunk clipping

### Findings

IP implements a plane-based clip mechanism for terrain rendering during portal
view, with two paths:

1. **GL fixed-function clip plane** — `GL11.glEnable(GL_CLIP_PLANE0)` with plane
   equation. Toggled by `FrontClipping.enableClipping()` / `disableClipping()`.
   Configurable via `IPGlobal.enableClippingMechanism`.
2. **Shader uniform** — `IEShader.ip_getClippingEquationUniform()` exposes a
   `Uniform` (Vec4 `(a, b, c, d)` for `a*x + b*y + c*z + d > 0` keeps fragment).
   Every shader IP patches has this uniform. Updated by
   `FrontClipping.updateClippingEquationUniformForCurrentShader(boolean)` during
   render passes.

**API surface**:

| API | Use |
|---|---|
| `Plane(Vec3 pos, Vec3 normal)` (record in q_misc_util) | Plane representation |
| `PortalRendering.getActiveClippingPlane() → Plane?` | Current rendering clip (or null) |
| `Portal.getInnerClipping() → Plane?` | Portal's "inside" clip plane |
| `FrontClipping.setupInnerClipping(Plane, modelView, adjustment)` | Sets clip for inner render |
| `FrontClipping.updateClippingEquationUniformForCurrentShader(boolean)` | Pushes equation to active shader |

### Implications for sub-level transit

Two regimes where Sable's sub-level renderer needs to participate in IP's clipping:

**Regime A** (Phase 3+, straddling): user is in source dim looking at an airship
that's partially past the portal plane. IP's portal-view clipping is *not*
active (we're not rendering through a portal). We need a new per-sub-level
clip-plane mechanism — the airship's "transit context" tells the renderer
which portal plane to clip against.

**Regime B** (already shipped behavior, build `5d26789`): looking through the
portal at a source-dim airship from dest dim. IP's `getActiveClippingPlane()` is
already set. Currently Sable's sub-level renderer ignores it and draws the
entire sub-level. With cross-dim retention working (the current production
state) and atomic teleport in Phase 1, this is OK as long as no airship is
*also* straddling the portal at the same time. Once Phase 2+ allows
straddling, Sable must clip in Regime B too.

Both regimes need the same Sable-side capability: **apply a clip plane during
sub-level render**. The clip-plane *source* differs (active IP plane vs
per-sub-level override) but the *mechanism* is shared.

### Approach options for getting clipping into Sable's renderer

1. **Hardware GL clip plane** (`GL_CLIP_PLANE0`). Works regardless of shader.
   Set up via `FrontClipping.setupInnerClipping(...)` before Sable renders, unset
   after. Simplest but compatibility concerns on non-fixed-pipeline configs
   (Sodium / Iris on certain backends).
2. **Inject IP's clip uniform into Sable's shaders.** Mixin on Sable's shader
   compilation to add the `ip_clipping` uniform + GLSL snippet. Most invasive.
3. **Sable-side per-sub-level clip API.** Add a clip plane field on
   `ClientSubLevel`, render with that clip if set. Lightest impact on IP,
   requires understanding Sable's render pipeline (Session 4).

### Open (Area E)

- **Does GL hardware clipping work on all targets** (Mac Metal-translated GL,
  Sodium renderer pass)? `IPCGlobal.useFrontClipping` is the IP flag for
  enabling/disabling.
- **Sable's shader landscape** — entirely Session 4's domain. Custom shaders for
  sub-level chunk rendering? Water occlusion? Entity rendering inside sub-level?
- **Phase 1 doesn't need clipping** — atomic teleport never has the airship
  straddling. Clip work is Phase 2+ scope.

### Session 3 takeaways

For Phase 1 MVP, **IP gives us everything we need**:
- Coord mapping via `portal.transformPoint(...)` and `getRotationD()`
- Portal enumeration via `level.getEntitiesOfClass(Portal.class, ...)`
- Destination dim via `portal.getDestDim()`
- Entity teleport for riders via existing `teleportEntityGeneral`

For Phase 2+ (straddling + render clipping), IP gives us the `Plane`
representation and clipping infrastructure (GL + shader uniform). The remaining
question is how to plumb that into Sable's sub-level renderer — **Session 4**.

Next: **Area F — Sable client renderer.** This will determine the shader/clip
integration strategy and complete the design picture before we synthesize the
Phase 1 implementation plan.

---

## Area F — Sable client renderer

### Findings (session 4, `sable-src/common/.../sublevel/render/`)

**Renderer architecture:** Sable picks a `SubLevelRenderDispatcher` based on
loaded mods (`SubLevelRenderer.SelectedRenderer`):

- `VANILLA` — used when Sodium is NOT present. `VanillaSubLevelRenderDispatcher`.
- `SODIUM_REACHAROUND` — used when Sodium is present.
  `ReachAroundSubLevelRenderDispatcher` extends the vanilla one but inherits
  `renderSectionLayer` from it (only overrides chunk-baking machinery).

**Both dispatchers ultimately use vanilla `ShaderInstance`** — even in
Sodium-loaded environments, Sable "reaches around" Sodium and uses vanilla
`SectionRenderDispatcher` for sub-level chunk baking + vanilla
`renderSectionLayer` for the draw pass. Confirmed via
`ReachAroundSubLevelRenderDispatcher extends VanillaSubLevelRenderDispatcher`
(no `renderSectionLayer` override).

**How Sable's renderer gets called from vanilla `LevelRenderer`:**

Sable mixins `net.minecraft.client.renderer.LevelRenderer.renderSectionLayer`
at TAIL (see `sable/mixin/sublevel_render/impl/vanilla/LevelRendererMixin.java`):
```java
@Inject(method = "renderSectionLayer", at = @At("TAIL"))
private void afterRenderSectionLayer(...) {
    SubLevelRenderDispatcher.get().renderSectionLayer(
        sublevels, renderType, shader, x, y, z, modelView, projection, partialTicks
    );
}
```

This is the critical insight: **Sable runs at TAIL of vanilla's
`renderSectionLayer`** — same render type, same shader, same camera state.

### Why IP's existing clip plane already applies for free

IP's `FrontClipping.updateClippingEquationUniformForCurrentShader(...)` is invoked
during `LevelRenderer.renderSectionLayer` (via IP's own `MixinLevelRenderer`
that does the portal-view chunk clipping). By the time Sable's TAIL inject fires,
the shader's `ip_clipping` uniform is already set with the active portal's clip
equation.

Sable's sub-level chunks go through the same shader binding because:
1. Same `ShaderInstance shader` is passed through to Sable's dispatcher
2. Sub-level vertex positions in shader are in world-space minus camera
3. IP's clip equation is in world-space-minus-camera (per `FrontClipping
   .getClipEquationInner`)

**Net result: portal-view clipping of Sable sub-levels should already work** —
when a player looks through a portal, the sub-level on the other side gets clipped
to the portal plane via IP's existing shader uniform. We just need to verify
empirically; the architecture says it should be free.

This downgrades Area E's open question dramatically. The "Sable doesn't
participate in IP clipping" concern was overstated.

### For Phase 3+ straddling (regime A in Area E)

When a sub-level straddles a portal *outside* of a portal-view render (i.e., we
want to clip the airship when looking at it directly, because half of it is past
the portal plane and the mirror is rendering the other half in dest dim), we need
a way to push our own clip plane that's NOT tied to portal-view rendering.

**Approach (deferred to Phase 3 implementation):** mixin
`VanillaSubLevelRenderDispatcher.renderSectionLayer` at HEAD to check if any
of the sublevels being rendered are in transit (have an associated portal in
our `IplTransitRegistry`). If yes, push the clip plane via
`FrontClipping.setupInnerClipping(transitPlane, modelView, 0)` + update uniform
on the bound shader. At TAIL, restore (push/pop semantics or `disableClipping`).

That mixin would target Sable's dispatcher, which sits inside *our* mixin layer
(we already mixin Sable from `ipl_sable.mixins.json`). Low-risk approach.

### Block entity rendering and other passes

Sable also renders block entities on sub-levels via `renderBlockEntities` and
single-block sub-levels via `renderAfterSections`. Both go through vanilla
shaders/render machinery. Same clip-plane-for-free reasoning applies.

For Phase 3+ straddling, we'd want the same clip-plane push for these passes.
Easy enough — same dispatcher, same TAIL/HEAD point.

### Open (Area F)

- **Empirical verification**: build Phase 1, look at an airship through a portal,
  confirm the sub-level renders correctly clipped on the source side of the
  portal view. (Phase 1 itself doesn't need clipping, but cross-dim viewing
  already works in production so we can verify clipping works there.)
- **Iris / shader-mod compatibility**: when Iris is loaded with shader packs,
  the shader path can change. IP has Iris integration in
  `compat/iris/`. Sable also has Iris-aware code in `mixin/render/iris/`.
  Probably fine but worth a spot-check.
- **`FancySubLevelRenderDispatcher`** — listed in the render package but not
  picked by `SelectedRenderer`. Probably experimental / legacy. Ignore for now.

### Session 4 takeaways

The Sable renderer doesn't need any new mixin surface for Phase 1 or even
Phase 2. Portal-view clipping is already wired by inheriting the bound shader
state from vanilla `LevelRenderer.renderSectionLayer` (TAIL inject).

Phase 3 straddling will need one new mixin on Sable's dispatcher to push a
per-sub-level clip plane (via `FrontClipping.setupInnerClipping`) around its
own renderSectionLayer. ~1 mixin, ~20 lines of code.

**The design is complete.** Next session: synthesis, mixin surface count,
implementation phase plan for Phase 1 atomic teleport MVP.

---

## Phase 0 synthesis — concrete plan for Phase 1

### What we know now (combining sessions 1-4)

**Sable side:**
- `SubLevelContainer.allocateSubLevel(uuid, x, z, pose)` creates a sub-level
  with our chosen UUID. Auto-cascades through observers: physics enroll,
  tracking add, Aero attach (via `BalloonMap.BalloonSubLevelObserver`).
- `container.removeSubLevel(subLevel, REMOVED)` symmetric for cleanup.
- `LevelPlot.newEmptyChunk(ChunkPos)` allocates a plot chunk. Once allocated,
  vanilla `LevelChunk.setBlockState(...)` writes blocks; Sable's mixin on that
  fires `handleBlockChange` which cascades to mass tracker, heatmap, floating
  block controller, and Aero's `BalloonMap.updateNearbyBalloons`.
- `BlockEntity` copy is plain Minecraft API: save source BE NBT, create new BE
  via `BlockEntity.loadStatic(pos, state, nbt, registries)`, set on dest chunk.
- Each `ServerSubLevel` already exposes `logicalPose()` (mutable Pose3d), so
  setting the dest pose at allocate-time is straightforward.

**Aero side:**
- Nothing to do. Block-change cascade handles balloon attachment automatically.

**IP side:**
- `Portal extends Entity` so `level.getEntitiesOfClass(Portal.class, bbox, filter)`
  enumerates portals near a sub-level.
- `portal.transformPoint(Vec3)` maps positions; `portal.getRotationD()` for
  rotation composition; `portal.getDestDim()` for target dim.
- `ServerTeleportationManager.teleportEntityGeneral(entity, mappedPos, destLevel)`
  for moving riders (players + mobs standing on the airship).
- Portal-view rendering already clips Sable's sub-levels for free (Sable's
  TAIL inject into vanilla `renderSectionLayer` inherits IP's clip uniform).

### Phase 1 mixin surface estimate

**Zero new mixins.** The entire Phase 1 implementation lives in plain helper
classes invoked from per-tick code. Per-tick code itself can either:

- (a) Live in a new class wired up via `Aeronautics.init`-style entry point —
  but we don't have one of those for our compat layer; or
- (b) Hook off one of our existing mixins. The natural fit is **adding a TAIL
  inject to `ServerLevel.tick`** (we already mixin ServerLevel adjacent code in
  `sable$tickPlotContainer` per Sable's pattern) or piggybacking on
  `SubLevelContainer.tick` TAIL inject (`SableCrossDimTrackingMixin` already
  has injects on `SubLevelTrackingSystem.tick`).

The cleanest option: **add a tiny new mixin** `SableSubLevelTransitMixin` that
injects at TAIL of `ServerSubLevelContainer.tick` (after physics, after pose
sync, before next tick). Calls into our controller class. That's ~10 lines of
mixin code plus the helper class.

### Phase 1 file layout

```
src/main/java/ipl/sable/transit/
  ├── SableTransitController.java   — per-tick scanner + transit dispatcher
  ├── SableTransitOps.java          — static transit primitives:
  │                                    * computeMappedPose(sourceSL, portal)
  │                                    * spawnDestSubLevel(uuid, destLevel, pose)
  │                                    * copyPlotBlocks(srcPlot, destPlot)
  │                                    * teleportRiders(sourceSL, portal, destLevel)
  │                                    * removeSubLevel(sourceSL)
  └── PortalCrossingDetector.java   — geometric check: did airship center
                                       cross portal plane this tick?

src/main/java/ipl/sable/mixin/
  └── SableSubLevelTransitMixin.java — @Inject TAIL on ServerSubLevelContainer.tick
                                       to invoke SableTransitController.tick()
```

### Implementation order (4 commits)

**Commit 1: Lifecycle plumbing (no block copy yet)**
- Create `SableTransitController`, `PortalCrossingDetector`, `SableTransitOps`
  (stub copy).
- Add mixin to invoke controller each tick.
- On detected portal crossing:
  1. Allocate empty dest sub-level (no blocks).
  2. Remove source sub-level.
  3. Don't bother teleporting riders yet (they'd land in empty space).
- Test: spawn an airship, push it through a portal slowly. Source-dim airship
  should disappear when its center crosses the portal plane. Dest-dim should
  show a registered sub-level (verifiable via debug or `/sable` commands) but
  empty plot. Confirm no crashes, no errors, clean tracking transitions.

**Commit 2: Block copy**
- Implement `SableTransitOps.copyPlotBlocks` properly. For each loaded
  `PlotChunkHolder` in source plot:
  - `destPlot.newEmptyChunk(localChunkPos)` to allocate corresponding chunk
  - Iterate non-air block positions in source chunk
  - For each pos: get state + BE NBT (if any), write to dest chunk via
    `setBlockState` + `setBlockEntity`
- Sable's `handleBlockChange` cascade should fire automatically for each set,
  rebuilding mass tracker, heatmap, Aero balloon, etc. on dest side.
- Test: airship transit now produces a fully-formed airship in dest dim.
  Expected: Aero's balloon system rebuilds; airship has correct lift; physics
  resumes from the mapped pose.

**Commit 3: Rider teleport**
- Implement `SableTransitOps.teleportRiders`. Find entities whose bounding
  box overlaps the airship's bounding box. For each, compute mapped position
  via `portal.transformPoint(rider.position())`. Call
  `ServerTeleportationManager.teleportEntityGeneral`.
- Test: stand on airship, push through portal, end up on the dest airship
  at correct relative offset. (Mounted seat passengers excluded per Phase 0
  decision — they'll be left behind in source dim for v1.)

**Commit 4: Hardening + cleanup**
- Crossing detection robustness: hysteresis to prevent ping-pong if airship
  parks near the portal plane.
- UUID collision check: what if dest dim's container already has an
  airship with the same UUID? (Shouldn't happen but graceful failure.)
- Plot allocation: if dest container's plot grid is full, transit gracefully
  fails (airship stays in source dim).
- Logging at INFO level for transit events.

### Crossing detection design (PortalCrossingDetector)

```java
public static boolean didCrossThisTick(ServerSubLevel airship, Portal portal) {
    Vec3 thisTickCenter = vec3From(airship.logicalPose().position());
    Vec3 lastTickCenter = vec3From(airship.lastPose().position());

    Plane portalPlane = new Plane(portal.getOriginPos(), portal.getNormal());
    double thisSide = portalPlane.getDistanceTo(thisTickCenter);
    double lastSide = portalPlane.getDistanceTo(lastTickCenter);

    // Sign change indicates crossing
    return (thisSide >= 0) != (lastSide >= 0)
        && portalContainsPointInPlane(portal, thisTickCenter);  // within portal bounds
}
```

The "within portal bounds" check is necessary because the portal plane is
unbounded but the portal entity has a finite shape. IP's `PortalShape` /
`portalArea` are the relevant queries. Probably leverage IP's existing
collision logic.

### Validation plan for Phase 1

1. Spawn an Aero airship in overworld.
2. Place an IP nether portal.
3. Hover-fly the airship slowly toward the portal.
4. When the airship's center crosses the portal plane:
   - Source-dim view: airship disappears.
   - Players standing on deck: teleported to nether at correct positions.
   - Dest-dim view: airship reappears in nether with full block structure,
     mapped pose, working lift.
5. (Stretch) drive airship back through; reversible transit.
6. No crashes. No "ghost" sub-levels left behind. No "Plot already exists"
   errors from the dedupe mixin (transit should be clean enough not to trip
   it).

### Known limitations for Phase 1 (document in code)

- **Instant teleport, visually jarring.** Airship pops between dims. Half the
  airship may "vanish into the portal" if longer than the portal can swallow
  in one tick. The mirror approach (Phase 2) fixes this.
- **Mounted passengers left behind.** Players in Create Aero seats stay in
  source dim sitting on a now-gone seat entity. Will manifest as the player
  falling, or being booted from the now-empty seat. Phase 2/3 fixes.
- **Mid-transit modifications discarded.** If a player breaks a block on the
  airship in the middle of the same tick the airship transits, the change
  could be lost. Acceptable for v1.
- **Plot grid full.** If dest dim has all plot slots occupied, transit fails
  silently (airship stays in source). Logged at WARN.

### Session 5 takeaway: ready to ship Phase 1

The design fully covers Phase 1. We have:
- A concrete file layout (4 new Java files, 1 small mixin)
- A 4-commit implementation order, each independently testable
- A validation plan with a known-good repro scenario
- A list of acknowledged limitations to document inline

Phase 2 (kinematic mirror with handoff) and Phase 3 (true straddling with
clip plane) are mapped out in detail through the prior sessions; we can pick
those up after Phase 1 ships and we've validated the foundation.

Let's start writing Phase 1.
