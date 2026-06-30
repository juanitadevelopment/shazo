package net.teppan.shazo.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;

/**
 * Wraps a {@link Connection} so that callers cannot break the transaction
 * boundary the {@link UnitOfWork} owns.
 *
 * <p>The connection handed to application code through {@link UnitOfWork#connection()}
 * participates in a transaction managed by the {@link Transactor}: the
 * transactor disables auto-commit, then commits or rolls back at the unit-of-work
 * boundary. If application code were to {@code commit()}, {@code rollback()},
 * {@code close()}, {@code setAutoCommit(true)}, or {@code abort(...)} that
 * connection itself, the container's guarantee would silently break.
 *
 * <p>This guard returns a dynamic proxy that forwards every method to the real
 * connection <em>except</em> the lifecycle/boundary operations below, which throw
 * {@link UnsupportedOperationException}:
 *
 * <ul>
 *   <li>{@code commit()}</li>
 *   <li>{@code rollback()} and {@code rollback(Savepoint)}</li>
 *   <li>{@code close()}</li>
 *   <li>{@code setAutoCommit(boolean)}</li>
 *   <li>{@code abort(Executor)}</li>
 * </ul>
 *
 * <p>A dynamic proxy is used deliberately. {@link Connection} gains methods
 * across JDBC/JDK versions (e.g. {@code abort}/{@code setNetworkTimeout} in JDBC
 * 4.1, {@code beginRequest}/{@code setShardingKey} as default methods in JDBC
 * 4.3); a hand-written delegating wrapper would fail to forward those — silently,
 * in the default-method case. Forwarding by reflection keeps the guard correct
 * as the interface evolves: new methods reach the real connection automatically,
 * and only the named boundary operations are intercepted.
 *
 * <p>The unchecked {@link UnsupportedOperationException} is intentional: unlike a
 * {@link java.sql.SQLException}, it will not be swallowed by a caller's
 * {@code catch (SQLException)} block, so contract violations surface loudly.
 *
 * <p>This is a guard against <em>accidental</em> misuse, not a security boundary.
 * Code that deliberately calls {@link Connection#unwrap(Class)} can still reach
 * the underlying connection.
 */
final class GuardedConnection {

    /**
     * Methods intercepted by name. {@code Connection} has no overloads of these
     * that should remain callable, so name-based matching is sufficient (and
     * matches both {@code rollback()} and {@code rollback(Savepoint)}, as the
     * original framework's restricted connection did).
     */
    private static final Set<String> FORBIDDEN =
            Set.of("commit", "rollback", "close", "setAutoCommit", "abort");

    private GuardedConnection() {
    }

    /**
     * Returns a guarded view of {@code delegate} that forbids the transaction
     * boundary operations listed in this class's documentation.
     *
     * @param delegate the real transaction connection; never {@code null}
     * @return a {@link Connection} proxy guarding {@code delegate}
     */
    static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                GuardedConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new Handler(delegate));
    }

    private record Handler(Connection delegate) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (FORBIDDEN.contains(method.getName())) {
                throw new UnsupportedOperationException(
                        method.getName() + "() is managed by the unit of work and "
                        + "must not be called on a transaction-scoped connection");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                // Unwrap so callers see the connection's real exception (e.g. SQLException).
                throw e.getCause();
            }
        }
    }
}
