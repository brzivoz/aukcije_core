package rs.sud.eaukcija.operations;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only, payload-safe operator status API. */
@RestController
@RequestMapping("/api/operator/status")
@Profile("!local-h2")
public class OperatorStatusController {

    private static final Logger log = LoggerFactory.getLogger(OperatorStatusController.class);

    private final PipelineStatusService service;

    public OperatorStatusController(PipelineStatusService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> status(HttpServletRequest request) {
        if (!LoopbackRequest.isLoopback(request)) {
            return problem(HttpStatus.FORBIDDEN, "OPERATOR_STATUS_LOCAL_ONLY",
                    "Operator status is restricted to a loopback client.");
        }
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(service.status());
        } catch (RuntimeException unavailable) {
            log.error("Operator status unavailable code=STATUS_EVIDENCE_UNAVAILABLE");
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_EVIDENCE_UNAVAILABLE",
                    "Persisted pipeline status evidence is temporarily unavailable.");
        }
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String description) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, description);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail);
    }
}
