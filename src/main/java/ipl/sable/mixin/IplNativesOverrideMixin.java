package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Load IPSable's locally-built Sable natives instead of the ones bundled in the sable jar
 * (phase 4 of the portal-physics spec: aperture contact clipping + contact-impulse readout
 * fidelity live in the Rust layer).
 *
 * <p>Sable re-extracts its bundled {@code .l4z} natives with {@code REPLACE_EXISTING} on
 * every launch, so swapping the extracted file can't stick; and repacking the sable jar
 * couples our deploy loop to theirs. Instead: this mod carries the patched DLL as a
 * resource ({@code /natives_ipl/}), and this mixin — whose handler executes as merged
 * {@code Rapier3D} code, i.e. the exact classloader whose natives the JVM resolves —
 * extracts and {@code System.load}s it at the head of {@code loadLibrary}, cancelling the
 * stock path. The local build is built from the matching sable release tag
 * (5-arg {@code newVoxelCollider}, no-op {@code dispose}).
 *
 * <p>Fail-open: any problem (missing resource — e.g. non-Windows platforms we don't bundle,
 * IO error, link error) logs and falls through to Sable's stock natives.
 * Kill switch: {@code -Dipl.sable.customNatives=false}.
 */
@Pseudo
@Mixin(value = Rapier3D.class, remap = false)
public abstract class IplNativesOverrideMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipl$loadCustomNatives(CallbackInfo ci) {
        if ("false".equalsIgnoreCase(System.getProperty("ipl.sable.customNatives", "true"))) {
            return;
        }

        String arch = System.getProperty("os.arch", "");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win") || arch.equals("arm") || arch.startsWith("aarch64")) {
            return; // only the Windows x86_64 build is bundled; stock natives elsewhere
        }

        // Resource lookup notes: this handler executes as MERGED Rapier3D code (sable's
        // module), so the lookup must anchor on a real class in OUR module — and module
        // resource encapsulation can still null it, so fall back to the classloader view.
        String resource = "/natives_ipl/sable_rapier_x86_64_windows.dll";
        try {
            InputStream in = ipl.sable.natives.IplRapierNatives.class.getResourceAsStream(resource);
            if (in == null) {
                in = ipl.sable.natives.IplRapierNatives.class.getClassLoader()
                    .getResourceAsStream("natives_ipl/sable_rapier_x86_64_windows.dll");
            }
            if (in == null) {
                org.slf4j.LoggerFactory.getLogger("ipl-natives").warn(
                    "[IPL-NATIVES] bundled DLL resource not visible ({}); using sable's stock natives",
                    resource);
                return;
            }
            try (InputStream stream = in) {
                Path dir = Paths.get(".sable", "natives-ipl");
                Files.createDirectories(dir);
                Path dll = dir.resolve("sable_rapier_ipl_x86_64_windows.dll");
                Files.copy(stream, dll, StandardCopyOption.REPLACE_EXISTING);
                System.load(dll.toAbsolutePath().toString());
                ipl.sable.natives.IplRapierNatives.markAvailable();
                org.slf4j.LoggerFactory.getLogger("ipl-natives").info(
                    "[IPL-NATIVES] loaded IPSable-built sable_rapier natives from {}", dll.toAbsolutePath());
                ci.cancel();
            }
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("ipl-natives").error(
                "[IPL-NATIVES] failed to load custom natives — falling back to sable's bundled build", t);
            // fall through: stock loadLibrary proceeds
        }
    }
}
