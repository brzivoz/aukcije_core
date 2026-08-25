package rs.sud.eaukcija.controller;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.operations.OperatorRequestGuard;
import rs.sud.eaukcija.service.SyncSubmissionException;
import rs.sud.eaukcija.service.SyncUnavailableException;
import rs.sud.eaukcija.sync.persistence.PersistedAuctionDetailQuarantine;
import rs.sud.eaukcija.sync.persistence.PersistedAuctionListingQuarantine;
import rs.sud.eaukcija.sync.persistence.SyncAlreadyRunningException;
import rs.sud.eaukcija.sync.persistence.PersistedSyncRunError;
import rs.sud.eaukcija.sync.persistence.SyncRunChildResult;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunRootResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;
import rs.sud.eaukcija.sync.persistence.SyncTriggerKind;

@RestController
@RequestMapping("/api/sync/runs")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping
    public ResponseEntity<?> start(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())
                || !OperatorRequestGuard.isSameOriginBrowserContext(request)) {
            return problem(HttpStatus.FORBIDDEN, "SYNC_LOCAL_ONLY",
                    "Synchronization may only be triggered from a loopback client.");
        }
        UUID key;
        try {
            key = parseIdempotencyKey(idempotencyKey);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be a UUID.");
        }

        try {
            SyncRunClaimResult claim = syncService.startManual(key);
            Optional<SyncRunView> retained;
            try {
                retained = syncService.findRun(claim.runId());
            } catch (RuntimeException ledgerFailure) {
                log.error("eAukcija sync ledger unavailable runId={} code=SYNC_LEDGER_UNAVAILABLE",
                        claim.runId());
                return claimedButUnavailable(claim.runId());
            }
            if (retained.isEmpty()) {
                log.error("eAukcija sync ledger missing claimed runId={} code=SYNC_LEDGER_UNAVAILABLE",
                        claim.runId());
                return claimedButUnavailable(claim.runId());
            }
            SyncRunView view = retained.orElseThrow();
            URI statusUri = statusUri(claim.runId());
            HttpStatus responseStatus = claim.replayed() && view.status() != SyncRunStatus.RUNNING
                    ? HttpStatus.OK
                    : HttpStatus.ACCEPTED;
            return ResponseEntity.status(responseStatus)
                    .location(statusUri)
                    .cacheControl(CacheControl.noStore())
                    .body(new SyncRunStartedResponse(
                            claim.runId(), view.status(), statusUri.toString(), claim.replayed()));
        } catch (SyncAlreadyRunningException active) {
            ProblemDetail detail = detail(
                    HttpStatus.CONFLICT,
                    "SYNC_ALREADY_RUNNING",
                    "Another synchronization run is active.");
            detail.setProperty("activeRunId", active.activeRunId());
            detail.setProperty("statusUrl", statusUri(active.activeRunId()).toString());
            return problem(detail);
        } catch (SyncUnavailableException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SYNC_UNAVAILABLE",
                    "Durable synchronization is unavailable for the active profile.");
        } catch (SyncSubmissionException submission) {
            ProblemDetail detail = detail(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SYNC_EXECUTOR_UNAVAILABLE",
                    "The durable run was recorded but could not be submitted.");
            detail.setProperty("runId", submission.runId());
            detail.setProperty("statusUrl", statusUri(submission.runId()).toString());
            return problem(detail);
        } catch (RuntimeException ledgerFailure) {
            log.error("eAukcija sync trigger failed code=SYNC_LEDGER_UNAVAILABLE");
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SYNC_LEDGER_UNAVAILABLE",
                    "The durable synchronization ledger is temporarily unavailable.");
        }
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> status(@PathVariable String runId) {
        UUID parsed;
        try {
            parsed = parseRunId(runId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_SYNC_RUN_ID",
                    "The synchronization run ID must be a UUID.");
        }
        if (!syncService.isEnabled()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SYNC_UNAVAILABLE",
                    "Durable synchronization is unavailable for the active profile.");
        }
        try {
            Optional<SyncRunView> retained = syncService.findRun(parsed);
            if (retained.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "SYNC_RUN_NOT_FOUND",
                        "No synchronization run exists for that ID.");
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(SyncRunStatusResponse.from(
                            retained.orElseThrow(),
                            syncService.rootResults(parsed),
                            syncService.childResults(parsed),
                            syncService.listingQuarantines(parsed),
                            syncService.detailQuarantines(parsed),
                            syncService.errors(parsed)));
        } catch (RuntimeException ledgerFailure) {
            log.error("eAukcija sync status failed runId={} code=SYNC_LEDGER_UNAVAILABLE", parsed);
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SYNC_LEDGER_UNAVAILABLE",
                    "The durable synchronization ledger is temporarily unavailable.");
        }
    }

    private static UUID parseIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing key");
        }
        UUID parsed = UUID.fromString(value.trim());
        if (!parsed.toString().equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException("noncanonical key");
        }
        return parsed;
    }

    private static UUID parseRunId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing run id");
        }
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("noncanonical run id");
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

    private static URI statusUri(UUID runId) {
        return URI.create("/api/sync/runs/" + runId);
    }

    private static ResponseEntity<ProblemDetail> claimedButUnavailable(UUID runId) {
        ProblemDetail detail = detail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SYNC_LEDGER_UNAVAILABLE",
                "The run was claimed, but its retained status is temporarily unavailable.");
        detail.setProperty("runId", runId);
        detail.setProperty("statusUrl", statusUri(runId).toString());
        return problem(detail);
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

    public record SyncRunStartedResponse(
            UUID runId,
            SyncRunStatus status,
            String statusUrl,
            boolean replayed) {
    }

    public record SyncRunStatusResponse(
            UUID runId,
            SyncTriggerKind triggerKind,
            SyncRunStatus status,
            SyncRunStage stage,
            Instant startedAt,
            Instant heartbeatAt,
            Instant finishedAt,
            List<Integer> configuredRoots,
            int pageSize,
            String categoryTreeSha256,
            Instant categoryTreeObservedAt,
            int pagesExpected,
            int pagesCompleted,
            long listingRowsObserved,
            long listingRowsQuarantined,
            long uniqueAuctionCount,
            long duplicateAuctionCount,
            long unknownPropertyKindCount,
            long detailsRequired,
            long detailsAttempted,
            long detailsSucceeded,
            long detailsQuarantined,
            long detailsFailed,
            long retryCount,
            long errorCount,
            long unresolvedErrorCount,
            List<SyncRunRootResult> rootResults,
            List<SyncRunChildResult> childResults,
            List<PersistedAuctionListingQuarantine> listingQuarantines,
            List<PersistedAuctionDetailQuarantine> detailQuarantines,
            List<PersistedSyncRunError> errors) {

        static SyncRunStatusResponse from(
                SyncRunView view,
                List<SyncRunRootResult> rootResults,
                List<SyncRunChildResult> childResults,
                List<PersistedAuctionListingQuarantine> listingQuarantines,
                List<PersistedAuctionDetailQuarantine> detailQuarantines,
                List<PersistedSyncRunError> errors) {
            return new SyncRunStatusResponse(
                    view.runId(),
                    view.triggerKind(),
                    view.status(),
                    view.stage(),
                    view.startedAt(),
                    view.heartbeatAt(),
                    view.finishedAt(),
                    view.configuredRoots(),
                    view.pageSize(),
                    view.categoryTreeSha256(),
                    view.categoryTreeObservedAt(),
                    view.pagesExpected(),
                    view.pagesCompleted(),
                    view.listingRowsObserved(),
                    view.listingRowsQuarantined(),
                    view.uniqueAuctionCount(),
                    view.duplicateAuctionCount(),
                    view.unknownPropertyKindCount(),
                    view.detailsRequired(),
                    view.detailsAttempted(),
                    view.detailsSucceeded(),
                    view.detailsQuarantined(),
                    view.detailsFailed(),
                    view.retryCount(),
                    view.errorCount(),
                    view.unresolvedErrorCount(),
                    List.copyOf(rootResults),
                    List.copyOf(childResults),
                    List.copyOf(listingQuarantines),
                    List.copyOf(detailQuarantines),
                    List.copyOf(errors));
        }
    }
}
