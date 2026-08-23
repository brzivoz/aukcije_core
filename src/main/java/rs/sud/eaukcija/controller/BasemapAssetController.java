package rs.sud.eaukcija.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import rs.sud.eaukcija.basemap.BasemapArtifactException;
import rs.sud.eaukcija.basemap.BasemapArtifactRegistry;
import rs.sud.eaukcija.basemap.BasemapAsset;
import rs.sud.eaukcija.basemap.BasemapSnapshot;
import rs.sud.eaukcija.basemap.BasemapStatus;
import rs.sud.eaukcija.basemap.HttpByteRange;

/** Same-origin, conditionally cached, range-capable access to the active bundle. */
@RestController
public final class BasemapAssetController {

    private static final String CACHE_POLICY = "public, max-age=0, must-revalidate";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final BasemapArtifactRegistry registry;

    public BasemapAssetController(BasemapArtifactRegistry registry) {
        this.registry = registry;
    }

    @RequestMapping(
            value = "/api/basemap/status",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<BasemapStatus> status() {
        BasemapStatus status = registry.status();
        return ResponseEntity.status(status.healthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(status);
    }

    @RequestMapping(
            value = "/basemap/serbia.pmtiles",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void archive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        BasemapSnapshot snapshot = registry.snapshot();
        BasemapAsset asset = snapshot.asset("serbia.pmtiles").orElseThrow();
        setCommonHeaders(response, snapshot, asset, true);

        if (ifNoneMatch(request.getHeader(HttpHeaders.IF_NONE_MATCH), asset.etag())) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && !ifRangeMatches(request.getHeader(HttpHeaders.IF_RANGE), asset.etag())) {
            rangeHeader = null;
        }
        if (rangeHeader == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(asset.sizeBytes());
            writeIfGet(request, response, asset, 0, asset.sizeBytes());
            return;
        }

        HttpByteRange range;
        try {
            range = HttpByteRange.parse(rangeHeader, asset.sizeBytes());
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + asset.sizeBytes());
            response.setContentLengthLong(0);
            return;
        }
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(
                HttpHeaders.CONTENT_RANGE,
                "bytes " + range.start() + "-" + range.end() + "/" + asset.sizeBytes());
        response.setContentLengthLong(range.length());
        writeIfGet(request, response, asset, range.start(), range.length());
    }

    @RequestMapping(
            value = "/basemap/style.json",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void style(HttpServletRequest request, HttpServletResponse response) throws IOException {
        serveWholeAsset("style.json", request, response);
    }

    @RequestMapping(
            value = "/basemap/sprites/{filename:.+}",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void sprite(
            @PathVariable String filename,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (!filename.matches("light(?:@2x)?\\.(?:json|png)")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        serveWholeAsset("sprites/" + filename, request, response);
    }

    @RequestMapping(
            value = "/basemap/glyphs/{fontStack}/{range}.pbf",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void glyph(
            @PathVariable String fontStack,
            @PathVariable String range,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (fontStack.isBlank()
                || fontStack.contains("/")
                || !range.matches("[0-9]+-[0-9]+")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        serveWholeAsset("glyphs/" + fontStack + "/" + range + ".pbf", request, response);
    }

    @ExceptionHandler(BasemapArtifactException.class)
    public ResponseEntity<BasemapStatus> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(registry.status());
    }

    private void serveWholeAsset(
            String relativePath,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        BasemapSnapshot snapshot = registry.snapshot();
        BasemapAsset asset = snapshot.asset(relativePath).orElse(null);
        if (asset == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        setCommonHeaders(response, snapshot, asset, false);
        if (ifNoneMatch(request.getHeader(HttpHeaders.IF_NONE_MATCH), asset.etag())) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLengthLong(asset.sizeBytes());
        writeIfGet(request, response, asset, 0, asset.sizeBytes());
    }

    private static void setCommonHeaders(
            HttpServletResponse response,
            BasemapSnapshot snapshot,
            BasemapAsset asset,
            boolean ranges) {
        response.setContentType(asset.contentType());
        response.setHeader(HttpHeaders.ETAG, asset.etag());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_POLICY);
        response.setHeader("X-Basemap-Version", snapshot.buildId());
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (ranges) {
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
    }

    private static void writeIfGet(
            HttpServletRequest request,
            HttpServletResponse response,
            BasemapAsset asset,
            long start,
            long length) throws IOException {
        if (HttpMethod.HEAD.matches(request.getMethod())) {
            return;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(
                    asset.path(), StandardOpenOption.READ);
                OutputStream output = response.getOutputStream()) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new IOException("active basemap asset ended before its validated size");
                }
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        }
    }

    private static boolean ifNoneMatch(String header, String currentEtag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String currentOpaque = stripWeak(currentEtag.trim());
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .anyMatch(candidate -> candidate.equals("*")
                        || stripWeak(candidate).equals(currentOpaque));
    }

    private static boolean ifRangeMatches(String header, String currentEtag) {
        return header == null || (!header.startsWith("W/") && header.trim().equals(currentEtag));
    }

    private static String stripWeak(String etag) {
        return etag.startsWith("W/") ? etag.substring(2).trim() : etag;
    }
}
