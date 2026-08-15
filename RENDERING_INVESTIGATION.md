# Rendering Investigation

This is from my testing in the Create Convoluted modpack. All bugs reproduced with the indev build (commit 587343f) running Iris + DH + Sodium + Veil + Flywheel on NeoForge 1.21.1.

---

## Bug catalog

### Bug 1: Terrain disappears near portal

When I get near a portal, the terrain in the dimension im in disappears. It shows the terrain from the other side of the portal. But its not just where the portal is, its everywhere. DH terrain and the sky are fine, only the vanilla render distance terrain vanishes.

Root cause: `FrontClipping.disableClipping()` is not called after `PortalRendering.popPortalLayer()` in the compatibility renderer. The clip plane stays enabled after the portal render finishes. The next frame, the source dimension terrain gets clipped by the stale clip plane and disappears.

Files:
- `MixinLevelRenderer.java:207-220` (onBeforeRenderingLayer enables clip)
- `MixinLevelRenderer.java:222-234` (onAfterRenderingLayer disables clip, but may not fire under Iris)
- `IrisCompatibilityPortalRenderer.java:94-131` (doRenderPortal, no defensive disableClipping)

Fix: add `FrontClipping.disableClipping()` after `popPortalLayer()` in `doRenderPortal`.

---

### Bug 2: Liquids and particles visible through terrain

When im near a portal, liquids (water, lava) and particles render through solid terrain. You can see water from the other dimension bleeding through the ground.

Root cause: `IplProgramBindHook.onBind` has an early return at line 106-108 that skips the clip equation upload when `!haveActive && !inPortalRender && !inSubLevelBracket`. But shaders that were bound BEFORE the portal bracket opened never get the clip equation uploaded. When the portal render starts, these shaders still have a zeroed `iportal_ClippingEquation` uniform. The clip test `dot(worldPos, 0,0,0,0) + 0 = 0 >= 0` always passes, so nothing gets clipped. Liquids and particles use shaders that are bound early and not re-bound during portal render, so they leak through.

Files:
- `IplProgramBindHook.java:100-108` (early return)
- `MixinLevelRenderer.java:140-155` (onMyBeforeTranslucentRendering disables clipping)

Fix: when `PortalRendering.isRendering()` is true, always write the cached clip equation to the program, even if `isClippingEnabled` is false. Remove the early return for the portal render case.

---

### Bug 3: Portal visible through blocks with shaders

When a shaderpack is active, the portal surface renders on top of solid terrain between the camera and the portal. You can see the portal through walls.

Root cause: The compatibility renderer calls `CHelper.enableDepthClamp()` before `drawPortalAreaWithFramebuffer`. Depth clamp lets geometry render at the far plane without clipping. But Iris's depth buffer may be in a state where the portal quad passes the depth test even when it should be occluded by terrain.

Files:
- `IrisCompatibilityPortalRenderer.java:103-114` (enableDepthClamp + drawPortalArea)
- `MyRenderHelper.java:146-170` (drawPortalAreaWithFramebuffer)

Fix: before drawing the portal area, verify the main FB depth buffer has the source dimension's depth. If Iris swapped it, restore from the deferred buffer or disable depth test for the portal quad.

---

### Bug 4: Shadow stripes (revised)

Stripes of broken lighting across terrain, 5-7 blocks thick, spaced 3-5 blocks apart. They are NOT aligned to chunks. They change in thickness. Happens in both dimensions.

Root cause: The clip plane equation is camera-relative. `FrontClipping.getClipEquationInner` computes `c = -planeNormal . portalPos` where `portalPos` includes `cameraPos`. When the camera moves, `c` changes. But the equation is only uploaded to the shader when the shader is bound (via `IplProgramBindHook.onBind` or `MixinLevelRenderer_Optional.onGetShaderInRenderingLayer`).

Under Sodium, chunk sections are batched. If the camera moves between shader binds, the shader uses a stale `c` value. The clip plane shifts by however much the camera moved. A 5-7 block camera movement = 5-7 block thick band of terrain where the clip test is wrong. The spacing (3-5 blocks) is the batch size between rebinds. Thickness changes because camera speed varies.

The stripes are parallel to the portal plane, not to chunks. This is why they "change in thickness" - its proportional to camera movement speed.

