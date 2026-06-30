package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Runs {@link UnitOfWorkTask}s against a {@link DataSource}, each within a single
 * JDBC transaction.
 *
 * <p>For each call to {@link #execute(UnitOfWorkTask)} a connection is borrowed
 * from the pool with auto-commit disabled, the task is run with a
 * {@link UnitOfWork} bound to that connection, and the transaction is committed
 * if the task returns normally or rolled back if it throws. Repositories the
 * task obtains from the unit of work all share the connection, so multi-entity
 * work commits atomically.
 *
 * <pre>{@code
 * var transactor = new Transactor(dataSource);
 * transactor.execute(uow -> {
 *     uow.repository(orderDescriber).store(order);
 *     uow.repository(auditDescriber).store(audit);
 *     return null;
 * });
 * }</pre>
 *
 * <p>{@code Transactor} is thread-safe: each {@code execute} call uses its own
 * connection and unit of work.
 *
 * @see UnitOfWork
 * @see UnitOfWorkTask
 */
public final class Transactor {

    private static final Logger log = LoggerFactory.getLogger(Transactor.class);

    private final DataSource dataSource;

    /**
     * Constructs a {@code Transactor} over the given data source.
     *
     * @param dataSource the JDBC data source; never {@code null}
     */
    public Transactor(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Executes {@code task} within a single transaction.
     *
     * @param task the unit of work; never {@code null}
     * @param <R>  the result type
     * @return the value returned by the task
     * @throws ShazoException if the task fails (after rollback) or a connection
     *                        cannot be obtained
     */
    public <R> R execute(UnitOfWorkTask<R> task) throws ShazoException {
        Objects.requireNonNull(task, "task");
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                R result = task.perform(new BoundUnitOfWork(conn));
                conn.commit();
                return result;
            } catch (ShazoException e) {
                safeRollback(conn);
                throw e;
            } catch (Exception e) {
                safeRollback(conn);
                throw new ShazoException("Unit of work failed", e);
            }
        } catch (ShazoException e) {
            throw e;
        } catch (SQLException e) {
            throw new ShazoException("Failed to obtain JDBC connection for unit of work", e);
        }
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("Rollback failed", e);
        }
    }

    /** A unit of work bound to one connection; vends repositories on demand. */
    private static final class BoundUnitOfWork implements UnitOfWork {

        private final Connection connection;
        private final Connection guarded;

        BoundUnitOfWork(Connection connection) {
            this.connection = connection;
            this.guarded = GuardedConnection.wrap(connection);
        }

        @Override
        public <T> Repository<T> repository(Describer<T, SqlCommand> describer) {
            return new BoundJdbcRepository<>(connection, describer);
        }

        @Override
        public Connection connection() {
            // Hand application code a guarded view: it may run statements but must
            // not commit/rollback/close the transaction the Transactor owns.
            return guarded;
        }
    }
}
