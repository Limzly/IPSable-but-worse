# Atlas Portal Physics — Architecture Spec (v3)

Status: **decided direction (atlas world + image colliders) — M0 audit PASSED
(green light, Tier-1 gate cheaper than estimated; see
`atlas_portal_physics_audit_results.md`)**
Scope: IPSable bridge between Immersive Portals (IP) and Sable / Create Simulated,
now including changes to the vendored rapier engine fork.

> v2 (`dim_agnostic_portal_physics_spec.md`) decided per-Level scenes + dynamic clone
> bodies + servo/impulse-feedback coupling + authority swap. It shipped and works —
> and its residual artifacts are now the problem: servo jitter (worst in multi-straddle),
> one-substep coupling lag ("mushy" far-side contact), damped oscillation when wedged
> across the plane, and a growing pile of controller machinery (gains, damps, authority
> hysteresis, per-session fraction weighting) that tunes around symptoms instead of
> removing causes. v2's constraint landscape has also changed since it was decided:
>
> 1. The audit proved the natives are OURS to change: Rust source in-tree
>    (`natives/rapier/`, `natives/marten/`), working build loop, and we already ship
>    engine-adjacent extensions (clip regions, private chunk storage, the parallel-
>    solver race fix in `scene.rs:39-54`).
> 2. The engine itself is already a fork (`rapier3d = git ryanhcode/rapier`, pinned
>    rev `38e92f1`, `natives/rapier/Cargo.toml`) — "avoid forking the engine" died at
>    the v2 audit; the real cost is carrying a diff across rebases, and we carry one
>    already.
> 3. New product requirements exceed v2's ceiling: portals ON physics structures
>    (moving portals), and "non-Euclidean-engine" seamlessness as the quality bar.
>
> v1 (single scene + weld joints) was rejected on the voxel-grid conflict and on
> "months of engine work" for true portal planes. v3 dissolves both objections: the
> grid conflict by chart-keyed terrain storage (a data-structure change we own), and
> the engine work by scoping the solver change to a per-contact frame correction that
> is *near-zero for translation-only portals* — not the full "portal planes inside the
> engine" rewrite v1 priced.

## 1. Problem statement

A body straddling a portal must behave as ONE rigid body whose contacts happen to be
collected in two (or N) places. Every v2 artifact — jitter, lag, fighting servos,
wedged oscillation, authority thrash — is a structural consequence of representing it
as TWO dynamic bodies coupled by a controller. No gain schedule fixes that; the fix is
to stop having two bodies.

Requirements, in priority order:

1. Exact, zero-lag contact coupling through portals (rest a ship dead-still through a
   portal: no servo, no drift, no log spam, no jitter — at N simultaneous straddles).
2. Wedged-across-the-plane resolves exactly (in-solver), not as damped oscillation.
3. Same-dimension and cross-dimension portals are the same code path.
4. Portals anchored to physics sub-levels (moving portals) — phased, but the
   architecture must not preclude them.
5. Gameplay layer (redstone, BEs, Create actors, entities, other mods) continues to
   work via the v2 single-authority + projection design, which is retained unchanged.

## 2. Decided architecture

**One Rapier world containing an atlas of charts. A chart is a Euclidean coordinate
frame (one per Minecraft dimension) glued to other charts by portal isometries. Every
body's state lives ONCE, expressed in its anchor chart at natural coordinates. A body
straddling a portal gains IMAGE COLLIDERS (not a second body) in the far chart at the
portal-mapped pose; contacts against images are assembled as constraint rows on the
one real body, with the contact frame corrected through the portal isometry. One body,
one island, one solve.**

The servo, impulse feedback, authority swap, fraction weighting, bleed/rock damps, and
the dynamic clone body are deleted. Aperture clipping, pair exclusion, the gameplay
router, and the entity pose-map layer carry over.

### 2.1 Charts (the scene → chart migration)

