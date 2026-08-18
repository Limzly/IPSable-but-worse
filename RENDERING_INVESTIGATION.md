# Rendering Investigation

This is from my testing in the Create Convoluted modpack. All bugs reproduced running Iris + DH + Sodium + Veil + Flywheel on NeoForge 1.21.1.

Note: Sable updated from 2.0.3 to 2.0.4 and Veil from 4.1.4 to 4.3.2 in the modpack since the last test. This may change compatibility.

---

## Bug catalog

### Bug 1: Terrain disappears near portal

When I get near a portal, the terrain in the dimension im in disappears. It shows the terrain from the other side of the portal. But its not just where the portal is, its everywhere. DH terrain and the sky are fine, only the vanilla render distance terrain vanishes.

Root cause: `FrontClipping.disableClipping()` is not called after `PortalRendering.popPortalLayer()` in the compatibility renderer. The clip plane stays enabled after the portal render finishes. The next frame, the source dimension terrain gets clipped by the stale clip plane and disappears.

Files:
- `MixinLevelRenderer.java:207-220` (onBeforeRenderingLayer enables clip)
- `MixinLevelRenderer.java:222-234` (onAfterRenderingLayer disables clip, but may not fire under Iris)
- `IrisCompatibilityPortalRenderer.java:94-131` (doRenderPortal, no defensive disableClipping)

Fix applied: added `FrontClipping.disableClipping()` after `popPortalLayer()` in `doRenderPortal`. NOT YET TESTED because the user has not successfully loaded our mod in the latest builds.

Status: fix applied, untested.

---

### Bug 2: Liquids and particles visible through terrain

When im near a portal, liquids (water, lava) and particles render through solid terrain. You can see water from the other dimension bleeding through the ground.

Root cause: `IplProgramBindHook.onBind` has an early return at line 106-108 that skips the clip equation upload when `!haveActive && !inPortalRender && !inSubLevelBracket`. But shaders that were bound BEFORE the portal bracket opened never get the clip equation uploaded. When the portal render starts, these shaders still have a zeroed `iportal_ClippingEquation` uniform. The clip test `dot(worldPos, 0,0,0,0) + 0 = 0 >= 0` always passes, so nothing gets clipped. Liquids and particles use shaders that are bound early and not re-bound during portal render, so they leak through.

Files:
- `IplProgramBindHook.java:100-108` (early return)
- `MixinLevelRenderer.java:140-155` (onMyBeforeTranslucentRendering disables clipping)

Fix applied: when not in a portal render and not in a sub-level bracket, now writes the no-clip sentinel (0,0,0,1) to the shader's iportal_ClippingEquation uniform before returning. This clears stale equations from previous portal renders.

Status: fix applied, untested.

---

### Bug 3: Portal visible through blocks with shaders

When a shaderpack is active, the portal surface renders on top of solid terrain between the camera and the portal. You can see the portal through walls.

Root cause: The compatibility renderer calls `CHelper.enableDepthClamp()` before `drawPortalAreaWithFramebuffer`. Depth clamp lets geometry render at the far plane without clipping. But Iris's depth buffer may be in a state where the portal quad passes the depth test even when it should be occluded by terrain.

Files:
- `IrisCompatibilityPortalRenderer.java:103-114` (enableDepthClamp + drawPortalArea)
- `MyRenderHelper.java:146-170` (drawPortalAreaWithFramebuffer)

Fix approach: before drawing the portal area, verify the main FB depth buffer has the source dimension's depth. If Iris swapped it, restore from the deferred buffer or disable depth test for the portal quad.

Status: NOT FIXED. Needs investigation.

---

### Bug 4: Shadow stripes

Stripes of broken lighting across terrain, 5-7 blocks thick, spaced 3-5 blocks apart. They are NOT aligned to chunks. They change in thickness. Happens in both dimensions.

Root cause: The clip plane equation is camera-relative. `FrontClipping.getClipEquationInner` computes `c = -planeNormal . portalPos` where `portalPos` includes `cameraPos`. When the camera moves, `c` changes. But the equation is only uploaded to the shader when the shader is bound (via `IplProgramBindHook.onBind` or `MixinLevelRenderer_Optional.onGetShaderInRenderingLayer`).

Under Sodium, chunk sections are batched. If the camera moves between shader binds, the shader uses a stale `c` value. The clip plane shifts by however much the camera moved. A 5-7 block camera movement = 5-7 block thick band of terrain where the clip test is wrong. The spacing (3-5 blocks) is the batch size between rebinds. Thickness changes because camera speed varies.

The stripes are parallel to the portal plane, not to chunks. This is why they "change in thickness" - its proportional to camera movement speed.

