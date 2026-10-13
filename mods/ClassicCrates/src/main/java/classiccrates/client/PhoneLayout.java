package classiccrates.client;

import classiccrates.ClassicCrates;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Android launchers of the PojavLauncher family (MojoLauncher, Amethyst ...) only load touch layouts from their own
 * "controlmap" folder, which players cannot easily reach. The game runs inside the launcher app there, so it may write
 * to that folder: this puts the pack's layout next to the launcher's own, where it shows up in the launcher's "Load"
 * list. The game folder on those launchers is &lt;launcher files&gt;/instances/&lt;profile&gt;, so the folder is two levels up;
 * on a PC that folder does not exist and nothing happens.
 */
final class PhoneLayout {

    private static final String FILE = "Switchyard-controls.json";
    private static final String RESOURCE = "/assets/classiccrates/phone/" + FILE;

    private PhoneLayout() {
    }

    /**
     * @return true when the layout was written or replaced in this start (so the player can be told once).
     */
    static boolean install() {
        try {
            Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Path instances = game.getParent();
            if (instances == null || instances.getParent() == null) {
                return false;
            }
            Path controlMap = instances.getParent().resolve("controlmap");
            if (!Files.isDirectory(controlMap)) {
                return false;
            }
            byte[] layout;
            try (InputStream in = PhoneLayout.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    return false;
                }
                layout = in.readAllBytes();
            }
            // The marker remembers which bundled layout was installed, so a pack update replaces the file once and a
            // layout the player then edits in the launcher is left alone.
            String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(layout));
            Path marker = FMLPaths.CONFIGDIR.get().resolve("classiccrates-phone-layout.txt");
            Path target = controlMap.resolve(FILE);
            if (Files.exists(target) && Files.exists(marker) && Files.readString(marker, StandardCharsets.UTF_8).trim().equals(version)) {
                return false;
            }
            Files.write(target, layout);
            Files.writeString(marker, version + "\n", StandardCharsets.UTF_8);
            ClassicCrates.LOGGER.info("Installed the Switchyard touch layout in {}", controlMap);
            return true;
        } catch (Exception e) {
            ClassicCrates.LOGGER.warn("Could not install the Switchyard touch layout", e);
            return false;
        }
    }
}