- The N per-dimension `PhysicsScene`s collapse into one world (`SimulationSceneData`:
  one `RigidBodySet`, `ColliderSet`, `IslandManager`, broad/narrow phase, solver —
  `scene.rs:62-74`). Each dimension becomes a **chart id** on colliders and terrain.
- **JNI surface preserved**: `sceneId`/scene handles remain the Java-visible concept.
  Natively, a "scene handle" becomes a lightweight chart view over the singleton
  world (`initialize(sceneId,...)` mints a chart; every per-scene native call resolves
  handle → chart). Sable's Java pipeline and our eight bridge call sites (v2 audit,
  "Migration surface") keep compiling; `ipl$forwardTarget` indirection survives as-is.
- **Terrain layering (the v1 blocker, dissolved)**: `main_level_chunks: ChunkMap` and
  `octree_chunks` (`scene.rs:76-95`) become per-chart maps — `HashMap<ChartId,
  ChunkMap>` or a widened key. Each chart's voxel grid holds exactly its own
  dimension's terrain at natural coordinates; the "foreign terrain in the wrong
  frame" bug class stays inexpressible, same guarantee v2 §2.2 bought, now inside one
  world. `useDedicatedChunks` (ipl_ext.rs:106) is the shipped precedent for layered
  chunk storage.
