package rs.sud.eaukcija.basemap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Checksum-validates a build and publishes its pointer with one atomic rename. */
public final class BasemapArtifactActivator {

    public static final String ACTIVE_POINTER = "ACTIVE";
    private static final String ACTIVATION_LOCK = ".activation.lock";

    private final BasemapArtifactValidator validator;

    public BasemapArtifactActivator(BasemapArtifactValidator validator) {
        this.validator = validator;
    }

    public BasemapSnapshot activate(Path assetDirectory, String buildId) {
        BasemapArtifactValidator.requireBuildId(buildId);
        Path root = assetDirectory.toAbsolutePath().normalize();
        requireRoot(root);
        Path lockPath = root.resolve(ACTIVATION_LOCK);
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = lockChannel.lock()) {
            BasemapSnapshot validated = validator.validate(root, buildId);
            publishPointer(root, buildId);
            return validated;
        } catch (BasemapArtifactException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BasemapArtifactException("could not activate basemap bundle", exception);
        }
    }

    private static void publishPointer(Path root, String buildId) throws IOException {
        Path temporary = Files.createTempFile(root, ".ACTIVE.", ".tmp");
        boolean published = false;
        try {
            byte[] bytes = (buildId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        root.resolve(ACTIVE_POINTER),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new BasemapArtifactException(
                        "filesystem does not support atomic basemap pointer replacement", exception);
            }
            published = true;
            // fsyncing the temporary file makes its contents durable; fsyncing
            // the containing directory after rename makes the new ACTIVE name
            // durable across a sudden power loss as well.
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException exception) {
                throw new BasemapArtifactException(
                        "basemap pointer activated but could not be made durable", exception);
            }
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void requireRoot(Path root) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new BasemapArtifactException("basemap asset directory does not exist");
        }
    }
}
