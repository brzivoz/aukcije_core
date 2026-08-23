package rs.sud.eaukcija.basemap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps serving the last good immutable snapshot while a new pointer is validated. */
@Component
public final class BasemapArtifactRegistry implements InitializingBean, DisposableBean {

    private static final PointerObservation UNCHECKED =
            new PointerObservation("<unchecked>", null, null);

    private final Path assetDirectory;
    private final Duration pollInterval;
    private final BasemapArtifactValidator validator;
    private final AtomicReference<BasemapSnapshot> active = new AtomicReference<>();
    private final ScheduledExecutorService watcher;

    private volatile PointerObservation lastAttempt = UNCHECKED;
    private volatile String pointerVersion;
    private volatile String warning;
    private volatile Instant checkedAt;

    public BasemapArtifactRegistry(
            @Value("${basemap.assets.directory:${BASEMAP_ASSET_DIRECTORY:data/basemap}}")
            String assetDirectory,
            @Value("${basemap.assets.poll-interval:PT1S}") Duration pollInterval,
            BasemapArtifactValidator validator) {
        this.assetDirectory = Path.of(assetDirectory).toAbsolutePath().normalize();
        this.pollInterval = pollInterval;
        this.validator = validator;
        this.watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "basemap-active-pointer-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterPropertiesSet() {
        refreshNow();
        long delayMillis = Math.max(50L, pollInterval.toMillis());
        watcher.scheduleWithFixedDelay(
                this::refreshSafely, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        watcher.shutdownNow();
    }

    public BasemapSnapshot snapshot() {
        BasemapSnapshot snapshot = active.get();
        if (snapshot == null) {
            refreshNow();
            snapshot = active.get();
        }
        if (snapshot == null) {
            throw new BasemapArtifactException("no validated basemap bundle is active");
        }
        return snapshot;
    }

    public BasemapStatus status() {
        BasemapSnapshot snapshot = active.get();
        return new BasemapStatus(
                snapshot != null,
                snapshot == null ? "UNAVAILABLE" : "AVAILABLE",
                snapshot == null ? null : snapshot.buildId(),
                pointerVersion,
                snapshot == null ? null : snapshot.artifactSha256(),
                snapshot == null ? null : snapshot.artifactSizeBytes(),
                snapshot == null ? null : snapshot.loadedAt(),
                checkedAt,
                warning);
    }

    /** Synchronous hook for startup, focused tests, and an unavailable first request. */
    public synchronized void refreshNow() {
        PointerObservation observed = observePointer();
        if (observed.equals(lastAttempt)) {
            return;
        }
        lastAttempt = observed;
        checkedAt = Instant.now();
        pointerVersion = observed.version();
        if (observed.problem() != null) {
            warning = observed.problem();
            return;
        }
        try {
            BasemapSnapshot candidate = validator.validate(assetDirectory, observed.version());
            active.set(candidate);
            warning = null;
        } catch (RuntimeException exception) {
            // Do not replace the previous snapshot. The warning is deliberately
            // a stable code instead of a path- or manifest-bearing message.
            warning = "ACTIVE_POINTER_REJECTED";
        }
    }

    private void refreshSafely() {
        try {
            refreshNow();
        } catch (RuntimeException exception) {
            warning = "ACTIVE_POINTER_CHECK_FAILED";
            checkedAt = Instant.now();
        }
    }

    private PointerObservation observePointer() {
        Path pointer = assetDirectory.resolve(BasemapArtifactActivator.ACTIVE_POINTER);
        if (Files.isSymbolicLink(pointer)) {
            return new PointerObservation("symlink", null, "ACTIVE_POINTER_INVALID");
        }
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return new PointerObservation("missing", null, "ACTIVE_POINTER_MISSING");
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    pointer, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.size() > 256) {
                return new PointerObservation(
                        fingerprint(attributes, "oversized"), null, "ACTIVE_POINTER_INVALID");
            }
            String version = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (!BasemapArtifactValidator.BUILD_ID.matcher(version).matches()) {
                return new PointerObservation(
                        fingerprint(attributes, version), null, "ACTIVE_POINTER_INVALID");
            }
            return new PointerObservation(fingerprint(attributes, version), version, null);
        } catch (IOException | RuntimeException exception) {
            return new PointerObservation(
                    "unreadable-" + Instant.now().toEpochMilli(),
                    null,
                    "ACTIVE_POINTER_UNREADABLE");
        }
    }

    private static String fingerprint(BasicFileAttributes attributes, String content) {
        return attributes.lastModifiedTime().toMillis()
                + ":" + attributes.size()
                + ":" + Objects.toString(attributes.fileKey(), "")
                + ":" + content;
    }

    private record PointerObservation(String fingerprint, String version, String problem) {
    }
}