- **Cross-chart contact isolation (AUDITED — route decided)**: dynamic↔dynamic via
  `InteractionGroups` chart bits — only 2 of 32 bits are used today (`groups.rs`);
  caveats: the fork's `InteractionTestMode::Or` is more permissive than AND (design
  masks so neither direction crosses, or switch the constants to And), and box
  colliders currently carry no groups and must be stamped. `filter_contact_pair` is
  NOT needed. **Terrain needs no per-chart colliders at all**: terrain is ONE
  giant-AABB collider whose voxels come from the chunk maps at narrow-phase time,
  and the dispatcher already selects chunk storage per-body via a `&dyn ChunkAccess`
  switch (`dispatcher.rs:305-311`) — chart isolation for terrain is chart-keyed
  chunk-map SELECTION by the dynamic collider's chart tag (images carry the far
  chart's tag). This also kills the cross-chart terrain-pair explosion in the
  (fork's BVH) broad phase at the source.
- f32 stays fine: charts keep natural per-dimension coordinates; nothing is offset.
- The hosting dimension contributes **no chart** (no terrain, no authoritative bodies
  post-migration — v2 §2.2 unchanged). Plot coordinates remain collider-local data.

### 2.2 Image colliders (the clone body, demoted to geometry)

- On straddle through portal P (chart A → chart B): the real body (anchored in A)
  gains image colliders in chart B — the same voxel collider data (the registry is
  global and cross-scene already, v2 audit §2.3), attached to the SAME rigid body,
  with a per-collider **portal transform**: effective pose = `P × bodyPose ×
  localPose`, chart tag = B.
- Engine change (vendored fork, AUDITED): the prefix rides `ColliderParent`
  alongside `pos_wrt_parent`, left-multiplied at the position-update chokepoint
  (`rigid_body_components.rs:1154`: `new_pos = parent_pos * pos_wrt_parent`) —
  broad phase and narrow phase both read the resulting `co.pos`, so both follow
  automatically. Three sibling composition sites get the same prefix (attach,
  propagate, user-changes); mass-properties composition must NOT (images contribute
  no mass). For the solver, the portal prefix/COM offset is stamped into a new
  `ContactManifoldData` field in the narrow-phase manifold loop
  (`narrow_phase.rs:999-1008` — the builders never see collider handles) and
  gathered per-lane like `data.normal`. Updated per substep (per step is enough for
  static portals; per substep once portals move).
- No mass mirroring, no servo, no gravity question, no velocity teleport channel —
  images are geometry, not bodies. Far-side friction/restitution come from the far
  chart's real terrain colliders, as in v2.
- Multi-straddle: N portals → N image sets on one body. Chained portals compose
  isometries into the image transform. Both are "more colliders," not more sessions.

### 2.3 Constraint assembly through the portal (the load-bearing engine change)

A contact between an image (body A imaged into chart B by `P = (R, t)`) and a native
chart-B collider (terrain or another ship) produces a manifold whose points/normals
are chart-B quantities, while body A's COM/velocity are chart-A quantities. The solver
must treat the contact as acting on A through P⁻¹:

- Image velocity at contact point p: `v_img(p) = R·v_A + (R·ω_A) × (p − P(com_A))`.
- Impulse λ·n at p maps back to A as force `Rᵀ·λn`, torque
  `(P⁻¹p − com_A) × Rᵀ·λn`.

Two tiers fall out:

- **Tier 1 — translation-only portals (R = I), the overwhelming majority**: velocities
  and normals are frame-identical; the ONLY correction is the lever arm — computed
  against the mapped COM: `p − (com_A + t)` instead of `p − com_A`. A per-constraint
  COM substitution for imaged colliders, in the contact-constraint builder. This tier
  yields **exact in-solver coupling with a near-trivial engine patch** and is the M2
  milestone gate.
- **Tier 2 — rotated portals, ANY fixed R (90° or arbitrary)**: additionally rotate
  body A's Jacobian blocks by R (linear part; lever-arm cross term) and map its
  velocity into the contact frame. Nothing in the math or the solver is quarter-turn
  specific — a generic per-lane R multiply serves 37° as well as 90°, and the narrow
  phase already handles arbitrarily oriented voxel colliders (ships rotate freely
  against terrain today), so images at arbitrary rotation add NO new collision
  requirement. Re-anchoring likewise composes any isometry into the body pose.
  **The 90° gate is a GAMEPLAY gate, not physics** — it lives in the actor/router
  layer (§2.9), where block mutations must land on grid; physics traversal of
  arbitrarily rotated portals comes with Tier 2 for free. Localized to constraint
  assembly, but touches the solver's hot path — see the SIMD hazard (§5.1). If the
  wide-lane batcher can't take per-lane frame rotation, route imaged-pair manifolds
  through a non-SIMD/generic constraint path. Tier 2 is severable: until it lands,
  rotated portals keep the v2 servo path (kill-switch coexistence, §6).
- Both bodies dynamic (ship-vs-ship through a portal): same math, one body imaged per
  pair. Half-open convention picks WHICH side hosts the pair (A-image vs B-native in
  chart B, or A-native vs B-image in chart A — exactly one, mirroring v2 §2.5's seam
  rule) so no contact is double-counted.

### 2.4 Aperture clipping — carried over verbatim

- `IplClipRegion` + the in-place neutralize pass (`hooks.rs:146-205`) apply
  unchanged: near side keeps `d < 0` on the real colliders, far side keeps `d >= 0`
  on the images. Clip regions attach per collider-set (real vs image) instead of per
  body-in-scene; `setClipRegions` (ipl_ext.rs:44) grows an image-set variant.
- The neutralize-don't-retain rule (contact count must never shrink — the fork's SIMD
  batcher lane-gathers unchecked; the 0xc0000005 lore at `hooks.rs:172-179`) is LAW
  for every new pass v3 adds.

### 2.5 Self-image and pair exclusion

- A body must not contact its own image — **AUDITED: already enforced by the
  engine.** The narrow phase hard-clears same-parent pairs
  (`narrow_phase.rs:843-847`; sensors `:737-741`), and images are colliders on the
  parent body, so native-vs-image pairs die before manifolds with zero new code.
  `ipl_excluded_pairs` stays for its existing (distinct-body) uses only.
