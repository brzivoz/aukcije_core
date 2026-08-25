package rs.sud.eaukcija.controller;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import rs.sud.eaukcija.enrichment.EnrichmentAlreadyRunningException;
import rs.sud.eaukcija.enrichment.EnrichmentBacklogStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunClaim;
import rs.sud.eaukcija.enrichment.EnrichmentRunItemView;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunView;
import rs.sud.eaukcija.enrichment.EnrichmentSelector;
import rs.sud.eaukcija.enrichment.EnrichmentSelectorType;
import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentSubmissionException;
import rs.sud.eaukcija.enrichment.EnrichmentUnavailableException;

@RestController
@RequestMapping("/api/enrichment")
public class EnrichmentController {

    private final EnrichmentService service;

    public EnrichmentController(EnrichmentService service) {
        this.service = service;
    }

    @PostMapping("/runs")
    public ResponseEntity<?> start(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return problem(HttpStatus.FORBIDDEN, "ENRICHMENT_LOCAL_ONLY",
                    "Enrichment may only be triggered from a loopback client.");
        }
        UUID idempotencyKey;
        try {
            idempotencyKey = canonicalUuid(key);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be a UUID.");
        }
        try {
            return started(service.startManual(idempotencyKey));
        } catch (RuntimeException failure) {
            return mutationFailure(failure);
        }
    }

    @PostMapping("/replays")
    public ResponseEntity<?> replay(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ReplayRequest replay,
            HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return problem(HttpStatus.FORBIDDEN, "ENRICHMENT_LOCAL_ONLY",
                    "Enrichment may only be replayed from a loopback client.");
        }
        try {
            UUID idempotencyKey = canonicalUuid(key);
            EnrichmentSelector selector = selector(replay);
            int maxItems = replay.maxItems() == null ? 100 : replay.maxItems();
            if (maxItems < 1 || maxItems > 1_000) {
                throw new IllegalArgumentException("maxItems must be between 1 and 1000");
            }
            return started(service.startReplay(idempotencyKey, selector, maxItems));
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REPLAY_REQUEST",
                    "Provide exactly one valid run, auction, or version selector and a bounded maxItems.");
        } catch (RuntimeException failure) {
            return mutationFailure(failure);
        }
    }

    @PostMapping("/pause")
    public ResponseEntity<?> pause(HttpServletRequest request) {
        return control(true, request);
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume(HttpServletRequest request) {
        return control(false, request);
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        try {
            EnrichmentBacklogStatus status = service.status();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(status);
        } catch (EnrichmentUnavailableException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_UNAVAILABLE",
                    "Durable enrichment is unavailable for the active profile.");
        } catch (RuntimeException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_STATUS_UNAVAILABLE",
                    "Enrichment status or active local versions are temporarily unavailable.");
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> run(@PathVariable String runId) {
        UUID parsed;
        try {
            parsed = canonicalUuid(runId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_ENRICHMENT_RUN_ID",
                    "The enrichment run ID must be a UUID.");
        }
        if (!service.isEnabled()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_UNAVAILABLE",
                    "Durable enrichment is unavailable for the active profile.");
        }
        try {
            Optional<EnrichmentRunView> retained = service.findRun(parsed);
            if (retained.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "ENRICHMENT_RUN_NOT_FOUND",
                        "No enrichment run exists for that ID.");
            }
            List<EnrichmentRunItemView> items = service.items(parsed);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new RunResponse(retained.orElseThrow(), items));
        } catch (RuntimeException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_LEDGER_UNAVAILABLE",
                    "The enrichment ledger is temporarily unavailable.");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedReplayBody() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REPLAY_REQUEST",
                "Provide one valid bounded replay request as JSON.");
    }

    private ResponseEntity<?> control(boolean paused, HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return problem(HttpStatus.FORBIDDEN, "ENRICHMENT_LOCAL_ONLY",
                    "Enrichment control is restricted to a loopback client.");
        }
        try {
            boolean value = paused ? service.pause() : service.resume();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new ControlResponse(value));
        } catch (EnrichmentUnavailableException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_UNAVAILABLE",
                    "Durable enrichment is unavailable for the active profile.");
        } catch (RuntimeException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_LEDGER_UNAVAILABLE",
                    "The enrichment control ledger is temporarily unavailable.");
        }
    }

    private ResponseEntity<?> started(EnrichmentRunClaim claim) {
        Optional<EnrichmentRunView> retained = service.findRun(claim.runId());
        if (retained.isEmpty()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_LEDGER_UNAVAILABLE",
                    "The claimed enrichment run is temporarily unavailable.");
        }
        EnrichmentRunView run = retained.orElseThrow();
        URI location = URI.create("/api/enrichment/runs/" + claim.runId());
        HttpStatus status = claim.replayed() && run.status() != EnrichmentRunStatus.RUNNING
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .location(location)
                .cacheControl(CacheControl.noStore())
                .body(new StartedResponse(
                        claim.runId(), run.status(), location.toString(), claim.replayed()));
    }

    private static ResponseEntity<?> mutationFailure(RuntimeException failure) {
        if (failure instanceof EnrichmentAlreadyRunningException overlap) {
            ProblemDetail detail = detail(
                    HttpStatus.CONFLICT,
                    "ENRICHMENT_ALREADY_RUNNING",
                    "Another enrichment run is active.");
            detail.setProperty("activeRunId", overlap.activeRunId());
            detail.setProperty("statusUrl", "/api/enrichment/runs/" + overlap.activeRunId());
            return problem(detail);
        }
        if (failure instanceof EnrichmentUnavailableException) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_UNAVAILABLE",
                    "Durable enrichment is disabled, paused, or lacks an active local version.");
        }
        if (failure instanceof EnrichmentSubmissionException rejected) {
            ProblemDetail detail = detail(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ENRICHMENT_EXECUTOR_UNAVAILABLE",
                    "The enrichment run was recorded but could not be submitted.");
            detail.setProperty("runId", rejected.runId());
            detail.setProperty("statusUrl", "/api/enrichment/runs/" + rejected.runId());
            return problem(detail);
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "ENRICHMENT_LEDGER_UNAVAILABLE",
                "The enrichment ledger is temporarily unavailable.");
    }

    private static EnrichmentSelector selector(ReplayRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("missing replay request");
        }
        List<EnrichmentSelector> selectors = new ArrayList<>();
        if (request.sourceSyncRunId() != null) {
            selectors.add(new EnrichmentSelector(
                    EnrichmentSelectorType.SOURCE_SYNC_RUN, request.sourceSyncRunId().toString()));
        }
        if (request.enrichmentRunId() != null) {
            selectors.add(new EnrichmentSelector(
                    EnrichmentSelectorType.ENRICHMENT_RUN, request.enrichmentRunId().toString()));
        }
        if (request.auctionId() != null) {
            if (request.auctionId() <= 0) {
                throw new IllegalArgumentException("auctionId must be positive");
            }
            selectors.add(new EnrichmentSelector(
                    EnrichmentSelectorType.AUCTION, request.auctionId().toString()));
        }
        if (request.version() != null) {
            String value = request.version().trim();
            if (!value.matches("[A-Za-z0-9._:-]{1,160}")) {
                throw new IllegalArgumentException("version selector is invalid");
            }
            selectors.add(new EnrichmentSelector(EnrichmentSelectorType.VERSION, value));
        }
        if (selectors.size() != 1) {
            throw new IllegalArgumentException("exactly one selector is required");
        }
        return selectors.get(0);
    }

    private static UUID canonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing uuid");
        }
        String trimmed = value.trim();
        UUID parsed = UUID.fromString(trimmed);
        if (!parsed.toString().equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("noncanonical uuid");
        }
        return parsed;
    }

    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String description) {
        return problem(detail(status, code, description));
    }

    private static ProblemDetail detail(HttpStatus status, String code, String description) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, description);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        return detail;
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail detail) {
        return ResponseEntity.status(detail.getStatus())
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail);
    }

    public record ReplayRequest(
            UUID sourceSyncRunId,
            UUID enrichmentRunId,
            Long auctionId,
            String version,
            Integer maxItems) {
    }

    public record StartedResponse(
            UUID runId,
            EnrichmentRunStatus status,
            String statusUrl,
            boolean replayed) {
    }

    public record ControlResponse(boolean paused) {
    }

    public record RunResponse(EnrichmentRunView run, List<EnrichmentRunItemView> items) {
    }
}
