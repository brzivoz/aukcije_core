package rs.sud.eaukcija.refresh;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
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

import rs.sud.eaukcija.operations.OperatorRequestGuard;

/** Loopback-only one-click refresh mutation and persisted workflow reads. */
@RestController
@RequestMapping("/api/operator/refresh")
@Profile("!local-h2")
public class RefreshController {

    private static final Logger log = LoggerFactory.getLogger(RefreshController.class);

    private final RefreshCoordinator coordinator;

    public RefreshController(RefreshCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping
    public ResponseEntity<?> start(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        if (!OperatorRequestGuard.isTrustedMutation(request)) {
            return problem(HttpStatus.FORBIDDEN, "REFRESH_MUTATION_FORBIDDEN",
                    "Refresh mutations require a loopback same-origin operator request.");
        }
        UUID key;
        try {
            key = parseUuid(idempotencyKey);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be a canonical UUID.");
        }
        try {
            RefreshClaim claim = coordinator.startManual(key);
            URI statusUrl = statusUrl(claim.workflowId());
            return ResponseEntity.status(claim.replayed() && !claim.alreadyRunning()
                            ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .location(statusUrl)
                    .cacheControl(CacheControl.noStore())
                    .body(new RefreshStartedResponse(
                            claim.workflowId(), claim.alreadyRunning(), claim.replayed(),
                            statusUrl.toString()));
        } catch (RefreshUnavailableException unavailable) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "REFRESH_UNAVAILABLE",
                    "The durable refresh workflow is unavailable for the active profile.");
        } catch (RefreshSubmissionException rejected) {
            ProblemDetail detail = detail(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "REFRESH_EXECUTOR_UNAVAILABLE",
                    "The workflow was retained but could not be submitted.");
            detail.setProperty("workflowId", rejected.workflowId());
            detail.setProperty("statusUrl", statusUrl(rejected.workflowId()).toString());
            return problem(detail);
        } catch (RuntimeException unavailable) {
            log.error("Refresh trigger failed code=REFRESH_LEDGER_UNAVAILABLE");
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "REFRESH_LEDGER_UNAVAILABLE",
                    "Persisted refresh evidence is temporarily unavailable.");
        }
    }

    @GetMapping
    public ResponseEntity<?> latest(HttpServletRequest request) {
        if (!OperatorRequestGuard.isLoopback(request)) {
            return problem(HttpStatus.FORBIDDEN, "REFRESH_LOCAL_ONLY",
                    "Refresh workflow status is restricted to a loopback client.");
        }
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(coordinator.latestState());
        } catch (RuntimeException unavailable) {
            log.error("Refresh status failed code=REFRESH_LEDGER_UNAVAILABLE");
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "REFRESH_LEDGER_UNAVAILABLE",
                    "Persisted refresh evidence is temporarily unavailable.");
        }
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<?> status(
            @PathVariable String workflowId,
            HttpServletRequest request) {
        if (!OperatorRequestGuard.isLoopback(request)) {
            return problem(HttpStatus.FORBIDDEN, "REFRESH_LOCAL_ONLY",
                    "Refresh workflow status is restricted to a loopback client.");
        }
        UUID parsed;
        try {
            parsed = parseUuid(workflowId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REFRESH_WORKFLOW_ID",
                    "The refresh workflow ID must be a canonical UUID.");
        }
        try {
            Optional<RefreshWorkflowState> retained = coordinator.findState(parsed);
            if (retained.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "REFRESH_WORKFLOW_NOT_FOUND",
                        "No refresh workflow exists for that ID.");
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(retained.orElseThrow());
        } catch (RuntimeException unavailable) {
            log.error("Refresh status failed workflowId={} code=REFRESH_LEDGER_UNAVAILABLE", parsed);
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "REFRESH_LEDGER_UNAVAILABLE",
                    "Persisted refresh evidence is temporarily unavailable.");
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing UUID");
        }
        UUID parsed = UUID.fromString(value.trim());
        if (!parsed.toString().equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException("noncanonical UUID");
        }
        return parsed;
    }

    private static URI statusUrl(UUID workflowId) {
        return URI.create("/api/operator/refresh/" + workflowId);
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

    public record RefreshStartedResponse(
            UUID workflowId,
            boolean alreadyRunning,
            boolean replayed,
            String statusUrl) {
    }
}
