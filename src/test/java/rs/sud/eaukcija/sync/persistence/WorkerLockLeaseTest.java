package rs.sud.eaukcija.sync.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

class WorkerLockLeaseTest {

    @Test
    void abortsThePhysicalSessionWhenUnlockIsUncertain() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(any())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("synthetic unlock failure"));
        WorkerLockLease lease = new WorkerLockLease(connection, 17_000_002L);

        assertThatThrownBy(lease::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("could not release PostgreSQL worker lock");

        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }
}