- Ship-visible-through-its-own-portal-loop self-collision is thereby OFF initially.
  AUDITED for later: enabling it needs BOTH a relax flag at the `narrow_phase.rs:843`
  filter AND a solver fix — the SoA solver scatters `solver_vel1` then `solver_vel2`
  back sequentially, so with body1 == body2 the second scatter silently overwrites
  the first (half the impulse lost; no debug_assert catches it). Islands are safe.
  Stays descoped until deliberately funded.

### 2.6 Re-anchoring (authority swap, demoted to bookkeeping)

- When the crossing fraction passes threshold: recompose the body's pose/velocity
  into the far chart (`pose ← P × pose`, twist mapped through R), swap chart tags
  between the native collider set and the image set, retarget gravity frame. No
  promote/demote of dynamics state between bodies — there is one body; re-anchoring
  is a change of coordinates, discontinuity-free by construction.
- `executeHostedTransit` (SableRehomeOps) keeps its gameplay half (parent flip,
  riders, tracking); its physics half shrinks to the recompose. Majority-rehome and
  the declarative session machinery in `SableTransitController` survive as the thing
  that decides WHEN; the swap hysteresis exists only to pick the anchor chart, and no
  longer has dynamics consequences if it thrashes.

### 2.7 Stepping cadence

- One world steps atomically: **once per server tick** (current substep structure
  preserved), not once per level tick. Sable calls `Rapier3D.step` per level
  (`SubLevelPhysicsSystem`); under the chart view, the FIRST step call of a server
  tick steps the world and the rest no-op (or a mixin moves the step to one seam).
  Pose publication back to each level keeps its current per-level path.
- The `parallel` island solver keeps the perf shape: charts are disjoint islands
  except where portal images couple them — which is exactly where a joint solve is
  the point.
- Contact-event readout (`clearCollisions`, reported_collisions buffer) becomes
  per-world; demux records to per-level consumers by body id / chart tag. The v2
  "share via mixin, don't double-read" rule still applies, now across levels.

### 2.8 Moving portals (portals on physics structures) — phased

- P becomes time-varying, derived each substep from the anchor body's pose composed
  with the portal's local frame. Image poses update per substep (§2.2 already
  requires the plumbing). Chained moving portals compose poses; the frame twist
  composes by the same algebra — all derived per substep, never integrated, so no
  drift accumulates in the images.
- **Charts stay inertial — this is the payoff of the atlas formulation.** We never
  simulate in the portal's (rotating, accelerating) frame; the portal is only a
  time-varying gluing map. No centrifugal/Coriolis/fictitious-force terms ever enter
  the solve. Portal motion enters through exactly two channels: the image pose P(t),
  and the image velocity term below.
- **Frame twist in contact velocity**: the image point velocity is
  `v_img(p) = R·v_A + (R·ω_A) × (p − P(com_A)) + v_P + ω_P × (p − c_P)`
  where `(v_P, ω_P)` is the portal frame's twist about its center `c_P` (derived from
  the anchor body's twist). Without the last two terms, contacts through a moving
  portal see wrong approach speeds (sticky/popping contact). Engine-side, same
  assembly site as the §2.3 velocity mapping; the existing `fake_velocities` /
  `tangent_velocity` machinery (`hooks.rs`) is precedent but only covers the
  tangential channel — the normal approach speed needs the real term. Note the
  physics is CORRECT and useful even one-way: a portal sweeping over a resting object
  correctly shoves the object via its far-side contacts.
- **Acceleration is captured between substeps**, not within one: P is frozen per
  substep (kinematic), so a fast-spinning or hard-accelerating portal frame shows
  O(dt) contact error. Mitigations: the straddle speed cap becomes a cap on
  RELATIVE portal↔body speed (the portal's motion counts against the tunneling
  budget too — CCD is inert, v2 lesson), and substep count can rise locally if
  evidence demands.
- **Clip regions must track P(t)**: today `setClipRegions` is Java-pushed per tick;
  a moving portal needs the clip OBB derived at substep granularity. Store the
  portal's LOCAL frame + anchor body id natively and derive the region per substep
  (small `ipl_ext` addition), rather than pushing 14 doubles from Java per substep.
