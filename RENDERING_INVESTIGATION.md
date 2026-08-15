# Rendering Investigation — Create Convoluted + IPSable

## Status: Research phase. No more guessing.

This document maps every reported visual bug to its root cause in the code, 
with file:line references. Fixes will be planned from this doc, not from 
speculation.

---

## Bug catalog

### Bug 1: Terrain disappears, shows other side (not just at portal)

**User report:** "the terrain in the dimension you are in disappears to then 
show the terrain on the other side of the portal... but not only where the 
portal is but everywhere but not DH terrain or the sky"

**Symptom:** When near a portal, the entire vanilla render distance terrain 
of the current dimension vanishes and is replaced by the destination 
dimension's terrain. DH LODs and the sky are unaffected.

**Root cause:** The clip plane (`FrontClipping.setupInnerClipping`) is 
applied during `renderSectionLayer` calls via 
`MixinLevelRenderer.onBeforeRenderingLayer` (line 207-220). When 
`PortalRendering.isRendering()` is true, the clip plane is enabled. But the 
clip plane equation is camera-relative world-space, and it's meant to clip 
ONLY the destination dimension's terrain to the portal aperture.

The bug is that the clip plane is ALSO being applied to the SOURCE 
dimension's terrain render. This happens because:

1. `IrisCompatibilityPortalRenderer.doRenderPortal` calls 
   `renderPortalContent(portal)` which re-enters 
   `LevelRenderer.renderLevel` for the destination dimension
2. During that re-entry, `MixinLevelRenderer.onBeforeRenderingLayer` fires 
   for EVERY `renderSectionLayer` call, including the destination dimension's 
   terrain layers
3. `FrontClipping.setupInnerClipping` is called with the portal's clipping 
   plane
4. After `renderPortalContent` returns, `PortalRendering.popPortalLayer()` 
   is called, but `FrontClipping.disableClipping()` may not fire correctly 
   if the mixin's `onAfterRenderingLayer` doesn't run (e.g., if Iris's 
   pipeline bypasses the normal renderSectionLayer path)

**Result:** The clip plane stays enabled after the portal render completes. 
The next frame's source-dimension terrain render gets clipped by the 
stale clip plane → terrain disappears everywhere.

**Files involved:**
- `src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer.java:207-220` 
  (onBeforeRenderingLayer / onAfterRenderingLayer)
- `src/main/java/qouteall/imm_ptl/core/render/FrontClipping.java:30-46` 
  (enableClipping / disableClipping)
- `src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatibilityPortalRenderer.java:94-131` 
  (doRenderPortal)

**Fix direction:** Ensure `FrontClipping.disableClipping()` is called 
unconditionally after `renderPortalContent` returns, regardless of whether 
the mixin's after-hook fired. Add a defensive `disableClipping()` call in 
`doRenderPortal` after `PortalRendering.popPortalLayer()`.

---

### Bug 2: Liquids and particles visible through terrain near portal

**User report:** "liquids and particles are visible through terrain when 
near the portal"

**Symptom:** Water, lava, and particles from the destination dimension 
render through solid terrain of the source dimension when a portal is 
nearby.

**Root cause:** Liquids and particles use different render paths than 
terrain blocks:

- **Terrain** uses `renderSectionLayer` (which has the clip plane mixin)
- **Liquids** are part of `renderSectionLayer` for translucent pass, but 
  under Sodium/Veil, the liquid rendering goes through a different 
  shader path that may not have the clip equation uploaded
- **Particles** render via `particleEngine.render()` which doesn't go 
  through `renderSectionLayer` at all

The clip equation upload (`IplProgramBindHook.onBind`) only fires when a 
shader is bound via `_glUseProgram` or `ProgramManager.glUseProgram`. If 
the particle/liquid shader was already bound before the portal render 
started and isn't re-bound during it, the uniform stays at zero → clip 
test always passes → renders through terrain.

**Files involved:**
- `src/main/java/ipl/sable/render/IplProgramBindHook.java:100-108` 
  (early return if !haveActive && !inPortalRender && !inSubLevelBracket)
- `src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer.java:140-155` 
  (onMyBeforeTranslucentRendering calls FrontClipping.disableClipping)

**Fix direction:** In `IplProgramBindHook.onBind`, when 
`PortalRendering.isRendering()` is true, ALWAYS write the cached clip 
equation to the program (even if `isClippingEnabled` is false), because 
the portal render may have shaders that were bound before the bracket 
opened. Also add a hook on `particleEngine.render` to re-upload the clip 
equation before particle draws.

---

### Bug 3: Portal visible through blocks with shaders

