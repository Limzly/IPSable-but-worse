# Sandbox-only build workarounds

These patches cap JVM memory so IPSable can build on hosts with only 4GB RAM.
They are NOT applied to the published fork because upstream dev machines can
spare the default 6g gradle heap and 4g decompiler heap.

## To re-apply locally (e.g. on a 4GB cloud sandbox)

```bash
cd /home/z/my-project/ipsable-fork
git apply sandbox-memory-caps.patch
```

## To revert

```bash
cd /home/z/my-project/ipsable-fork
git apply -R sandbox-memory-caps.patch
```

## What the patch does

1. `gradle.properties`: `org.gradle.jvmargs` 6g -> 2g + `-XX:MaxMetaspaceSize=512m`
2. `build.gradle`: adds `tasks.withType(JavaExec).configureEach { maxHeapSize = '1500m' }`
3. `build.gradle`: adds `subsystems { decompiler { maxMemory = '1024m' } }` to override NeoGradle's hardcoded 4g Vineflower default

The third one is the actual fix that lets the build complete without OOM on a
4GB host. The first two are belt-and-braces.

If you only need the decompiler cap (the most impactful single change), apply
just that block:

```groovy
// In build.gradle, after the existing `tasks.withType(JavaCompile)` block:
subsystems {
    decompiler {
        maxMemory = '1024m'
    }
}
```
