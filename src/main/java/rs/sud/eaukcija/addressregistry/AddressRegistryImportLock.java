package rs.sud.eaukcija.addressregistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Session-scoped import lock held across staging, validation, and promotion. */
@Component
@Profile("!local-h2")
final class AddressRegistryImportLock {

    static final long LOCK_ID = 220_258_344L;

    private final DataSource dataSource;

    AddressRegistryImportLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    Optional<Lease> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK_ID);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(new Lease(connection));
        } catch (SQLException failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new AddressRegistryImportException(
                    "IMPORT_LOCK_FAILED", "could not acquire the Address Registry import lock", failure);
        }
    }

    static final class Lease implements AutoCloseable {

        private final Connection connection;
        private boolean held = true;

        private Lease(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            if (!held) {
                return;
            }
            held = false;
            RuntimeException failure = null;
            boolean unlocked = false;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, LOCK_ID);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException(
                                "PostgreSQL Address Registry import lock was not held by this session");
                    }
                    unlocked = true;
                }
            } catch (SQLException | RuntimeException unlockFailure) {
                failure = new IllegalStateException(
                        "could not release the Address Registry import lock", unlockFailure);
            }

            if (!unlocked) {
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                if (failure == null) {
                    failure = new IllegalStateException(
                            "could not close the Address Registry import-lock connection", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