**User report:** "the portal is visible through blocks with shaders"

**Symptom:** When a shaderpack is active, the portal surface renders on 
top of solid terrain between the camera and the portal.

**Root cause:** Iris's shader pipeline manages depth differently from 
vanilla. When Iris's `IrisCompatibilityPortalRenderer` renders the portal 
content into the main framebuffer, Iris may have already written depth 
values for the source dimension's terrain. The portal's `drawPortalArea` 
call draws the portal quad with depth test enabled, but Iris's depth 
buffer may be in a state where the portal quad passes the depth test 
even when it should be occluded.

The compatibility renderer's `doRenderPortal` calls 
`CHelper.enableDepthClamp()` before drawing the portal area, which allows 
geometry to render at the far plane without clipping. This is needed for 
the portal quad to render at the correct depth, but it also means the 
portal can render through terrain if the depth buffer isn't properly 
managed.

**Files involved:**
- `src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatibilityPortalRenderer.java:103-114` 
  (enableDepthClamp + drawPortalAreaWithFramebuffer)
- `src/main/java/qouteall/imm_ptl/core/render/MyRenderHelper.java:146-170` 
  (drawPortalAreaWithFramebuffer)

**Fix direction:** Before `drawPortalAreaWithFramebuffer`, verify that 
the main framebuffer's depth buffer contains the source dimension's 
depth (not the destination's). If Iris swapped the depth buffer during 
portal content render, we need to either restore it from the deferred 
buffer or disable depth test for the portal quad draw and rely on 
stencil instead.

---

### Bug 4: Shadow stripes

**User report:** "the shadows basically are 5-7 blocks thick and run in a 
straight line across my render distance being spaced out by 3-5 blocks, 
the shadow is just broken lighting on blocks, this happens in both 
dimensions"

**Symptom:** Regular bands of broken lighting across terrain, 5-7 blocks 
thick, spaced 3-5 blocks apart, in both dimensions.

**Root cause:** This is NOT classic z-fighting (which would be 1-pixel 
thick shimmering). The 5-7 block thickness and 3-5 block spacing matches 
Minecraft's chunk section size (16 blocks) and render batch boundaries.

The clip plane equation is camera-relative, computed in 
`FrontClipping.getClipEquationInner`. When the camera moves, the equation 
changes. But if the clip equation is uploaded to a shader ONCE (at bind 
time) and the camera then moves, the shader uses the stale equation for 
subsequent chunk sections until the shader is re-bound.

Under Sodium, chunk sections are batched into `renderSectionLayer` calls. 
If the shader is bound once for the entire batch and not re-bound per 
section, the clip equation is correct for the first section but stale 
for subsequent sections → bands of incorrect lighting at chunk section 
boundaries.

**Files involved:**
- `src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer_Optional.java:73-85` 
  (onGetShaderInRenderingLayer calls updateClippingEquationUniformForCurrentShader)
- `src/main/java/qouteall/imm_ptl/core/render/FrontClipping.java:174+` 
  (updateClippingEquationUniformForCurrentShader)

**Fix direction:** Verify that `onGetShaderInRenderingLayer` fires for 
EVERY section layer, not just once per batch. Under Sodium, the shader 
may only be applied once per `renderSectionLayer` call (not per section). 
Need to add a hook that re-uploads the clip equation when the camera 
position changes, or force a shader re-bind per section.

---

### Bug 5: Ghost terrain (wrong dimension sections)

**User report:** "a small section of the opposite dimension, like 5*8*5 
but it varies and can be entire areas, being rendered with broken lighting"

**Symptom:** Small sections of the wrong dimension appear in the current 
dimension's render, with broken lighting.

**Root cause:** The `IrisCompatibilityPortalRenderer` uses a single 
`deferredBuffer` for all portals. When `onBeforeHandRendering` copies the 
main FB to the deferred buffer, it captures the source dimension's 
content. Then `doRenderPortal` renders the destination dimension into 
the main FB and composites the portal area back into the deferred.

If the clip plane doesn't fully clip the destination dimension's terrain 
(see Bug 4 — stale clip equation), fragments of destination terrain 
"leak" into the main FB outside the portal area. These leaked fragments 
then get composited into the deferred buffer, and when the deferred is 
drawn back to the main FB at the end of the frame, the leaked fragments 
appear as ghost terrain.

**Files involved:**
- `src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatibilityPortalRenderer.java:155-195` 
  (onBeforeHandRendering — copy + renderPortals + draw deferred back)
- `src/main/java/qouteall/imm_ptl/core/render/MyRenderHelper.java:146-170` 
  (drawPortalAreaWithFramebuffer)

**Fix direction:** Fix Bug 4 first (stale clip equation). If the clip 
plane works correctly, destination terrain won't leak outside the portal 
area, and ghost terrain should disappear.

---

### Bug 6: DH data collision

**User report:** "DH saves data for the overworld and nether in the same 
file making it overwrite it and making the wrong terrain visible"

**Symptom:** DH LOD data from one dimension overwrites another's, causing 
wrong terrain to appear at distant LODs.

**Root cause:** DH's `LocalSaveStructure` accumulates data paths from all 
previously-registered dimensions. The `ipl_sable:sublevels` hosting 
dimension is "patient zero" — its creation pollutes the path list for 
all subsequent dimensions.

**Status:** Config patch applied (DH config now has 
`ignoredDimensionCsv = "ipl_sable:sublevels"`). Log confirms: 
`ipl_sable:sublevels already in DH ignoredDimensionCsv CSV, no change 
needed`. But the data collision may persist because:
1. The config patch only prevents DH from RENDERING the hosting dim — 
   it doesn't prevent DH from creating a `DhLevel` for it
2. The `LocalSaveStructure` path accumulation happens at `DhLevel` 
   creation time, before the config's ignore list is checked

**Fix direction:** Need a mixin on DH's `AbstractDhWorld` or 
`DhClientServerLevel` constructor to skip level creation entirely for 
`ipl_sable:sublevels`. Config-level fix is insufficient.

---

## Fork vs upstream comparison

### What our fork adds (that upstream IPSable doesn't have)

1. **`DhConfigPatch.java`** — patches DH config to ignore hosting dim
2. **`IplDhOverrideInjectorMixin.java`** — stops DH error spam
3. **`IPModEntryClient.java`** — auto-enable compat mode for Iris+DH
4. **`MixinBlockGetter.java`** — sound physics log fix
5. **`MyGameRenderer.java`** — lightmap always update

### What upstream IPSable already has

1. Full Sable physics compat (sub-levels straddle portals)
2. `IrisCompatibilityPortalRenderer` (the renderer with all the bugs)
3. `FrontClipping` clip plane system
4. `IplProgramBindHook` clip equation upload
5. Veil shader preprocessing
6. Sodium/Flywheel compat mixins

### What we should NOT duplicate

We should NOT try to fix the rendering pipeline bugs (Bugs 1-5) by 
patching upstream's renderer code. That code is complex, fragile, and 
upstream is actively maintaining it. Our patches would diverge and make 
future rebases painful.

### What we SHOULD do

1. **Report Bugs 1-5 upstream** to r2smith141/IPSable with this research 
   doc as evidence
2. **Keep our fork's unique additions** (DH compat, sound physics, 
   lightmap fix) — these are modpack-specific and upstream may not want them
