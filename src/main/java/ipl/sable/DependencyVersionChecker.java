package ipl.sable;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Checks dependency versions at startup and warns if they don't match
 * what we tested against. This doesn't prevent crashes, but it makes
 * them diagnosable.
 */
public final class DependencyVersionChecker {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-dep-check");

    /**
     * Expected versions from gradle.properties. Update these when
     * updating the build dependencies.
     */
    private static final Map<String, String> EXPECTED_VERSIONS = new HashMap<>();

    static {
        EXPECTED_VERSIONS.put("sable", "2.0.4");
        EXPECTED_VERSIONS.put("veil", "4.3.2");
        EXPECTED_VERSIONS.put("iris", "1.8.14-beta.1");
        EXPECTED_VERSIONS.put("sodium", "0.8.13-beta.2");
        EXPECTED_VERSIONS.put("distanthorizons", "3.2.0-b");
        EXPECTED_VERSIONS.put("create", "6.0.10");
        EXPECTED_VERSIONS.put("flywheel", "1.0.6");
    }

    private DependencyVersionChecker() {}

    public static void checkVersions() {
        for (Map.Entry<String, String> entry : EXPECTED_VERSIONS.entrySet()) {
            String modId = entry.getKey();
            String expected = entry.getValue();

            ModList.get().getModContainerById(modId).ifPresent(container -> {
                String actual = container.getModInfo().getVersion().toString();
                if (!actual.startsWith(expected) && !expected.startsWith(actual)) {
                    LOG.warn(
                        "[IPL-DEP-CHECK] {} version mismatch: expected {}, found {}. " +
                        "Some features may not work correctly. " +
                        "Report issues at https://github.com/Limzly/IPSable-but-worse/issues",
                        modId, expected, actual
                    );
                } else {
                    LOG.info("[IPL-DEP-CHECK] {} version OK: {}", modId, actual);
                }
            });
        }
    }
}
