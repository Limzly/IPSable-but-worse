# Dependency Resilience Plan

## The problem

Our fork has 110 Java classes that import Sable types directly. If Sable updates and changes its API (renames a class, removes a method, changes a signature), those classes will throw `NoSuchMethodError` / `NoSuchFieldError` / `ClassNotFoundException` at runtime. The `SableBridge.PRESENT` pattern only protects `SableImpl` (which is loaded lazily). The 110 direct-import classes in `ipl/sable/` are loaded eagerly by the classloader when mixins are applied.

63 of these are `@Pseudo` mixins which ARE safe (they silently don't apply if the target class is missing). But the other 47 non-mixin classes in `ipl/sable/client/`, `ipl/sable/atlas/`, `ipl/sable/transit/`, etc. are NOT safe.

Same problem applies to Iris (IrisInterface uses reflection on `LevelRenderer.pipeline` field), Veil (IplVeilCompat uses Veil event classes), DH (DhConfigPatch is safe, but IplDhOverrideInjectorMixin is @Pseudo so safe).

## The solution

### 1. Version detection + warnings at startup

Add a version check system that:
- Reads each dependency's actual version at runtime (via ModList.get().getModContainerById())
- Compares against the expected version (from gradle.properties)
- Logs a WARNING if there's a mismatch
- Shows a one-time chat message to the player if a critical mismatch is detected

This doesn't prevent crashes, but it makes them diagnosable. When Sable updates and something breaks, the user sees:
  "[IPSable] Warning: Sable version mismatch. Expected 2.0.4, found 2.0.5.
   Some features may not work. Report issues at github.com/Limzly/IPSable-but-worse/issues"

### 2. Try-catch guards on SableBridge dispatch

The SableBridge already has `PRESENT` flag. Extend it to catch errors on every dispatch:

  ```java
  public static BlockState lookupNonAirSubLevelBlockAt(Level world, Vec3 worldPos) {
      if (!PRESENT) return null;
      try {
          return SableImpl.lookupNonAirSubLevelBlockAt(world, worldPos);
      } catch (Throwable t) {
          LOG.error("[IPL-SABLE] Sable API call failed (version mismatch?): {}", t.getMessage());
          return null; // graceful fallback
      }
  }
  ```

This makes each SableBridge call resilient. If one Sable API method changes, that specific feature breaks gracefully (returns null/no-op) instead of crashing the game.

### 3. Lazy class loading for client classes

The 47 non-mixin client classes in `ipl/sable/client/` are loaded eagerly when the mixin processor scans the `ipl_sable.mixins.json` config. If any of these classes imports a Sable type that no longer exists, the class load fails and the mixin config fails to apply entirely.

Fix: move all direct Sable imports behind lazy-loaded wrapper classes (like SableImpl already does). The client classes should reference interfaces/wrappers, not Sable types directly. This is a big refactor (47 classes) but it's the proper fix.

### 4. Build-time version ranges in gradle

Instead of exact version pins in gradle.properties, use version ranges in build.gradle:

  ```groovy
  def sableVersion = findProperty('sable_version') ?: '+'
  implementation "dev.ryanhcode.sable:sable-neoforge-${minecraft_version}:${sableVersion}"
  ```

This lets the build resolve to whatever version is available, but we should still warn at runtime if the version is different from what we tested.

## Implementation priority

1. Version detection + warnings (quick, high value for diagnosis)
2. Try-catch guards on SableBridge (quick, prevents hard crashes)
3. Build-time version ranges (quick, lets us build against newer deps)
4. Lazy class loading refactor (slow, proper fix, do later)

## Which deps are at risk

| Dependency | Version pinned | Risk | Current protection |
|---|---|---|---|
| Sable | 2.0.4 | HIGH - 110 classes import it | SableBridge.PRESENT (partial) |
| Veil | 4.3.2 | MEDIUM - IplVeilCompat uses event classes | ModList.isLoaded check |
| Iris | 1.8.14-beta.1 | MEDIUM - reflection on field | IrisInterface pattern |
| Sodium | 0.8.12 | LOW - compat mixins only | @Pseudo / require=0 |
| DH | 3.2.0-b | LOW - @Pseudo mixin only | @Pseudo + require=0 |
| Create | 6.0.10 | LOW - not directly imported | None needed |
| Flywheel | 1.0.6 | LOW - compat mixins only | IPCompatMixinPlugin |
