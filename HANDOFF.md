# HANDOFF DOCUMENT - IPSable-but-worse Fork

This document contains EVERYTHING a new AI session needs to continue work on this
fork. The new session will NOT have access to the current conversation history or
local files. Read this document completely before starting any work.

---

## WHAT THIS PROJECT IS

This is a fork of IPSable (github.com/r2smith141/IPSable) which itself is a fork
of Immersive Portals for NeoForge (github.com/iPortalTeam/ImmersivePortalsModForNeo).
The fork is for the Create Convoluted modpack (modrinth.com/modpack/create-convoluted).

The goal: make Immersive Portals work with Sable physics + Distant Horizons + Iris
shaders + Sodium + Veil + Flywheel in a single modpack on NeoForge 1.21.1.

The user is Limzly. Their GitHub: github.com/Limzly. Their Modrinth: modrinth.com/user/Limzly
Their writing style: casual, direct, no em dashes, no formal grammar they dont use.
Look at their modpack page for voice reference.

---

## GIT REPO DETAILS

- Fork URL: https://github.com/Limzly/IPSable-but-worse
- Upstream: https://github.com/r2smith141/IPSable (branch: default)
- Working copy location in sandbox: /home/z/my-project/ipsable-fork
- Baseline tag: fork-baseline-v0.5.0 (marks upstream IPSable v0.5.0, commit f5e0471)
- Current HEAD: commit 787919d (indev, no version tags)
- Branch: default (same as upstream)

### SSH ACCESS FOR PUSHING

The sandbox does NOT have ssh installed. We use a paramiko-based SSH wrapper.

- Private key: /home/z/my-project/.ssh/ipsable_fork_ed25519
- Public key: registered as a deploy key on the GitHub fork with write access
- SSH wrapper script: /home/z/my-project/scripts/git-ssh-wrapper.py
- Git remote origin-fork is configured to use the wrapper via:
  git config core.sshCommand /home/z/my-project/scripts/git-ssh-wrapper.py

The deploy key is registered on GitHub at:
https://github.com/Limzly/IPSable-but-worse/settings/keys

If the key stops working (e.g. new sandbox session), the user needs to add the
NEW public key as a deploy key. Generate a new key with:
  python3 /home/z/my-project/scripts/gen-deploy-key.py
The public key will be printed. User adds it to GitHub deploy keys with write access.

### PUSH WORKFLOW

1. Make sure paramiko is installed: python3 -m pip install paramiko
2. cd /home/z/my-project/ipsable-fork
3. git add -A && git commit -m "..."
4. git push origin-fork default

If push fails with auth error, regenerate the deploy key and ask user to add it.

---

## BUILD ENVIRONMENT

- JDK 21 required (NOT 24+, Gradle 8.10.2 doesnt support it)
- Installed via SDKMAN: ~/.sdkman/candidates/java/21.0.5-tem
- SDKMAN may get wiped on session restart. Reinstall with:
  curl -s "https://get.sdkman.io" | bash
  source ~/.sdkman/bin/sdkman-init.sh
  sdk install java 21.0.5-tem
- Activate with: export JAVA_HOME=~/.sdkman/candidates/java/21.0.5-tem

### Sandbox memory limits

The sandbox has 4GB RAM, no swap. The build OOMs with default settings.
Apply sandbox memory caps before building:
  See: /home/z/my-project/ipsable-fork/sandbox-memory-caps.patch
  Apply: git apply sandbox-memory-caps.patch
  This caps gradle to 2g heap and Vineflower decompiler to 1g.
  Do NOT commit these caps. They are sandbox-only.

### User's build commands (Windows PowerShell)

