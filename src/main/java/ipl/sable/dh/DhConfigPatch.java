package ipl.sable.dh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Patches Distant Horizons' config file at startup to add the
 * {@code ipl_sable:sublevels} hosting dimension to the ignored dimensions list.
 *
 * <p><b>Why this is necessary:</b> DH's {@code LocalSaveStructure} accumulates
 * data paths from all previously-registered dimensions. The
 * {@code ipl_sable:sublevels} hosting dimension is the first extra dimension
 * registered after the overworld, so it pollutes the path list for ALL
 * subsequent dimensions (nether, end, etc.). DH then reads/writes LOD data
 * from the wrong dimension's files, causing "wrong terrain visible" when
 * portals link dimensions.
 *
 * <p><b>How:</b> DH reads its config from {@code config/DistantHorizons.toml}.
 * The config has an {@code ignoredDimensionCsv} key under
 * {@code [client.advanced.graphics.experimental]} — a comma-separated list of
 * dimension resource locations where DH won't render. This class adds
 * {@code ipl_sable:sublevels} to that CSV.
 *
 * <p><b>Config format (DH 3.2.0-b):</b>
 * <pre>
 * [client.advanced.graphics.experimental]
 *     # A comma separated list of dimension resource locations where DH won't render.
 *     # Example: "minecraft:the_nether,minecraft:the_end"
 *     ignoredDimensionCsv = ""
 * </pre>
 *
 * <p>There is no {@code dimensionIgnoring} boolean — the CSV is always
 * processed, and an empty string means "render all dimensions".
 *
 * <p>This is a config-level fix, not a code-level fix. It's more robust than
 * a @Pseudo mixin because it uses DH's own config system. The downside is
 * that it modifies the user's config file — but it only adds the hosting
 * dimension to the ignore list, which is the correct behavior for all
 * IPSable+DH installations.
 *
 * <p>Called from {@code IPModEntryClient.onInitializeClient} when both DH and
 * Sable are detected.
 */
public final class DhConfigPatch {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-dh-compat");

    /** The dimension ID of the IPSable hosting dimension. */
    public static final String HOSTING_DIMENSION = "ipl_sable:sublevels";

    /** The DH config key for the ignored dimensions CSV. */
    private static final String CSV_KEY = "ignoredDimensionCsv";

    private DhConfigPatch() {}

    /**
     * Patches the DH config file to ignore the IPSable hosting dimension.
     *
     * @param configDir The Minecraft instance's config directory
     *                  (typically {@code .minecraft/config}).
     */
    public static void patchConfig(Path configDir) {
        Path dhConfig = configDir.resolve("DistantHorizons.toml");

        if (!Files.exists(dhConfig)) {
            LOG.info("[IPL-DH-COMPAT] DistantHorizons.toml not found at {}; " +
                "DH may not have been loaded yet. Will retry on next launch.", dhConfig);
            return;
        }

        try {
            List<String> lines = Files.readAllLines(dhConfig);
            boolean modified = false;
            boolean csvFound = false;
            boolean hostingAlreadyIgnored = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                // DH 3.2.0-b config format:
                //   ignoredDimensionCsv = ""
                //   ignoredDimensionCsv = "minecraft:the_nether,minecraft:the_end"
                if (line.matches("^\\s*" + CSV_KEY + "\\s*=.*")) {
                    csvFound = true;
                    if (line.contains(HOSTING_DIMENSION)) {
                        hostingAlreadyIgnored = true;
                    } else {
                        String newLine = addToCsv(line);
                        if (newLine != null) {
                            lines.set(i, newLine);
                            modified = true;
                            LOG.info("[IPL-DH-COMPAT] Added {} to DH {} CSV", HOSTING_DIMENSION, CSV_KEY);
                        }
                    }
                    break; // There's only one occurrence
                }
            }

            if (hostingAlreadyIgnored) {
                LOG.info("[IPL-DH-COMPAT] {} already in DH {} CSV, no change needed", HOSTING_DIMENSION, CSV_KEY);
                return;
            }

            if (modified) {
                Files.write(dhConfig, lines, StandardOpenOption.TRUNCATE_EXISTING);
                LOG.info("[IPL-DH-COMPAT] Patched DH config to ignore {} — prevents LOD data collision across dimensions", HOSTING_DIMENSION);
                LOG.info("[IPL-DH-COMPAT] NOTE: DH reads config at startup. A relaunch is required for this fix to take effect.");
            } else if (!csvFound) {
                LOG.warn("[IPL-DH-COMPAT] DH config exists but '{}' key not found. " +
                    "DH config format may have changed. Manual fix: add '" + HOSTING_DIMENSION +
                    "' to DH's ignored dimensions in the config screen.", CSV_KEY);
            }
        } catch (IOException e) {
            LOG.warn("[IPL-DH-COMPAT] Failed to patch DH config: {}", e.getMessage());
        }
    }

    /**
     * Parses a TOML line like {@code ignoredDimensionCsv = "dim1,dim2"} and
     * adds the hosting dimension to the CSV value. Returns null if the line
     * can't be parsed.
     */
    private static String addToCsv(String line) {
        int eqIdx = line.indexOf('=');
        if (eqIdx < 0) return null;

        String prefix = line.substring(0, eqIdx + 1);
        String value = line.substring(eqIdx + 1).trim();

        // Value should be a quoted string: "" or "dim1,dim2"
        if (value.startsWith("\"") && value.endsWith("\"")) {
            String inner = value.substring(1, value.length() - 1);
            String newInner;
            if (inner.isEmpty()) {
                newInner = HOSTING_DIMENSION;
            } else {
                newInner = HOSTING_DIMENSION + "," + inner;
            }
            return prefix + " \"" + newInner + "\"";
        }

        // Unquoted value (unlikely but handle it)
        return prefix + " " + HOSTING_DIMENSION + "," + value;
    }
}
