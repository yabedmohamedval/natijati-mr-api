package mr.natijati.results.importing.async;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;

/** One workbook across all workers. Requires direct PostgreSQL or a SESSION pooler. */
@Component
public class ImportWorkerLock {
    private final DataSource datasource;
    private static final long KEY = 718438240159L;
    public ImportWorkerLock(DataSource datasource) { this.datasource=datasource; }
    public Handle acquire() throws SQLException {
        Connection connection=datasource.getConnection();
        try (var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1,KEY);
            try (var result=statement.executeQuery()) {
                result.next();
                if (!result.getBoolean(1)) throw new IllegalStateException("Un worker est déjà actif ; réessayer cette exécution");
            }
            return new Handle(connection);
        } catch (SQLException | RuntimeException ex) { connection.close(); throw ex; }
    }
    public static final class Handle implements AutoCloseable {
        private final Connection connection;
        private Handle(Connection connection) { this.connection=connection; }
        public void close() throws SQLException {
            try (var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1,KEY); statement.execute();
            } finally { connection.close(); }
        }
    }
}