Files:
- `FrontClipping.java:101-120` (getClipEquationInner, camera-relative equation)
- `IplProgramBindHook.java:72-178` (onBind, uploads equation per shader bind)
- `MixinLevelRenderer_Optional.java:73-85` (onGetShaderInRenderingLayer, re-uploads per layer)

Fix applied: added `FrontClipping.refreshClipEquationForCurrentCamera()` which re-computes the world-space clip equation using the current camera position. Called from `IplProgramBindHook.onBind` before uploading.

Status: fix applied, untested.

---

### Bug 5: Ghost terrain (wrong dimension sections)

Small sections of the opposite dimension, like 5x8x5 but it varies and can be entire areas, rendered with broken lighting. Sometimes its not small, its large areas.

Root cause: consequence of Bug 4. The stale clip equation lets destination dimension terrain fragments leak outside the portal area. These leaked fragments get composited into the deferred buffer by `drawPortalAreaWithFramebuffer`. When the deferred buffer is drawn back to the main FB at the end of the frame, the leaked fragments appear as ghost terrain.

Fix: fix Bug 4 first. If the clip equation is always correct, no destination terrain leaks, and ghost terrain disappears.

Status: should be fixed by Bug 4 fix, untested.

---

### Bug 6: Wrong water color

Large areas in water where its a completely wrong colour. Deep blue instead of a light blue with slight green tint. Happens in the overworld near portals.

Root cause: water color in Minecraft comes from `BiomeColors.getAverageWaterColor()`, sampled per-vertex during chunk meshing. Under Sodium this is baked into the chunk vertex buffer. The color is biome-dependent and dimension-specific.

