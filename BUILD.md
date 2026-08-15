# Building IPSable-but-worse

This fork builds against **Minecraft 1.21.1** with **NeoForge 21.1.228** and
requires **JDK 21** (Eclipse Temurin recommended).

## TL;DR — if you hit `Unsupported class file major version 69`

Your default `java` is Java 24+ but Gradle 8.10.2 only supports up to Java 23.
Install JDK 21 and point Gradle at it. In PowerShell:

```powershell
# 1. Install Temurin JDK 21 (one-time)
winget install --id EclipseFoundation.Temurin.21.JDK --accept-source-agreements --accept-package-agreements

# 2. Find where it installed
$jdk21 = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Where-Object { $_.Name -like "jdk-21*" } | Select-Object -First 1).FullName
echo "Found JDK at: $jdk21"

# 3. Set JAVA_HOME for this PowerShell session
$env:JAVA_HOME = $jdk21

# 4. Verify Java 21 is active
java -version
# → should print "openjdk version \"21.x.x\""

# 5. Re-run the build (in your IPSable-but-worse directory)
./gradlew jarJar --no-daemon
```

To make `JAVA_HOME` persistent across all future PowerShell sessions:
```powershell
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", $env:JAVA_HOME, "User")
```
Then close and reopen PowerShell.

## Prerequisites

| Tool        | Version          | Notes |
|-------------|------------------|-------|
| JDK         | 21 (LTS)         | Required. Gradle 8.10.2 does NOT support running on Java 24+ (fails with `Unsupported class file major version 69`). |
| Git         | any              | Required to clone the repo. |
| Internet    |                  | First build downloads NeoGradle, ~1 GB of dependencies, and Minecraft mappings. |

If you have multiple JDKs installed (e.g. Java 25 for other projects), you must
point Gradle at JDK 21 explicitly — Gradle uses whatever `java` is on `PATH`
when it launches.

## Quick start (Windows / PowerShell)

```powershell
# 1. Install Temurin JDK 21 (skip if already installed)
winget install --id EclipseFoundation.Temurin.21.JDK

# 2. Find the install path (typically C:\Program Files\Eclipse Adoptium\jdk-21.x.x+x)
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Select-Object -First 1).FullName
echo "JAVA_HOME = $env:JAVA_HOME"

# 3. Verify it's Java 21
& "$env:JAVA_HOME\bin\java.exe" -version

# 4. Clone + build
git clone https://github.com/Limzly/IPSable-but-worse.git
cd IPSable-but-worse
./gradlew jarJar --no-daemon

# 5. Jar will be at:
ls build/libs/immersive-portals-sable-compat-*.jar
```

To make `JAVA_HOME` persistent across PowerShell sessions:
```powershell
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", $env:JAVA_HOME, "User")
```
Then open a new PowerShell window.

## Quick start (macOS / Linux)

```bash
# Install JDK 21 via SDKMAN (recommended on Linux/macOS)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.5-tem

# Verify
java -version

# Clone + build
git clone https://github.com/Limzly/IPSable-but-worse.git
cd IPSable-but-worse
./gradlew jarJar --no-daemon

# Jar will be at:
ls build/libs/immersive-portals-sable-compat-*.jar
```

## Common issues

### `Unsupported class file major version 69`

You're running Gradle on Java 25 (or 24). Gradle 8.10.2 only supports up to
Java 23. Install JDK 21 and point Gradle at it via `JAVA_HOME`.

You can also force Gradle to use a specific JDK without setting `JAVA_HOME`
globally:

```bash
./gradlew jarJar -Dorg.gradle.java.home="/path/to/jdk-21"
```

### `BUILD FAILED` on `:neoFormDecompile` with OOM / exit code 137

Your build host has insufficient RAM. NeoGradle's Vineflower decompiler
defaults to a 4 GB heap. The full build (Gradle daemon + decompiler + compile
+ jar) needs about 6-8 GB free RAM to be comfortable.

If you only have 4 GB available, apply the sandbox memory caps:

```bash
git apply sandbox-memory-caps.patch
./gradlew jarJar --no-daemon
```

This caps the Gradle daemon to 2 GB and the Vineflower decompiler to 1 GB.
The build will be slower but should complete. Revert before pushing any
changes upstream.

### `./gradlew: Permission denied`

Make the wrapper executable (Linux/macOS only):
```bash
chmod +x gradlew
```

### Build is slow / hangs on `:neoFormDecompile`

This is normal. The first build decompiles all of joined Minecraft 1.21.1
(~5000 classes) via Vineflower. Expect 5-15 minutes for this step on a
modern machine. Subsequent builds skip it (cached).

### Mixin errors at runtime but not at build time

The `ipl_sable.mixins.json` config is soft-applied: the mixins no-op via
`@Pseudo` if Sable isn't on the classpath. If you're testing without Sable
installed, you'll see log lines like `[IPL-SABLE] Sable not present; IP runs
in upstream-equivalent mode.` — that's expected.

## Build artifacts

After a successful build, the jar is at:
```
build/libs/immersive-portals-sable-compat-<fork_version>+ip-<mod_version>.jar
```

For v0.6.0, that's `immersive-portals-sable-compat-0.6.0+ip-6.0.7.jar`.

Drop this jar into your Minecraft instance's `mods/` folder. It replaces
both:
- `immersive_portals-6.0.7-all.jar` (upstream Immersive Portals)
- `immersive_portal_sable_bridge-0.1.jar` (the decrepit bridge, now removed)

## Target mod stack

This fork is built and tested against the Create Convoluted 2.0.0 modpack,
which uses:

| Mod                  | Version       |
|----------------------|---------------|
| Minecraft            | 1.21.1        |
| NeoForge             | 21.1.228      |
| Sable                | 2.0.3+        |
| Create               | 6.0.10        |
| Create Aeronautics   | 1.3.0+        |
| Flywheel             | 1.0.6         |
| Veil                 | 4.1.4         |
| Sodium               | 0.8.12        |

Other mod combinations are not validated. See `changelog.md` for known
compat risk areas.
