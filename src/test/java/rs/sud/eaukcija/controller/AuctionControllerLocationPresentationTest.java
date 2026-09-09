package rs.sud.eaukcija.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import rs.sud.eaukcija.filter.*;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.service.SyncService;

@WebMvcTest(AuctionController.class)
@Import({AuctionFilterParser.class, AuctionResultsService.class})
@ActiveProfiles("test")
class AuctionControllerLocationPresentationTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AuctionRepository auctions;
    @MockitoBean private rs.sud.eaukcija.history.SourceHistoryService history;
    @MockitoBean private AuctionSearchRepository search;
    @MockitoBean private SyncService syncService;

    @BeforeEach void page() {
        given(search.page(any(), anyLong())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
    }
    @Test void listUiLabelsOnlyPublishableWinnersAndShowsTheHonestyNotice() throws Exception {
        Auction auction = new Auction(); auction.setId(42L); auction.setAuctionNumber("Н42");
        given(search.page(any(), anyLong())).willReturn(new PageImpl<>(List.of(auction), PageRequest.of(0, 25), 1));
        given(search.precisions(eq(List.of(42L)), any())).willReturn(Map.of(42L, List.of("SETTLEMENT")));
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Центар насеља")))
                .andExpect(content().string(containsString("приближна локација области, не адреса, улица или парцела")))
                .andExpect(content().string(containsString("Непознат завршетак")));
    }
    @Test void sharedSortValidationReturnsStructured400InsteadOf500() throws Exception {
        mvc.perform(get("/").param("sortBy", "description; DROP TABLE auctions"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sortBy"));
    }
    @Test void ledgerFailureDoesNotTakeDownTheAuctionPageOrLeakItsCause() throws Exception {
        String sentinel = "password=secret bearer-token personal-name thumbnail-base64";
        given(syncService.isEnabled()).willReturn(true);
        given(syncService.findLatestRun()).willThrow(new IllegalStateException(sentinel));
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuctionController.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>(); events.start(); logger.addAppender(events);
        try {
            mvc.perform(get("/")).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Статус синхронизације тренутно није доступан")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString(sentinel))));
            org.assertj.core.api.Assertions.assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("eAukcija page sync status unavailable code=SYNC_LEDGER_UNAVAILABLE");
            org.assertj.core.api.Assertions.assertThat(events.list).allSatisfy(event ->
                    org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy()).isNull());
        } finally { logger.detachAppender(events); events.stop(); }
    }
}
