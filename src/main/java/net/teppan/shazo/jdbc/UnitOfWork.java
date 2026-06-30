package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Repository;

import java.sql.Connection;

/**
 * A single transactional unit of work over a relational database.
 *
 * <p>A {@code UnitOfWork} is handed to a {@link UnitOfWorkTask} by a
 * {@link Transactor}. Within the task, every repository obtained from
 * {@link #repository(Describer)} shares the same JDBC {@link Connection}, so
 * operations across multiple repositories are committed (or rolled back)
 * together as one transaction. This generalizes
 * {@link JdbcRepository#transact(StorageTask)} from a single repository to any
 * number of repositories and domain types.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var transactor = new Transactor(dataSource);
 *
 * String orderId = transactor.execute(uow -> {
 *     Repository<Order> orders = uow.repository(orderDescriber);
 *     Repository<AuditLog> logs = uow.repository(auditDescriber);
 *
 *     orders.store(order);          // both stored in the SAME transaction
 *     logs.store(new AuditLog(order.id(), "created"));
 *     return order.id();
 * });
 * }</pre>
 *
 * <p>The {@link Connection} is also exposed via {@link #connection()} for the
 * occasional statement that does not fit the repository model; it must not be
 * committed, rolled back, or closed by the task — the {@link Transactor} owns
 * its lifecycle.
 *
 * @see Transactor
 * @see UnitOfWorkTask
 */
public interface UnitOfWork {

    /**
     * Returns a repository for {@code describer} bound to this unit of work's
     * transaction. Repositories returned within the same unit of work all share
     * one connection and therefore one transaction.
     *
     * @param describer the describer for domain type {@code T}; never {@code null}
     * @param <T>       the domain type
     * @return a transaction-scoped repository
     */
    <T> Repository<T> repository(Describer<T, SqlCommand> describer);

    /**
     * Returns the JDBC connection backing this unit of work, for statements that
     * do not fit the repository model.
     *
     * <p>The connection participates in the surrounding transaction, whose
     * boundary the unit of work owns. The returned connection is a guarded view:
     * {@code commit()}, {@code rollback()}, {@code close()},
     * {@code setAutoCommit(boolean)}, and {@code abort(Executor)} throw
     * {@link UnsupportedOperationException}. Every other operation is forwarded to
     * the real connection.
     *
     * @return the transaction's connection; never {@code null}
     */
    Connection connection();
}
