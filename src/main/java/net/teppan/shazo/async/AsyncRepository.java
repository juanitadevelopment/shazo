package net.teppan.shazo.async;

import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An asynchronous wrapper over any {@link Repository}, returning
 * {@link CompletableFuture} from all operations.
 *
 * <p>By default, operations execute on a virtual-thread-per-task executor
 * (Project Loom). Supply a custom {@link Executor} if you need a bounded
 * thread pool, tracing, or a specific scheduling policy.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var async = new AsyncRepository<>(jdbcRepository);
 *
 * async.retrieve(query)
 *      .thenAccept(opt -> opt.ifPresent(this::render))
 *      .exceptionally(ex -> { log.error("retrieval failed", ex); return null; });
 *
 * async.catalog(criteria)
 *      .thenApply(persons -> persons.stream().map(Person::name).toList())
 *      .thenAccept(names -> ...);
 * }</pre>
 *
 * <h2>Exception handling</h2>
 * <p>{@link ShazoException} is wrapped in a {@link CompletionException} and
 * delivered through the future's failure channel. Unwrap it with
 * {@code future.get()} or {@code exceptionally()}.
 *
 * <h2>Lifecycle</h2>
 * <p>When constructed without an executor, this class owns an internal
 * virtual-thread executor and must be {@linkplain #close() closed} to release
 * it — use it in a try-with-resources block. When an executor is supplied by
 * the caller, the caller owns it and {@link #close()} leaves it untouched.
 *
 * @param <T> the domain type managed by the underlying repository
 */
public final class AsyncRepository<T> implements AutoCloseable {

    private final Repository<T> delegate;
    private final Executor executor;
    private final boolean ownsExecutor;

    /**
     * Constructs an {@code AsyncRepository} using an internally-owned
     * virtual-thread-per-task executor. The instance must be closed to release
     * the executor.
     *
     * @param delegate the underlying repository; never {@code null}
     */
    public AsyncRepository(Repository<T> delegate) {
        this(delegate, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /**
     * Constructs an {@code AsyncRepository} with a caller-supplied executor.
     * The caller retains ownership; {@link #close()} does not shut it down.
     *
     * @param delegate the underlying repository; never {@code null}
     * @param executor the executor for async operations; never {@code null}
     */
    public AsyncRepository(Repository<T> delegate, Executor executor) {
        this(delegate, executor, false);
    }

    private AsyncRepository(Repository<T> delegate, Executor executor, boolean ownsExecutor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Asynchronously tests whether a matching entity exists in storage.
     *
     * @param query the query object
     * @return a future that completes with {@code true} if a match exists,
     *         or fails with a {@link CompletionException} wrapping a
     *         {@link ShazoException}
     */
    public CompletableFuture<Boolean> contains(T query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.contains(query);
            } catch (ShazoException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Asynchronously persists {@code entity}.
     *
     * @param entity the entity to store
     * @return a future that completes when the entity has been stored,
     *         or fails with a {@link CompletionException}
     */
    public CompletableFuture<Void> store(T entity) {
        return CompletableFuture.runAsync(() -> {
            try {
                delegate.store(entity);
            } catch (ShazoException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Asynchronously deletes the entity matching {@code entity}.
     *
     * @param entity the entity to delete
     * @return a future that completes when deletion is done, or fails
     */
    public CompletableFuture<Void> delete(T entity) {
        return CompletableFuture.runAsync(() -> {
            try {
                delegate.delete(entity);
            } catch (ShazoException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Asynchronously retrieves the entity matching {@code query}.
     *
     * @param query the query object
     * @return a future that completes with the matching entity or
     *         {@link Optional#empty()}, or fails with a
     *         {@link CompletionException}
     */
    public CompletableFuture<Optional<T>> retrieve(T query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.retrieve(query);
            } catch (ShazoException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Asynchronously retrieves all entities matching {@code query}.
     *
     * @param query the query/filter object
     * @return a future that completes with an immutable list of matches,
     *         or fails with a {@link CompletionException}
     */
    public CompletableFuture<List<T>> catalog(T query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.catalog(query);
            } catch (ShazoException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Returns the underlying synchronous repository.
     *
     * @return the delegate; never {@code null}
     */
    public Repository<T> synchronous() {
        return delegate;
    }

    /**
     * Shuts down the internally-owned executor, awaiting completion of
     * in-flight tasks. Has no effect when the executor was supplied by the
     * caller, and is idempotent.
     */
    @Override
    public void close() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.close();
        }
    }
}