- **Decision (fiat, revisit only with evidence)**: the portal frame is kinematic
  within a step — traversal impulses do NOT back-react on the anchor ship. Every
  shipped portal game does this; it is stable and believable. Momentum-conserving
  portal recoil is descoped (§4), with a principled escalation path recorded there.
- Bridge side: the IP portal entity rides/anchors to the sub-level; router predicates
  (§2.9) consume the time-varying isometry. Grid-aligned rotation gating stays for
  ACTORS only (§2.3 Tier 2 note): physics traversal has no grid restriction.

### 2.9 Gameplay layer — retained from v2, unchanged

- Single authoritative sub-level in the hosting container; presence as projection;
  world-frame router + half-open predicate for actors/redstone/BEs; the three bypass
  seams; entity collision via the OBB pass + straddle pose-map mixins; server-auth
  parity snapshots for client smoothness. v2 §2.1, §2.8, §2.9 and the audit rows for
  them are incorporated by reference. v3 changes NOTHING here — that design is what
  an atlas engine wants (gameplay state lives once; physics is a projection).
- Gravity: home-(anchor-chart)-frame gravity, by fiat (v2 §2.4 rule, unchanged).

## 3. What carries over vs. dies

| v2 component | v3 fate |
|---|---|
| Per-scene worlds | Charts in one world; JNI scene surface preserved as views |
| Dynamic clone body + mirrored mass | **Deleted** → image collider sets |
| Velocity servo + gains + bleed/rock damps | **Deleted** |
| Impulse feedback readout (coupling) | **Deleted** as coupling; readout stays for sounds/particles |
| Authority swap + hysteresis | Re-anchoring (bookkeeping only); trigger logic survives |
| Multi-straddle session weighting | **Deleted** (N image sets, one solve) |
| Aperture clip regions + neutralize pass | Carried verbatim, per collider-set |
| `ipl_excluded_pairs` | Generalized to native-vs-image set exclusion |
| `useDedicatedChunks` private storage | Precedent/mechanism for chart-keyed terrain |
| Transit ops (`executeHostedTransit`) | Gameplay half unchanged; physics half = recompose |
| World-frame router, actor seams, entity pose-map mixins, parity snapshots | Unchanged |
| CCD-off + straddle speed cap | Unchanged (CCD confirmed inert vs voxels) |

## 4. Descoped (explicitly)

- **Momentum back-reaction on portal anchors** (portal recoil) — kinematic-frame fiat
  (§2.8). The principled escalation, if ever wanted: the image pose depends on the
  anchor body C's pose, so an imaged contact is really a THREE-body constraint row
  (traverser A, far body B, anchor C) — expressible in the same §2.3 assembly
  framework by adding C's Jacobian blocks (∂image/∂C terms). Real engine work, no
  shipped game does it; recorded so the fiat is a choice, not a ceiling.
- **Scaled portals for sub-level bodies** — isometries only (v2 rule stands).
- **Self-collision through portal loops** — excluded initially (§2.5). Eventual
  design recorded in Appendix A (requires Tier 2; ≈1 wk on top of it).
- **Portal-aware CCD / sweep continuation across charts** — CCD is inert against
  voxel colliders anyway; speed cap stays.
- **Portal-aware native raycasts/shapecasts** — none exist natively; MC-side `clip`
  routing continues to serve.
