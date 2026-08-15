# Changelog

This is a fork of IPSable for the Create Convoluted modpack. It's indev.

## indev

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
- Root cause: DH's LocalSaveStructure accumulates data paths from all previously registered dimensions. The ipl_sable:sublevels hosting dimension is patient zero. The config patch (ignoredDimensionCsv) only prevents DH from rendering the hosting dim, not from creating a DhLevel for it. The path accumulation happens at DhLevel creation time.
- Fix: added IplDhSkipHostingDimensionMixin (@Pseudo on AbstractDhLevel constructor) that cancels DhLevel creation for ipl_sable:sublevels. Uses DhDimensionTracker (thread-local flag) set by IplServerLevelDhTrackerMixin when the hosting dimension's ServerLevel is being created. This prevents DH from ever creating a DhLevel for the hosting dimension, stopping the path accumulation cascade.

### What still needs investigation

- Bug 3: Portal visible through blocks with shaders (Iris depth buffer management)
- Bug 5: Ghost terrain (should be fixed by Bug 4, needs testing)
- Bug 6: Wrong water color (biome color resolver not swapped during portal render)

### Previous fixes (kept)

- Sound Physics raycast log fix (MixinBlockGetter)
- Auto-enable compatibility render mode when Iris + DH detected (IPModEntryClient)
- DH OverrideInjector mixin (stops 15K errors/session)
- DH config patch (ignoredDimensionCsv)
- Lightmap always updated (MyGameRenderer)

## Upstream history

This fork builds on top of upstream IPSable v0.5.0 (commit f5e0471).