Files:
- `FrontClipping.java:101-120` (getClipEquationInner, camera-relative equation)
- `IplProgramBindHook.java:72-178` (onBind, uploads equation per shader bind)
- `MixinLevelRenderer_Optional.java:73-85` (onGetShaderInRenderingLayer, re-uploads per layer)

Fix: re-upload the clip equation when the camera position changes, not just when the shader is bound. Track the last camera position used for the upload, and force a re-upload when it moves more than a threshold (e.g. 0.01 blocks).

---

### Bug 5: Ghost terrain (wrong dimension sections)

Small sections of the opposite dimension, like 5x8x5 but it varies and can be entire areas, rendered with broken lighting. Sometimes its not small, its large areas.

Root cause: consequence of Bug 4. The stale clip equation lets destination dimension terrain fragments leak outside the portal area. These leaked fragments get composited into the deferred buffer by `drawPortalAreaWithFramebuffer`. When the deferred buffer is drawn back to the main FB at the end of the frame, the leaked fragments appear as ghost terrain.

Fix: fix Bug 4 first. If the clip equation is always correct, no destination terrain leaks, and ghost terrain disappears.

---

### Bug 6: Wrong water color

Large areas in water where its a completely wrong colour. Deep blue instead of a light blue with slight green tint. Happens in the overworld near portals.

Root cause: water color in Minecraft comes from `BiomeColors.getAverageWaterColor()`, sampled per-vertex during chunk meshing. Under Sodium this is baked into the chunk vertex buffer. The color is biome-dependent and dimension-specific.

When IP re-enters `LevelRenderer.renderLevel` for the destination dimension, it switches the `ClientLevel` and `LevelRenderer` but does NOT switch the biome color resolver or `BlockColors` instance. If the destination dimension's chunk mesh was built with the source dimension's biome colors (because the color resolver wasn't swapped), the water renders with wrong colors.

Also, `FogRendererContext.swappingManager.pushSwapping(newDimension)` is called, but this only handles fog color, not biome water color.

Files:
- `MyGameRenderer.java:202` (FogRendererContext swap, but no BlockColors swap)
- `MyGameRenderer.java:165-190` (world/renderer/camera swap, but no color state swap)

Fix: investigate whether Sodium's `BlockColors` or biome color resolver needs to be swapped when entering portal content render. May need a mixin on `ClientLevel.getBlockColors()` or `BiomeColors` to return the correct dimension's colors.

---

### Bug 7: DH data collision

DH saves data for the overworld and nether in the same file. It overwrites and makes the wrong terrain visible at distant LODs.

Root cause: DH's `LocalSaveStructure` accumulates data paths from all previously-registered dimensions. The `ipl_sable:sublevels` hosting dimension is patient zero. Its creation pollutes the path list for all subsequent dimensions. The config patch (`ignoredDimensionCsv`) prevents DH from RENDERING the hosting dim, but does not prevent DH from CREATING a `DhLevel` for it. The path accumulation happens at `DhLevel` creation time, before the config's ignore list is checked.

Files:
- `DhConfigPatch.java` (config-level fix, insufficient)
- DH's `AbstractDhWorld` / `DhClientServerLevel` constructor (where path accumulation happens)

Fix: write a `@Pseudo` mixin on DH's `AbstractDhWorld` or `DhClientServerLevel` to skip level creation entirely for `ipl_sable:sublevels`. This is our responsibility, upstream IPSable does not target DH compat.

---

## Fork vs upstream

Our fork adds: DH compat (config patch + OverrideInjector mixin), sound physics log fix, lightmap always-update, auto-enable compat mode.

Upstream IPSable already has: the entire rendering pipeline including `IrisCompatibilityPortalRenderer`, `FrontClipping`, `IplProgramBindHook`, Veil/Sodium/Flywheel compat.

Bugs 1-5 are in upstream's renderer code. Bug 6 is in IP's dimension switching code. Bug 7 is a DH architectural issue that our config patch doesnt fully solve.

---

## Action plan

1. Fix Bug 1 (defensive disableClipping) - quick, high impact
2. Fix Bug 2 (remove early return in IplProgramBindHook) - quick, high impact
3. Fix Bug 4 (re-upload clip equation on camera move) - medium, fixes stripes
4. Fix Bug 7 (DH DhLevel creation mixin) - medium, our responsibility
5. Bug 3, 5, 6 should be fixed or improved by fixing 1, 2, 4

If bugs persist after 1, 2, 4, 7 are done, investigate 3 and 6 further.