The user builds on their own machine (Windows, JDK 21 at C:\Program Files\Java\jdk-21).
Give them these commands when they need to build:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
cd D:\Code\IPSable\IPSable-but-worse
git pull
./gradlew jarJar --no-daemon
```

The jar is at: build/libs/immersive-portals-sable-compat-0.6.0+ip-6.0.7.jar

---

## TARGET MOD STACK

| Mod | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 |
| Sable | 2.0.3+ |
| Create | 6.0.10 |
| Create Aeronautics | 1.3.0+ |
| Sodium | 0.8.13-beta.2 |
| Iris | 1.8.14-beta.1 |
| Distant Horizons | 3.2.0-b |
| Veil | 4.1.4 |
| Flywheel | 1.0.6 |

The modpack is Create Convoluted 2.0.0 on Modrinth (unlisted, ~250 mods).
Disabled mods in the modpack: C2ME, Fabric API, FancyMenu, Ars Nouveau,
Dark Loading Screen, Farmer's Respite. These were disabled to reduce conflicts.

---

## WHAT THE FORK HAS DONE

### Confirmed working (do not revert)

1. **Sound Physics log fix** (MixinBlockGetter.java)
   - Changed error log with full stack trace to a one-line warning
   - The 30-block raycast clamp behavior is unchanged

2. **Auto-enable compatibility render mode** (IPModEntryClient.java)
   - When both Iris and DH are detected, sets IPGlobal.renderMode to compatibility
   - Uses IrisCompatibilityPortalRenderer instead of IrisPortalRenderer
   - Deferred to CLIENT_TASK_LIST because IPConfig.getConfig() is null during onInitializeClient

3. **DH OverrideInjector mixin** (IplDhOverrideInjectorMixin.java)
   - @Pseudo mixin on DH's OverrideInjector.bind
   - Cancels the call when PortalRendering.isRendering() is true
   - Stops ~15,000 IllegalStateException errors per session

4. **DH config patch** (DhConfigPatch.java)
   - Patches DistantHorizons.toml at startup
   - Adds ipl_sable:sublevels to ignoredDimensionCsv key
   - DH 3.2.0-b uses key "ignoredDimensionCsv" under [client.advanced.graphics.experimental]
   - Requires a relaunch to take effect (DH reads config at startup)

5. **Lightmap always update** (MyGameRenderer.java)
   - Always updates lightmap when switching dimensions for portal render
   - Previously only updated if dimension was new (cached dimensions reused stale lightmap)

### Latest fixes (commit 787919d, NOT YET TESTED by user)

6. **Bug 1: Terrain disappears near portal** (IrisCompatibilityPortalRenderer.java)
   - Added defensive FrontClipping.disableClipping() after popPortalLayer()
   - Under Iris, the mixin's after-hook may not fire, leaving clip plane enabled

7. **Bug 2: Liquids/particles through terrain** (IplProgramBindHook.java)
   - Changed early return to write no-clip sentinel (0,0,0,1) before returning
   - Clears stale clip equations from previous portal renders

8. **Bug 4: Shadow stripes** (FrontClipping.java + IplProgramBindHook.java)
   - Added refreshClipEquationForCurrentCamera() method
   - Re-computes the camera-relative clip equation on every shader bind
   - Root cause: equation is camera-relative but only uploaded per shader bind

9. **Bug 7: DH data collision** (IplDhSkipHostingDimensionMixin + IplServerLevelDhTrackerMixin + DhDimensionTracker)
   - @Pseudo mixin cancels AbstractDhLevel constructor for ipl_sable:sublevels
   - Flag set by ServerLevel mixin at RETURN (can't use HEAD, requires static handler)
   - Flag auto-clears after 1 second

### What does NOT work yet

- Bug 3: Portal visible through blocks with shaders (Iris depth buffer management)
- Bug 5: Ghost terrain (should be fixed by Bug 4, needs testing)
- Bug 6: Wrong water color (biome color resolver not swapped during portal render)
- The user reported "portal is visible through blocks with shaders" as a new bug

### What was tried and REVERTED (do not try again)

- glClear(GL_DEPTH_BUFFER_BIT) before portal content - didnt fix invisible terrain
- Deferred to main FB blit after each portal - didnt fix ghost blocks
- Removed setPipeline(null) - didnt fix black screen, caused portal-through-blocks
- FrontClipping.ADJUSTMENT 0.01 to 0.1 - didnt fix shadow stripes

---

## KEY FILES AND THEIR ROLES

### Rendering pipeline (upstream IPSable code, do not modify lightly)

- src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatibilityPortalRenderer.java
  The compatibility renderer used when Iris + DH are both present. Renders portal
  content into the main framebuffer, composites into a deferred buffer.

- src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisPortalRenderer.java
  The normal Iris renderer. NOT used when compatibility mode is on. Has a broken
  framebuffer blit under Iris 1.8.14.

- src/main/java/qouteall/imm_ptl/core/render/FrontClipping.java
  The clip plane system. Manages the camera-relative clip equation that crops
  destination dimension terrain to the portal aperture. We added
  refreshClipEquationForCurrentCamera() to fix stale equations.

- src/main/java/qouteall/imm_ptl/core/render/MyGameRenderer.java
  The world render re-entry code. switchAndRenderTheWorld() saves/restores GL
  state, swaps levels/renderers/cameras, calls renderLevel for the destination
  dimension. We modified the lightmap update to always fire.

- src/main/java/ipl/sable/render/IplProgramBindHook.java
  Hook that uploads the clip equation to shaders on every program bind.
  Called by IplGlUseProgramProbeMixin and IplProgramManagerProbeMixin.

- src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer.java
  Injects into LevelRenderer.renderLevel. Sets up clip plane before
  renderSectionLayer and disables it after. The after-hook may not fire under Iris.

- src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer_Optional.java
  Additional LevelRenderer hooks. Re-uploads clip equation on shader apply.

### Our fork's additions

- src/main/java/ipl/sable/dh/DhConfigPatch.java - patches DH config file
- src/main/java/ipl/sable/dh/DhDimensionTracker.java - flag for hosting dim
- src/main/java/ipl/sable/mixin/IplDhOverrideInjectorMixin.java - stops DH error spam
- src/main/java/ipl/sable/mixin/IplDhSkipHostingDimensionMixin.java - cancels DhLevel creation
- src/main/java/ipl/sable/mixin/IplServerLevelDhTrackerMixin.java - sets flag on ServerLevel creation
- src/main/java/qouteall/imm_ptl/core/mixin/common/miscellaneous/MixinBlockGetter.java - sound physics fix
- src/main/java/qouteall/imm_ptl/core/platform_specific/IPModEntryClient.java - auto-compat + DH config patch

### Config files

- src/main/resources/ipl_sable.mixins.json - our mixin config (defaultRequire: 0)
- gradle.properties - mod metadata, fork_version=0.6.0 (indev, no version bumps)
- build.gradle - build config, has sandbox memory caps commented out

### Documentation

- RENDERING_INVESTIGATION.md - bug catalog with root causes and file:line refs
- BUILD.md - JDK 21 setup instructions for Windows/Linux
- README.md - project description in Limzly's voice
- changelog.md - what works, what doesnt, what was reverted
- sandbox-memory-caps.patch - memory caps for 4GB sandbox builds

---

## BUG CATALOG (from RENDERING_INVESTIGATION.md)

### Bug 1: Terrain disappears near portal
Status: FIX APPLIED (commit 787919d), untested
Root cause: FrontClipping.disableClipping() not called after popPortalLayer
Fix: defensive disableClipping() in doRenderPortal

### Bug 2: Liquids/particles through terrain
Status: FIX APPLIED (commit 787919d), untested
Root cause: IplProgramBindHook early return left stale clip equations
Fix: write no-clip sentinel (0,0,0,1) before returning

### Bug 3: Portal visible through blocks with shaders
Status: NOT FIXED
Root cause: Iris depth buffer management in compatibility renderer
The portal quad renders on top of terrain because depth clamp is enabled
and Iris's depth buffer state is unknown

### Bug 4: Shadow stripes
Status: FIX APPLIED (commit 787919d), untested
Root cause: clip equation is camera-relative but only uploaded per shader bind
Stripes are parallel to portal plane, thickness proportional to camera movement
Fix: refreshClipEquationForCurrentCamera() on every onBind

### Bug 5: Ghost terrain
Status: SHOULD BE FIXED by Bug 4 fix, untested
Root cause: consequence of Bug 4 (stale clip equation lets destination terrain leak)

### Bug 6: Wrong water color
Status: NOT FIXED
Root cause: biome color resolver not swapped during portal render
Water color comes from BiomeColors.getAverageWaterColor() which is dimension-specific
MyGameRenderer swaps level/renderer/camera but NOT BlockColors or biome resolver

### Bug 7: DH data collision
Status: FIX APPLIED (commit 787919d), untested
Root cause: DH LocalSaveStructure accumulates data paths from all dims
Fix: cancel AbstractDhLevel constructor for ipl_sable:sublevels

---

## USER'S STYLE AND RULES

1. No em dashes. Use regular hyphens or commas.
2. No formal grammar they dont use. Casual, direct tone.
3. Bug fixes are 0.0.x version changes, features are 0.x.0
4. We are indev - no version tags, no version bumps until things work
5. The user wants the mod to "just work" in the modpack with no complicated
   launch instructions or needing to reopen the game
6. The user said to skip reporting upstream (phase 1) and skip keeping modpack
   fixes separate (phase 2). Do phase 4 (DH mixin) no matter what.
7. The user wants research docs BEFORE coding, not guessing.
8. The user wants the investigation written from THEIR perspective and their reports.
9. DO NOT use em dashes or grammar they dont use.

---

## NEXT STEPS

1. The user needs to build commit 787919d and test bugs 1, 2, 4, 7
2. If bugs 1, 2, 4 are fixed, investigate Bug 3 (portal through blocks) and
   Bug 6 (water color)
3. Bug 6 (water color): need to swap BlockColors/biome resolver in
   MyGameRenderer.switchAndRenderTheWorld when entering portal content render
4. Bug 3 (portal through blocks): need to investigate Iris depth buffer state
   before drawPortalAreaWithFramebuffer

### Potential future work the user mentioned

- "would be cool to see lighting from both dimensions affect how shaders look
  instead of there being a cut" - this is a long-term feature request, not a bug

---

## IMPORTANT NOTES

- The sandbox bash tool times out after ~2 minutes. Long builds (jarJar takes
  10-15 min) will timeout. Run them in the background with nohup + logging.
- The sandbox session may restart, wiping ~/.ssh and ~/.sdkman. The key and JDK
  are in /home/z/my-project/.ssh/ and need to be re-linked to ~ after restart.
- Paramiko must be reinstalled after session restart: python3 -m pip install paramiko
- The gradlew file may lose execute permission: chmod +x gradlew
- File modes get messed up on clone (644 becomes 755). Reset with:
  git diff --name-only | xargs -I{} git update-index --chmod=-x "{}"

---

## CONTACT

- User: Limzly on z.ai web (session web-1908d88f-d4db-4f53-9bc8-32a2b6e4176f)
- GitHub fork: https://github.com/Limzly/IPSable-but-worse
- Modpack: https://modrinth.com/modpack/create-convoluted
- Worklog: /home/z/my-project/worklog.md (shared, append-only)
