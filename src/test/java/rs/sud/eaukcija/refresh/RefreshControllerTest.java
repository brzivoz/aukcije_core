package rs.sud.eaukcija.refresh;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import rs.sud.eaukcija.operations.OperatorRequestGuard;

@WebMvcTest(RefreshController.class)
@ActiveProfiles("test")
class RefreshControllerTest {

    private static final UUID WORKFLOW = UUID.fromString("40000000-0000-4000-8000-000000000040");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RefreshCoordinator coordinator;

    @Test
    void loopbackSameOriginClickReturnsTheSharedWorkflowImmediately() throws Exception {
        when(coordinator.startManual(any()))
                .thenReturn(new RefreshClaim(WORKFLOW, true, false));

        mvc.perform(post("/api/operator/refresh")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header(OperatorRequestGuard.REQUEST_HEADER, OperatorRequestGuard.REQUEST_VALUE)
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Origin", "http://localhost")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            request.setServerName("localhost");
                            request.setServerPort(80);
                            return request;
                        }))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.workflowId").value(WORKFLOW.toString()))
                .andExpect(jsonPath("$.alreadyRunning").value(true));
    }

    @Test
    void crossSiteOrHeaderlessMutationIsRejected() throws Exception {
        mvc.perform(post("/api/operator/refresh")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header(OperatorRequestGuard.REQUEST_HEADER, OperatorRequestGuard.REQUEST_VALUE)
                        .header("Sec-Fetch-Site", "cross-site")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REFRESH_MUTATION_FORBIDDEN"));

        mvc.perform(post("/api/operator/refresh")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void persistedReadIsNoStoreAndContainsOnlyLocalizedFailureText() throws Exception {
        when(coordinator.findState(WORKFLOW)).thenReturn(Optional.of(state()));

        mvc.perform(get("/api/operator/refresh/{id}", WORKFLOW)
                        .with(request -> {
                            request.setRemoteAddr("::1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage").value("PROCESS_LOCATIONS"))
                .andExpect(jsonPath("$.failureMessage").value("Обрада локација није завршена. Покушајте поново."));
    }

    private static RefreshWorkflowState state() {
        return new RefreshWorkflowState(
                true, WORKFLOW, "MANUAL", "FAILED", "PROCESS_LOCATIONS",
                Instant.parse("2026-08-25T10:00:00Z"), Instant.parse("2026-08-25T10:01:00Z"),
                60, 1, 1, 1, 1, 1, 1, 0, 0, Map.of(),
                null, null, null, null, null, "ENRICHMENT_FAILED",
                "Обрада локација није завршена. Покушајте поново.",
                null, null, true, "Europe/Belgrade", Instant.parse("2026-08-26T01:00:00Z"));
    }
}