When IP re-enters `LevelRenderer.renderLevel` for the destination dimension, it switches the `ClientLevel` and `LevelRenderer` but does NOT switch the biome color resolver or `BlockColors` instance. If the destination dimension's chunk mesh was built with the source dimension's biome colors (because the color resolver wasn't swapped), the water renders with wrong colors.

Also, `FogRendererContext.swappingManager.pushSwapping(newDimension)` is called, but this only handles fog color, not biome water color.

Files:
- `MyGameRenderer.java:202` (FogRendererContext swap, but no BlockColors swap)
- `MyGameRenderer.java:165-190` (world/renderer/camera swap, but no color state swap)

Fix approach: investigate whether Sodium's `BlockColors` or biome color resolver needs to be swapped when entering portal content render. May need a mixin on `ClientLevel.getBlockColors()` or `BiomeColors` to return the correct dimension's colors.

Status: NOT FIXED. Needs investigation.

---

### Bug 7: DH data collision

DH saves data for the overworld and nether in the same file. It overwrites and makes the wrong terrain visible at distant LODs.

Root cause: DH's `LocalSaveStructure` accumulates data paths from all previously-registered dimensions. The `ipl_sable:sublevels` hosting dimension is patient zero. Its creation pollutes the path list for all subsequent dimensions. The config patch (`ignoredDimensionCsv`) prevents DH from RENDERING the hosting dim, but does not prevent DH from CREATING a `DhLevel` for it. The path accumulation happens at `DhLevel` creation time, before the config's ignore list is checked.

Files:
- `DhConfigPatch.java` (config-level fix, insufficient)
- DH's `AbstractDhWorld` / `DhClientServerLevel` constructor (where path accumulation happens)

Fix approach: need a `@Pseudo` mixin on DH's `AbstractDhWorld` or `DhClientServerLevel` to skip level creation entirely for `ipl_sable:sublevels`. Previous attempt (IplServerLevelDhTrackerMixin on ServerLevel constructor) crashed because you can't inject at HEAD on a constructor before super(). Need a different approach:

Option A: mixin on DH's `AbstractDhWorld.getServerLevelEvent` or similar event handler, cancel when the dimension is ipl_sable:sublevels. This avoids touching vanilla ServerLevel.

Option B: mixin on DH's `DhClientServerLevel.<init>` (the DH class, not vanilla) to cancel creation. Use @At("RETURN") since HEAD requires static handler.

Option C: use DH's own API (`DhApiBeforeDhInitEvent` or similar) to register the hosting dimension as ignored at runtime. Check if DH exposes a programmatic API for this.

Status: NOT FIXED. Previous attempt was reverted because it caused a Quark crash. Config patch is the only workaround currently.

---

### Bug 8: Sounds from wrong dimension (Sound Physics)

Sound Physics Remastered and Sound Physics Perfected play sounds from the wrong dimension when near a portal. You can hear nether sounds in the overworld and vice versa. The raycast also throws "Raycast too far" errors because it crosses the portal boundary.

How IP works: when you look at a portal, IP re-enters `LevelRenderer.renderLevel` for the destination dimension. Both dimensions are rendered in the same framebuffer in the same render pass. Sound Physics raycasts for occlusion using `BlockGetter.traverseBlocks`, which doesn't know about portal boundaries. It raycasts in a straight line through the world, crossing the portal boundary, and hits blocks in the wrong dimension.

Root cause: IP renders both dimensions simultaneously. The sound engine doesn't know which dimension a sound source is in relative to the listener. Sound Physics raycasts from the listener to the sound source, and if the source is in the destination dimension (rendered through the portal), the raycast goes through the portal into the wrong dimension's blocks.

Files:
- `MixinBlockGetter.java` (the raycast clamp, already fixed the log spam)
- Sound Physics Remastered's `RaycastUtils.java` (calls `BlockGetter.traverseBlocks`)
- Sound Physics Perfected's `RaycastingHelper.java` (same)
- `MyGameRenderer.java:255` (re-enters renderLevel for destination dimension)

Fix approaches:

Option A: redirect Sound Physics raycasts through the portal transform. When Sound Physics calls `traverseBlocks`, transform the raycast start/end points through the portal's coordinate transform if the sound source is in the destination dimension. This would make the raycast go through the portal correctly. Requires mixin on Sound Physics's `RaycastUtils` / `RaycastingHelper` classes.

Option B: suppress sounds from the destination dimension entirely. When `PortalRendering.isRendering()` is true, block sound events from the destination dimension from playing. Less immersive but simpler. Can be done with a mixin on `SoundEngine.play` or similar.

Option C: provide a portal-aware raycast API. Replace `BlockGetter.traverseBlocks` with a version that knows about portal boundaries and transforms the raycast path through the portal. This is the most correct fix but the most complex.

Option D: tell Sound Physics mods to not raycast during portal rendering. When `PortalRendering.isRendering()` is true, return "no occlusion" (full volume) for all sounds. This means sounds through portals will be louder (no occlusion) but won't play from the wrong dimension. Can be done with a mixin on Sound Physics's occlusion calculation.

Recommended: Option D as a quick fix (suppress occlusion during portal render), then Option A as the proper fix (portal-transformed raycasts). Option B is a fallback if A is too complex.

Status: NOT FIXED. The log spam is fixed (Bug 8 in previous changelog) but the actual wrong-dimension sounds still play.

---

## Fork vs upstream

Our fork adds: DH compat (config patch + OverrideInjector mixin), sound physics log fix, lightmap always-update, auto-enable compat mode, defensive disableClipping, clip equation refresh, no-clip sentinel on early return.

Upstream IPSable already has: the entire rendering pipeline including `IrisCompatibilityPortalRenderer`, `FrontClipping`, `IplProgramBindHook`, Veil/Sodium/Flywheel compat.

Bugs 1-5 are in upstream's renderer code. Bug 6 is in IP's dimension switching code. Bug 7 is a DH architectural issue. Bug 8 is a Sound Physics + IP interaction.

---

## What works

- Game boots (when the jar is actually in the mods folder)
- Sound Physics log spam fixed (one-line warning instead of stack trace)
- DH OverrideInjector error spam fixed (15K errors/session eliminated)
- Cross-portal lighting improved (lightmap always updated)
- Auto-enable compatibility render mode when Iris + DH detected
- DH config patch to ignore ipl_sable:sublevels hosting dimension

## What doesn't work

All rendering bugs (1-6) are UNTESTED in the latest build because the user has not successfully loaded our mod in the latest builds. The fixes are applied but need testing.

Bug 7 (DH data collision) needs a new approach after the ServerLevel mixin was reverted.

Bug 8 (wrong dimension sounds) is a new addition. Needs a fix approach decision.

---

## Action plan

1. User needs to successfully build and load the mod (jar in mods folder)
2. Test bugs 1, 2, 4 (fixes applied, untested)
3. If bugs 1, 2, 4 are fixed, investigate bug 3 (portal through blocks)
4. If bug 4 is fixed, bug 5 (ghost terrain) should be fixed too
5. Investigate bug 6 (water color) - need to swap biome color resolver
6. Bug 7 (DH data collision) - try Option B or C (mixin on DH class, not vanilla)
7. Bug 8 (wrong dimension sounds) - try Option D first (suppress occlusion during portal render)

---

## How IP works (for context)

When you look at a portal, IP re-enters `LevelRenderer.renderLevel` for the destination dimension. Both dimensions are rendered in the same framebuffer in the same render pass. A clip plane (FrontClipping) crops the destination dimension to the portal area so it doesn't overwrite the source dimension's content outside the portal.

The clip plane equation is camera-relative: it includes the camera position. When the camera moves, the equation changes. If the equation isn't re-uploaded to shaders, the clip test uses stale values and terrain gets incorrectly clipped or unclipped.

Sound Physics raycasts for occlusion using `BlockGetter.traverseBlocks`. It doesn't know about portal boundaries, so it raycasts in a straight line through the world, crossing the portal boundary into the wrong dimension.
