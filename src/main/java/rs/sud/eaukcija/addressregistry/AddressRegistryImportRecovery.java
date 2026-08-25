package rs.sud.eaukcija.addressregistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Finalizes import attempts whose owning JVM exited before a terminal update. */
@Component
@Profile("!local-h2")
public class AddressRegistryImportRecovery {

    private static final Logger log = LoggerFactory.getLogger(AddressRegistryImportRecovery.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AddressRegistryImportRecovery(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAfterStartup() {
        int recovered = reconcileAbandonedRuns();
        if (recovered > 0) {
            log.warn(
                    "Address Registry import recovery completed code=IMPORT_PROCESS_RESTARTED recoveredJobs={}",
                    recovered);
        }
    }

    int reconcileAbandonedRuns() {
        Integer recovered = transactions.execute(status -> {
            Boolean acquired = jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)",
                    Boolean.class,
                    AddressRegistryImportLock.LOCK_ID);
            if (!Boolean.TRUE.equals(acquired)) {
                return 0;
            }
            return jdbc.update("""
                    UPDATE address_registry_import_runs
                       SET outcome = 'FAILED',
                           finished_at = CURRENT_TIMESTAMP,
                           total_millis = GREATEST(
                               0,
                               FLOOR(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint
                           ),
                           error_code = 'IMPORT_PROCESS_RESTARTED',
                           error_message = NULL
                     WHERE outcome = 'RUNNING'
                    """);
        });
        return recovered == null ? 0 : recovered;
    }
}