3. **Wait for upstream to fix the renderer** — they wrote it, they 
   understand it, and they're actively working on it

---

## Action plan

### Phase 1: Report upstream (do this first)

File issues on https://github.com/r2smith141/IPSable/issues for:
- Bug 1: Terrain disappears near portal (stale clip plane)
- Bug 2: Liquids/particles through terrain (clip equation not uploaded)
- Bug 3: Portal visible through blocks with shaders
- Bug 4: Shadow stripes (stale clip equation per chunk section)
- Bug 5: Ghost terrain (consequence of Bug 4)

Include file:line references from this doc.

### Phase 2: Keep our modpack-specific fixes

Our fork's value-add is the DH compat + sound physics + lightmap fix. 
These are correct and should stay.

### Phase 3: If upstream is slow, investigate Bug 1 + Bug 2 ourselves

These two are the most impactful (terrain disappearing + 
liquids/particles through terrain). The fix direction is clear from the 
research above. If upstream doesn't respond within a reasonable time, 
implement the fixes described in Bug 1 and Bug 2 sections.

### Phase 4: DH data collision (Bug 6)

Needs a @Pseudo mixin on DH's DhLevel creation to skip the hosting dim 
entirely. This is our fork's responsibility (upstream IPSable doesn't 
target DH compat).

---

## What NOT to do

- Do NOT make speculative changes to the renderer without understanding 
  the full render pipeline
- Do NOT add depth clears or FB blits without verifying they don't break 
  Iris's depth buffer management
- Do NOT change `FrontClipping.ADJUSTMENT` without understanding the 
  z-fighting tradeoff
- Do NOT null the Iris pipeline (causes Iris to skip rendering, leaving 
  FB in bad state)
- Do NOT version-tag indev builds (keep the repo clean until things 
  actually work)