- **Cross-chart joints/ropes through portals** — plunger ropes through portals remain
  refused/force-only for now; the atlas makes mapped-Jacobian rope rows POSSIBLE
  later (they're rows, like contacts), but that's future work.

## 5. Risks / M0 audit questions

> **M0 executed 2026-07-21 — see `atlas_portal_physics_audit_results.md` for the
> answers (gap matrix, file:line). Verdict: green light; Tier-1 gate passed cheaper
> than estimated. One NEW open item from the audit: per-chart gravity/drag —
> `pipeline.step` takes one gravity vector while scenes carry per-dimension
> gravity/drag today; resolve as (a) assert-uniform (cheap start, true for vanilla
> dims), (b) per-body gravity via forces, or (c) per-body gravity scale in the fork.
> The list below is retained as the original question set.

Scope risk concentrates in the solver hot path and the step restructure:

1. **SIMD constraint batcher fragility (top risk)**: the fork lane-gathers solver
   contacts unchecked — partially shrinking a manifold crashed reproducibly
   (0xc0000005 on solver workers, `hooks.rs:172-179`). §2.3 touches the constraint
   BUILDER, not the contact list, but the same class of assumption may lurk. M0 must
   locate where lever arms / world COM are gathered per lane and determine whether a
   per-lane COM substitution (Tier 1) and frame rotation (Tier 2) fit the wide path,
   or whether imaged manifolds must route through a scalar/generic constraint path.
   Tier 1 is the feasibility gate for the whole spec.
2. **Broad-phase churn**: charts overlap spatially in one SAP structure; candidate
   pairs across charts are filtered but still cost broad-phase work. Measure at M1
   with real dimension terrain; escalation is chart-keyed broad-phase layers
   (engine-side, moderate).
3. **Step fusion**: enumerate every `Rapier3D.step`/scene-step call site and per-level
   assumptions (queued forces, `queuedWheelMounts` static-flush ordering bug from the
   v2 audit becomes LOUDER under one world — fix it in this pass); design the
   once-per-server-tick seam.
4. **Chart id plumbing**: free bits in `InteractionGroups` vs `filter_contact_pair`
   activation cost (hook call per candidate pair) — pick the §2.1 route with numbers.
5. **Terrain map rekeying**: `pack_section_pos` uses 64 bits fully
   (`scene.rs:30-35`) — chart goes in a wrapper map or widened key; audit
   `changeBlock` hot-path cost of the extra indirection.
6. **Collider position update site**: find where `position_wrt_parent` composes with
   parent pose in the vendored engine; confirm a per-collider optional isometry
   prefix is a clean insertion (expected small; verify).
7. **Contact-event demux**: reported-collision records carry body ids — confirm
   nothing downstream assumes per-scene buffers (impact sounds route to the right
   level).
8. **Engine vendoring & skew**: vendor ryanhcode/rapier at the pinned rev into
   `natives/`, rebuild, A/B against current binaries (the v2 audit's source/binary
   skew warning stands: trustworthy build loop FIRST).
9. **Self-pair legality**: does the solver accept a contact constraint where both
   sides resolve to one rigid body (needed eventually for §2.5's future
   self-collision; near-term we exclude, so this is informational)?
10. **Rebase burden**: quantify the standing engine diff after Tier 1 + Tier 2 (
    target: a few hundred lines, feature-gated `ipl-atlas` cargo feature) and write
    down the sable-update rebase procedure.

## 6. Kill switches / coexistence

- `-Dipl.sable.atlas=false` (Java) and `IPL_ATLAS=0` (native env, same pattern as
  `IPL_DISABLE_CLIP`) revert to the v2 clone/servo path. The v2 machinery is NOT
  deleted until Tier 1 has survived in-game verification at every v2 milestone
  scenario (same-dim straddle, cross-dim, multi-straddle, wedge test, dim-stack
  free-fall).
- Tier 2 ships behind its own flag; rotated portals fall back to servo until it's
  green.
- Every native addition stays inside the `ipl_ext.rs` / feature-gate discipline: the
  sable JNI surface untouched, engine diff feature-gated.

## 7. Sequencing

- **M0 — engine audit + vendored build loop** (≈1 wk): §5 questions answered with
  file:line evidence; vendored engine builds and A/Bs clean against shipped binaries.
  GATE: Tier-1 COM substitution judged feasible in (or beside) the SIMD path.
- **M1 — charts** (≈1-2 wk): one world, chart-tagged colliders + terrain layering,
  scene-handle→chart views, step fusion, cross-chart isolation, event demux. Verify:
  all existing NON-portal behavior identical (this milestone is pure refactor;
  the v2 servo path still runs, now cross-chart).
- **M2 — image colliders + Tier 1** (≈1-2 wk): image sets, portal transform on
  colliders, COM substitution, clip regions per set, self-image exclusion. Verify:
  translation-portal straddle with the servo OFF — ship rests dead-still through a
  portal, wedge test resolves in-solver, multi-straddle N=2/3 jitter-free.
- **M3 — delete the coupling machinery** (≈2-3 days): servo, feedback, damps,
  fraction weighting off the translation path; re-anchoring replaces swap. Keep kill
  switch.
- **M4 — Tier 2 rotated portals** (≈2-3 wk, severable): frame-rotated Jacobians or
  scalar-path routing; 90° portal scenarios.
- **M5 — moving portals** (≈2 wk, after M3): time-varying P, frame-twist term,
  portal-on-ship bridge anchoring, router time-varying isometries.
- **M6 — polish**: broad-phase escalation if M1 numbers demand, ropes-through-portals
  exploration, self-collision-through-loops if wanted.

Estimated core (M0-M3, translation-exact, servo deleted): **≈4-6 weeks**. Rotated +
moving portals bring the total to ≈8-11 weeks.

## Appendix A — Self-collision through portal loops (eventual design)

Descoped (§4), designed here so the descope has a funded escalation path. Setting:
a loop's composite isometry `P = (R, t)` brings a body's image back into its own
chart (facing portal pair; ship long enough to reach its own tail). The candidate
pair is native collider (`bodyPose × local`) vs image collider
(`P × bodyPose × local`) — same chart, same parent. Three pieces:

1. **Pair admission.** Relax the `narrow_phase.rs:843` same-parent filter behind a
   predicate, not a blanket flag: admit iff the two colliders carry DIFFERENT portal
   prefixes (`None` vs `Some(P)`, or two images through different portals — then the
   effective map is `P₂⁻¹P₁`). Ordinary same-body pairs stay filtered. Degeneracy
   guard: reject when the composite isometry is near-identity (back-to-back portal
   pair ⇒ image overlaps the body wholesale) below an isometry-distance epsilon.
   Aperture clip regions (§2.4) already own the seam at the plane.

2. **Single-body combined-Jacobian constraint (forced by the audited
   double-scatter).** One body, two appearances ⇒ one constraint row:
   `J_eff = J_native − J_image`, with `J_image` the R-mapped block Tier 2 already
   builds. Impulse `λn` applies twice to body A: `+λn` at lever `p − com_A`, and
   `−Rᵀλn` at lever `P⁻¹p − com_A`. Effective mass from the COMBINED Jacobian —
   which is exactly what the SoA two-copy gather/sequential-scatter path cannot
   express (second scatter overwrites the first; audit Q9). Therefore: route
   admitted self-pairs to a dedicated SCALAR single-body constraint variant beside
   `GenericContactConstraint` (gather once, combined J, one scatter). Self-contact
   manifolds are rare, so the scalar path is free; no SIMD surgery. Friction and
   warmstart follow the same combined structure; image-vs-image pairs use the same
   framework with both sides mapped.

3. **Built-in sanity checks (formulation validation).** `P = identity` ⇒
   `J_eff = 0` — no constraint; a body cannot push itself. Pure translation ⇒
   linear forces cancel exactly and the net effect is the pure torque `t × λn`:
   pushing your own tail through an infinite corridor turns you. Chart-frame
   momentum is legitimately not conserved through a loop (portals break translation
   symmetry) — this is correct non-Euclidean behavior, not a bug.

Dependency: Tier 2 (M4). Cost on top of it: ≈1 wk, dominated by the new constraint
variant's friction/warmstart plumbing, not the math.
