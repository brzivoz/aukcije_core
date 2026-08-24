package rs.sud.eaukcija.sync.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** A PostgreSQL session advisory lock held without a long-running transaction. */
public final class WorkerLockLease implements AutoCloseable {

    private final Connection connection;
    private final long lockId;
    private boolean held;

    WorkerLockLease(Connection connection, long lockId) {
        this.connection = connection;
        this.lockId = lockId;
        this.held = true;
    }

    boolean isHeld() {
        return held;
    }

    Connection connection() {
        if (!held) {
            throw new IllegalStateException("worker lock lease is closed");
        }
        return connection;
    }

    @Override
    public void close() {
        if (!held) {
            return;
        }
        held = false;
        RuntimeException failure = null;
        boolean unlocked = false;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, lockId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("PostgreSQL worker lock was not held by this session");
                }
                unlocked = true;
            }
        } catch (SQLException | RuntimeException unlockFailure) {
            failure = new IllegalStateException("could not release PostgreSQL worker lock", unlockFailure);
        }

        if (!unlocked) {
            try {
                // Returning a pooled connection after an uncertain unlock can
                // preserve a session advisory lock. JDBC abort closes the
                // physical PostgreSQL session before the proxy is returned.
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
                        "could not close PostgreSQL worker-lock connection", closeFailure);
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
