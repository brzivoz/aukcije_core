package rs.sud.eaukcija.addressregistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

/** Downloads/copies one artifact into an isolated staging directory and verifies it. */
@Component
final class AddressRegistryArtifactStager {

    record Artifact(
            Path directory,
            Path gpkg,
            Instant downloadedAt,
            long sourceBytes,
            String sourceSha256,
            String archiveMember,
            long gpkgBytes,
            String gpkgSha256,
            long downloadMillis) implements AutoCloseable {

        @Override
        public void close() {
            try {
                if (!Files.exists(directory)) {
                    return;
                }
                try (var paths = Files.walk(directory)) {
                    paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // The import result is already known. Retaining a staging file is
                            // safer than masking that result with a cleanup failure.
                        }
                    });
                }
            } catch (IOException ignored) {
                // See above: best-effort cleanup only.
            }
        }
    }

    Artifact stage(AddressRegistrySourceSettings properties) {
        Instant started = Instant.now();
        Path root = properties.getWorkDirectory().toAbsolutePath().normalize();
        Path directory = null;
        try {
            Files.createDirectories(root);
            FileStore store = Files.getFileStore(root);
            if (store.getUsableSpace() < properties.getMinimumFreeBytes()) {
                throw new AddressRegistryImportException(
                        "INSUFFICIENT_DISK",
                        "working directory has " + store.getUsableSpace() + " usable bytes; at least "
                                + properties.getMinimumFreeBytes() + " are required");
            }
            directory = Files.createTempDirectory(root, "snapshot-");
            Path source = directory.resolve("source.download");
            copySource(properties.getSourceUri(), source, properties.getMaximumGpkgBytes() * 2);

            long sourceBytes = Files.size(source);
            String sourceSha256 = sha256(source);
            if (!sourceSha256.equals(properties.getExpectedSha256())) {
                deleteDirectory(directory);
                throw new AddressRegistryImportException(
                        "CHECKSUM_MISMATCH",
                        "download SHA-256 " + sourceSha256 + " does not match expected "
                                + properties.getExpectedSha256());
            }

            Extracted extracted = isZip(source)
                    ? extractSingleGpkg(source, directory, properties.getMaximumGpkgBytes())
                    : moveGpkg(source, directory, properties.getMaximumGpkgBytes(), sourceSha256);
            if (properties.getExpectedGpkgSha256() != null
                    && !extracted.sha256().equals(properties.getExpectedGpkgSha256())) {
                deleteDirectory(directory);
                throw new AddressRegistryImportException(
                        "GPKG_CHECKSUM_MISMATCH",
                        "GPKG SHA-256 " + extracted.sha256() + " does not match expected "
                                + properties.getExpectedGpkgSha256());
            }

            return new Artifact(
                    directory,
                    extracted.path(),
                    Instant.now(),
                    sourceBytes,
                    sourceSha256,
                    extracted.archiveMember(),
                    extracted.bytes(),
                    extracted.sha256(),
                    Duration.between(started, Instant.now()).toMillis());
        } catch (AddressRegistryImportException e) {
            if (directory != null) {
                deleteDirectory(directory);
            }
            throw e;
        } catch (IOException e) {
            if (directory != null) {
                deleteDirectory(directory);
            }
            throw new AddressRegistryImportException("STAGING_IO", "could not stage Address Registry artifact", e);
        }
    }

    private static void copySource(URI uri, Path destination, long maximumBytes) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) {
            Path source = scheme == null ? Path.of(uri.toString()) : Path.of(uri);
            if (!Files.isRegularFile(source)) {
                throw new AddressRegistryImportException("SOURCE_NOT_FOUND", "source file does not exist: " + source);
            }
            if (Files.size(source) > maximumBytes) {
                throw new AddressRegistryImportException("SOURCE_TOO_LARGE", "source exceeds configured byte limit");
            }
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new AddressRegistryImportException("UNSUPPORTED_SOURCE_URI", "source URI must use file, http, or https");
        }

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "aukcije-core-address-registry-import/1");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new AddressRegistryImportException("DOWNLOAD_HTTP", "official source returned HTTP " + status);
        }
        long contentLength = connection.getContentLengthLong();
        if (contentLength > maximumBytes) {
            throw new AddressRegistryImportException("SOURCE_TOO_LARGE", "source exceeds configured byte limit");
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
            copyLimited(input, output, maximumBytes, "SOURCE_TOO_LARGE");
        } finally {
            connection.disconnect();
        }
    }

    private static Extracted moveGpkg(
            Path source,
            Path directory,
            long maximumBytes,
            String sourceSha256) throws IOException {
        long bytes = Files.size(source);
        if (bytes > maximumBytes) {
            throw new AddressRegistryImportException("GPKG_TOO_LARGE", "GPKG exceeds configured byte limit");
        }
        Path gpkg = directory.resolve("address-registry.gpkg");
        Files.move(source, gpkg, StandardCopyOption.REPLACE_EXISTING);
        return new Extracted(gpkg, null, bytes, sourceSha256);
    }

    private static Extracted extractSingleGpkg(Path archive, Path directory, long maximumBytes) throws IOException {
        Path gpkg = directory.resolve("address-registry.gpkg");
        String member = null;
        long bytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".gpkg")) {
                    continue;
                }
                if (member != null) {
                    throw new AddressRegistryImportException(
                            "AMBIGUOUS_ARCHIVE", "archive contains more than one GPKG member");
                }
                member = entry.getName();
                if (entry.getSize() > maximumBytes) {
                    throw new AddressRegistryImportException("GPKG_TOO_LARGE", "GPKG exceeds configured byte limit");
                }
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(gpkg))) {
                    bytes = copyLimited(zip, output, maximumBytes, "GPKG_TOO_LARGE");
                }
            }
        }
        if (member == null || !Files.isRegularFile(gpkg)) {
            throw new AddressRegistryImportException("GPKG_MISSING", "archive does not contain one GPKG member");
        }
        return new Extracted(gpkg, member, bytes, sha256(gpkg));
    }

    private static boolean isZip(Path path) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(magic) != magic.length) {
                return false;
            }
        }
        return magic[0] == 'P' && magic[1] == 'K'
                && ((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6));
    }

    private static long copyLimited(InputStream input, OutputStream output, long maximumBytes, String errorCode)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximumBytes) {
                throw new AddressRegistryImportException(errorCode, "artifact exceeds configured byte limit");
            }
            output.write(buffer, 0, read);
        }
        return total;
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteDirectory(Path directory) {
        try {
            if (!Files.exists(directory)) {
                return;
            }
            try (var paths = Files.walk(directory)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort for a failed staging attempt.
                    }
                });
            }
        } catch (IOException ignored) {
            // Best effort for a failed staging attempt.
        }
    }

    private record Extracted(Path path, String archiveMember, long bytes, String sha256) {
    }
}
